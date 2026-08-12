package com.nexusiq.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.kafka.KafkaContainer;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the real consumers end to end against Testcontainers Kafka: a
 * duplicate document.processed event must apply exactly once (Phase 2
 * acceptance criterion 5, testing.md scenario 8), and a poison message must
 * reach the DLQ after the bounded retry policy (acceptance criterion 7,
 * testing.md scenario 9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(
        statements =
                "TRUNCATE TABLE audit_events, processed_events, document_chunks, workspace_members, workspaces, documents, users CASCADE")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentEventConsumersIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    @Order(1)
    void duplicateDocumentProcessedEvent_appliesExactlyOnce() throws Exception {
        String token = registerAndLogin("consumer1@acme.com");
        String workspaceId = createWorkspace(token, "Consumer Workspace 1");
        String documentId = uploadDocument(token, workspaceId);

        UUID eventId = UUID.randomUUID();
        String envelope = processedEnvelopeJson(eventId, UUID.fromString(workspaceId), UUID.fromString(documentId), 7);

        kafkaTemplate.send(KafkaTopics.DOCUMENT_PROCESSED, workspaceId, envelope).get();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.chunk_count").value(7)));

        // Redeliver the identical event_id — this is exactly what a rebalance or
        // an at-least-once producer retry looks like on the wire.
        kafkaTemplate.send(KafkaTopics.DOCUMENT_PROCESSED, workspaceId, envelope).get();

        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(processedEventRepository.count()).isEqualTo(1));
    }

    @Test
    @Order(2)
    void documentProcessedForAnUnknownDocument_reachesTheDlqAfterRetries() throws Exception {
        String token = registerAndLogin("consumer2@acme.com");
        String workspaceId = createWorkspace(token, "Consumer Workspace 2");

        // A well-formed envelope for a document that doesn't exist in this
        // workspace — the consumer throws every time, so the error handler must
        // exhaust its retries and publish to document.processed.dlq.
        UUID eventId = UUID.randomUUID();
        String envelope =
                processedEnvelopeJson(eventId, UUID.fromString(workspaceId), UUID.randomUUID(), 1);
        kafkaTemplate.send(KafkaTopics.DOCUMENT_PROCESSED, workspaceId, envelope).get();

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.dlq(KafkaTopics.DOCUMENT_PROCESSED)));

            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(30));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            String dlqPayload = records.iterator().next().value();
            assertThat(dlqPayload).contains(eventId.toString());
        }
    }

    private static ConsumerRecords<String, String> pollUntilNotEmpty(
            KafkaConsumer<String, String> consumer, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            if (!records.isEmpty()) {
                return records;
            }
        }
        return ConsumerRecords.empty();
    }

    private String processedEnvelopeJson(UUID eventId, UUID workspaceId, UUID documentId, int chunkCount) {
        return """
                {
                  "event_id": "%s",
                  "event_type": "DOCUMENT_PROCESSED",
                  "schema_version": 1,
                  "occurred_at": "2026-08-10T00:00:00Z",
                  "workspace_id": "%s",
                  "correlation_id": "%s",
                  "causation_id": null,
                  "payload": {"document_id": "%s", "chunk_count": %d}
                }
                """
                .formatted(eventId, workspaceId, UUID.randomUUID(), documentId, chunkCount);
    }

    // ---- helpers ----

    private String registerAndLogin(String email) throws Exception {
        String body =
                """
                {"email": "%s", "name": "Consumer Tester", "password": "password12345"}
                """
                        .formatted(email);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("access_token").asString();
    }

    private String createWorkspace(String token, String name) throws Exception {
        String body = """
                {"name": "%s"}
                """.formatted(name);
        String response = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asString();
    }

    private String uploadDocument(String token, String workspaceId) throws Exception {
        MockMultipartFile filePart = new MockMultipartFile(
                "file", "policy.txt", "text/plain", "confidential policy text".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata",
                "metadata",
                MediaType.APPLICATION_JSON_VALUE,
                """
                {"name": "Security Policy", "document_type": "SECURITY_POLICY"}
                """
                        .getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.perform(multipart("/api/v1/workspaces/" + workspaceId + "/documents")
                        .file(filePart)
                        .file(metadataPart)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asString();
    }
}

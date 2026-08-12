package com.nexusiq.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.entity.DecisionRun;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the decision.* consumers end to end against Testcontainers Kafka
 * (roadmap Phase 5 acceptance criteria 1, 2, 5, 9): POST /decisions
 * publishes decision.requested; a simulated decision.completed (standing in
 * for the real AI service, which is Python-only infra not available in this
 * module's test context) is persisted into evidence/findings/decisions and
 * surfaces through GET /decisions/{id}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(
        statements =
                "TRUNCATE TABLE audit_events, processed_events, approvals, findings_evidence, findings, evidence, "
                        + "decisions, agent_executions, decision_runs, decision_requests, "
                        + "document_chunks, documents, workspace_members, workspaces, users CASCADE")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DecisionEventConsumersIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafkaContainer;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DecisionRunRepository decisionRunRepository;

    @Test
    @Order(1)
    void createDecision_publishesRequested_thenCompletedIsPersistedAndVisible() throws Exception {
        String token = registerAndLogin("decision1@acme.com");
        String workspaceId = createWorkspace(token, "Decision Workspace 1");

        String decisionId = createDecision(
                token, workspaceId, "Vendor Alpha approval", "Should Vendor Alpha be approved for EU production?");

        // decision.requested was published with the run's id as the Kafka key.
        try (KafkaConsumer<String, String> consumer = subscriberFor(KafkaTopics.DECISION_REQUESTED)) {
            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(20));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }

        UUID runId = await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> decisionRunRepository
                                .findFirstByDecisionRequestIdOrderByStartedAtDesc(UUID.fromString(decisionId))
                                .map(DecisionRun::getId),
                        java.util.Optional::isPresent)
                .orElseThrow();

        // evidence.document_id / .chunk_id are real FKs — a random UUID here
        // violates the constraint and the consumer silently DLQs (confirmed
        // empirically: a direct-call diagnostic with mocked repositories
        // succeeded on the identical payload, isolating the failure to the
        // real FK check against fabricated ids).
        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        UUID documentId = seeded[0];
        UUID chunkId = seeded[1];
        String completedEnvelope = completedEnvelopeJson(
                UUID.randomUUID(), UUID.fromString(workspaceId), runId, documentId, chunkId);
        kafkaTemplate.send(KafkaTopics.DECISION_COMPLETED, runId.toString(), completedEnvelope).get();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.outcome.recommendation").value("APPROVE"))
                // High confidence (0.85), LOW risk, no VIOLATED findings, evidence_coverage
                // 0.95 — every ApprovalGate trigger is clear, so this auto-finalises
                // (Phase 7 acceptance criterion 2) rather than landing in the queue.
                .andExpect(jsonPath("$.outcome.requires_human_approval").value(false))
                .andExpect(jsonPath("$.outcome.final_status").value("AUTO_APPROVED"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.findings[0].category").value("POLICY"))
                .andExpect(jsonPath("$.evidence[0].chunk_id").value(chunkId.toString()))
                .andExpect(jsonPath("$.run.status").value("COMPLETED")));
    }

    @Test
    @Order(2)
    void duplicateDecisionCompletedEvent_appliesExactlyOnce() throws Exception {
        String token = registerAndLogin("decision2@acme.com");
        String workspaceId = createWorkspace(token, "Decision Workspace 2");
        String decisionId = createDecision(token, workspaceId, "Title", "Question?");

        UUID runId = await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> decisionRunRepository
                                .findFirstByDecisionRequestIdOrderByStartedAtDesc(UUID.fromString(decisionId))
                                .map(DecisionRun::getId),
                        java.util.Optional::isPresent)
                .orElseThrow();

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        UUID eventId = UUID.randomUUID();
        String envelope =
                completedEnvelopeJson(eventId, UUID.fromString(workspaceId), runId, seeded[0], seeded[1]);

        kafkaTemplate.send(KafkaTopics.DECISION_COMPLETED, runId.toString(), envelope).get();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.run.status").value("COMPLETED")));

        // Redeliver the identical event_id.
        kafkaTemplate.send(KafkaTopics.DECISION_COMPLETED, runId.toString(), envelope).get();

        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + token))
                        .andExpect(jsonPath("$.findings.length()").value(1)));
    }

    @Test
    @Order(3)
    void decisionFailed_marksRunAndRequestFailed() throws Exception {
        String token = registerAndLogin("decision3@acme.com");
        String workspaceId = createWorkspace(token, "Decision Workspace 3");
        String decisionId = createDecision(token, workspaceId, "Title", "Question?");

        UUID runId = await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> decisionRunRepository
                                .findFirstByDecisionRequestIdOrderByStartedAtDesc(UUID.fromString(decisionId))
                                .map(DecisionRun::getId),
                        java.util.Optional::isPresent)
                .orElseThrow();

        String envelope =
                """
                {
                  "event_id": "%s",
                  "event_type": "DECISION_FAILED",
                  "schema_version": 1,
                  "occurred_at": "2026-08-11T00:00:00Z",
                  "workspace_id": "%s",
                  "correlation_id": "%s",
                  "causation_id": null,
                  "payload": {"decision_id": "%s", "reason": "simulated budget exceeded"}
                }
                """
                        .formatted(UUID.randomUUID(), workspaceId, UUID.randomUUID(), runId);
        kafkaTemplate.send(KafkaTopics.DECISION_FAILED, runId.toString(), envelope).get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                decisionRunRepository.findById(runId).orElseThrow().getStatus().name())
                        .isEqualTo("FAILED"));
    }

    /** evidence.document_id / .chunk_id are real FKs — decision.completed
     * payloads must reference an actual document + chunk, mirroring the
     * seed pattern DocumentChunksSchemaIT uses for the same tables. Java
     * never writes document_chunks in production (Python owns it); this
     * test writes directly only to satisfy the FK for a message that would
     * really originate from ai-service's real ingested chunks.
     *
     * <p>Wrapped in an explicit {@link TransactionTemplate}: unlike
     * {@code @DataJpaTest}, plain {@code @SpringBootTest} does not open a
     * transaction per test method, and a native {@code executeUpdate()}
     * outside one throws {@code TransactionRequiredException} — confirmed
     * empirically. */
    private UUID[] seedDocumentAndChunk(UUID workspaceId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> {
            UUID userId = (UUID) entityManager
                    .createNativeQuery("SELECT created_by FROM workspaces WHERE id = :workspaceId")
                    .setParameter("workspaceId", workspaceId)
                    .getSingleResult();

            UUID documentId = UUID.randomUUID();
            entityManager
                    .createNativeQuery(
                            """
                            INSERT INTO documents (id, workspace_id, name, document_type, status, uploaded_by)
                            VALUES (:id, :workspaceId, 'Data Residency Policy', 'SECURITY_POLICY', 'READY', :uploadedBy)
                            """)
                    .setParameter("id", documentId)
                    .setParameter("workspaceId", workspaceId)
                    .setParameter("uploadedBy", userId)
                    .executeUpdate();

            UUID chunkId = UUID.randomUUID();
            StringBuilder vector = new StringBuilder("[1");
            for (int i = 1; i < 384; i++) {
                vector.append(",0");
            }
            vector.append(']');
            entityManager
                    .createNativeQuery(
                            """
                            INSERT INTO document_chunks
                                (id, document_id, workspace_id, chunk_index, content, embedding,
                                 embedding_model, embedding_version)
                            VALUES (:id, :documentId, :workspaceId, 0, 'EU/EEA data residency is documented.',
                                    CAST(:embedding AS vector), 'BAAI/bge-small-en-v1.5', 1)
                            """)
                    .setParameter("id", chunkId)
                    .setParameter("documentId", documentId)
                    .setParameter("workspaceId", workspaceId)
                    .setParameter("embedding", vector.toString())
                    .executeUpdate();

            return new UUID[] {documentId, chunkId};
        });
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

    private KafkaConsumer<String, String> subscriberFor(String topic) {
        java.util.Map<String, Object> consumerProps = new java.util.HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "it-test-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private String completedEnvelopeJson(
            UUID eventId, UUID workspaceId, UUID runId, UUID documentId, UUID chunkId) {
        return """
                {
                  "event_id": "%s",
                  "event_type": "DECISION_COMPLETED",
                  "schema_version": 1,
                  "occurred_at": "2026-08-11T00:00:00Z",
                  "workspace_id": "%s",
                  "correlation_id": "%s",
                  "causation_id": null,
                  "payload": {
                    "decision_id": "%s",
                    "workflow_version": "v1",
                    "prompt_version": "v1",
                    "llm_model": "gemini-2.5-flash",
                    "embedding_model": "BAAI/bge-small-en-v1.5",
                    "recommendation": "APPROVE",
                    "reasoning_summary": "Evidence supports approval.",
                    "confidence": 0.85,
                    "risk_level": "LOW",
                    "evidence_coverage": 0.95,
                    "validation_passed": true,
                    "validation_escalated": false,
                    "required_actions": [],
                    "conditions": [],
                    "unresolved_questions": [],
                    "key_evidence_chunk_ids": ["%s"],
                    "evidence": [{
                      "document_id": "%s",
                      "chunk_id": "%s",
                      "evidence_text": "EU/EEA data residency is documented.",
                      "relevance_score": 0.91,
                      "citation_reference": "SP-102 §1"
                    }],
                    "findings": [{
                      "category": "POLICY",
                      "policy_name": "Data Residency Policy",
                      "status": "SATISFIED",
                      "severity": null,
                      "title": "Data Residency Policy (SP-102 §1)",
                      "description": "EU/EEA storage requirement is documented.",
                      "confidence": 0.9,
                      "evidence_chunk_ids": ["%s"]
                    }],
                    "escalation_reasons": [],
                    "total_input_tokens": 500,
                    "total_output_tokens": 300,
                    "estimated_cost_usd": 0.002,
                    "latency_ms": 4200
                  }
                }
                """
                .formatted(
                        eventId,
                        workspaceId,
                        UUID.randomUUID(),
                        runId,
                        chunkId,
                        documentId,
                        chunkId,
                        chunkId);
    }

    // ---- helpers ----

    private String registerAndLogin(String email) throws Exception {
        String body =
                """
                {"email": "%s", "name": "Decision Tester", "password": "password12345"}
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

    private String createDecision(String token, String workspaceId, String title, String question)
            throws Exception {
        String body =
                """
                {"title": "%s", "question": "%s", "priority": "NORMAL"}
                """
                        .formatted(title, question);
        String response = mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asString();
    }
}

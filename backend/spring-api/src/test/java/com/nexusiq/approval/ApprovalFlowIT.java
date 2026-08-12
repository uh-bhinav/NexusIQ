package com.nexusiq.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusiq.TestcontainersConfiguration;
import com.nexusiq.decision.DecisionRunRepository;
import com.nexusiq.decision.entity.DecisionRun;
import com.nexusiq.messaging.KafkaTopics;
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
import org.junit.jupiter.api.Test;
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
 * Phase 7 (ADR-006) end to end against Testcontainers Kafka/Postgres: a
 * simulated decision.completed stands in for the real AI service (Python-only
 * infra, not available in this module's test context — same convention as
 * DecisionEventConsumersIT), exercising ApprovalGate, the approval queue, and
 * separation of duties through the real HTTP API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(
        statements =
                "TRUNCATE TABLE audit_events, processed_events, approvals, findings_evidence, findings, evidence, "
                        + "decisions, agent_executions, decision_runs, decision_requests, "
                        + "document_chunks, documents, workspace_members, workspaces, users CASCADE")
class ApprovalFlowIT {

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
    void lowConfidenceDecision_landsInQueue_withReasonsStated() throws Exception {
        String requesterToken = registerAndLogin("requester1@acme.com");
        String workspaceId = createWorkspace(requesterToken, "Approval WS 1");
        String decisionId = createDecision(requesterToken, workspaceId, "Title", "Question?");
        UUID runId = awaitRun(decisionId);

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        publishCompleted(workspaceId, runId, seeded[0], seeded[1], "0.50", "LOW", false);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.outcome.requires_human_approval").value(true))
                .andExpect(jsonPath("$.outcome.final_status").value("PENDING")));

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/approvals?status=PENDING")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].decision_run_id").value(runId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].reasons[0]").exists());
    }

    @Test
    void requesterCannotApproveOwnDecision() throws Exception {
        String requesterToken = registerAndLogin("requester2@acme.com");
        String workspaceId = createWorkspace(requesterToken, "Approval WS 2");
        String decisionId = createDecision(requesterToken, workspaceId, "Title", "Question?");
        UUID runId = awaitRun(decisionId);

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        publishCompleted(workspaceId, runId, seeded[0], seeded[1], "0.50", "LOW", false);

        UUID approvalId = awaitApprovalId(workspaceId, requesterToken, runId);

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/approvals/" + approvalId + "/approve")
                        .header("Authorization", "Bearer " + requesterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotActOnQueue() throws Exception {
        String requesterToken = registerAndLogin("requester3@acme.com");
        String workspaceId = createWorkspace(requesterToken, "Approval WS 3");
        String decisionId = createDecision(requesterToken, workspaceId, "Title", "Question?");
        UUID runId = awaitRun(decisionId);

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        publishCompleted(workspaceId, runId, seeded[0], seeded[1], "0.50", "LOW", false);
        UUID approvalId = awaitApprovalId(workspaceId, requesterToken, runId);

        String viewerToken = registerAndLogin("viewer3@acme.com");
        addMember(requesterToken, workspaceId, "viewer3@acme.com", "VIEWER");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/approvals/" + approvalId + "/approve")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approverApproves_finalizesDecision_publishesApprovalCompleted() throws Exception {
        String requesterToken = registerAndLogin("requester4@acme.com");
        String workspaceId = createWorkspace(requesterToken, "Approval WS 4");
        String decisionId = createDecision(requesterToken, workspaceId, "Title", "Question?");
        UUID runId = awaitRun(decisionId);

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        publishCompleted(workspaceId, runId, seeded[0], seeded[1], "0.50", "LOW", false);
        UUID approvalId = awaitApprovalId(workspaceId, requesterToken, runId);

        String approverToken = registerAndLogin("approver4@acme.com");
        addMember(requesterToken, workspaceId, "approver4@acme.com", "APPROVER");

        try (KafkaConsumer<String, String> consumer = subscriberFor(KafkaTopics.APPROVAL_COMPLETED)) {
            mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/approvals/" + approvalId + "/approve")
                            .header("Authorization", "Bearer " + approverToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"notes\":\"looks fine\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            ConsumerRecords<String, String> records = pollUntilNotEmpty(consumer, Duration.ofSeconds(20));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.outcome.final_status").value("HUMAN_APPROVED")));

        // A second approve on the now-resolved approval is a conflict, not a
        // silent no-op or a double-resume trigger (roadmap acceptance
        // criterion 8's Java-side half — Python's own idempotency on
        // approval.completed's event_id covers the Kafka-redelivery half).
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/approvals/" + approvalId + "/approve")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void approverRejects_recordsReasonAndFinalizes() throws Exception {
        String requesterToken = registerAndLogin("requester5@acme.com");
        String workspaceId = createWorkspace(requesterToken, "Approval WS 5");
        String decisionId = createDecision(requesterToken, workspaceId, "Title", "Question?");
        UUID runId = awaitRun(decisionId);

        UUID[] seeded = seedDocumentAndChunk(UUID.fromString(workspaceId));
        publishCompleted(workspaceId, runId, seeded[0], seeded[1], "0.50", "LOW", false);
        UUID approvalId = awaitApprovalId(workspaceId, requesterToken, runId);

        String approverToken = registerAndLogin("approver5@acme.com");
        addMember(requesterToken, workspaceId, "approver5@acme.com", "APPROVER");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/approvals/" + approvalId + "/reject")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not enough evidence for EU residency\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolution_notes").value("Not enough evidence for EU residency"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/decisions/" + decisionId)
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.outcome.final_status").value("HUMAN_REJECTED")));
    }

    private UUID awaitRun(String decisionId) {
        return await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> decisionRunRepository
                                .findFirstByDecisionRequestIdOrderByStartedAtDesc(UUID.fromString(decisionId))
                                .map(DecisionRun::getId),
                        java.util.Optional::isPresent)
                .orElseThrow();
    }

    private UUID awaitApprovalId(String workspaceId, String token, UUID runId) {
        return await()
                .atMost(Duration.ofSeconds(20))
                .until(
                        () -> {
                            String response = mockMvc.perform(get(
                                            "/api/v1/workspaces/" + workspaceId + "/approvals?status=PENDING")
                                            .header("Authorization", "Bearer " + token))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString();
                            JsonNode content = objectMapper.readTree(response).get("content");
                            for (JsonNode node : content) {
                                if (node.get("decision_run_id").asString().equals(runId.toString())) {
                                    return java.util.Optional.of(UUID.fromString(node.get("id").asString()));
                                }
                            }
                            return java.util.Optional.<UUID>empty();
                        },
                        java.util.Optional::isPresent)
                .orElseThrow();
    }

    private void publishCompleted(
            String workspaceId,
            UUID runId,
            UUID documentId,
            UUID chunkId,
            String confidence,
            String riskLevel,
            boolean violated)
            throws Exception {
        String status = violated ? "VIOLATED" : "SATISFIED";
        String envelope =
                """
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
                    "confidence": %s,
                    "risk_level": "%s",
                    "evidence_coverage": 0.90,
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
                      "status": "%s",
                      "severity": null,
                      "title": "Data Residency Policy (SP-102 §1)",
                      "description": "EU/EEA storage requirement.",
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
                                UUID.randomUUID(),
                                workspaceId,
                                UUID.randomUUID(),
                                runId,
                                confidence,
                                riskLevel,
                                chunkId,
                                documentId,
                                chunkId,
                                status,
                                chunkId);
        kafkaTemplate.send(KafkaTopics.DECISION_COMPLETED, runId.toString(), envelope).get();
    }

    private String registerAndLogin(String email) throws Exception {
        String body =
                """
                {"email": "%s", "name": "Approval Tester", "password": "password12345"}
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
        return objectMapper.readTree(response).get("id").asString();
    }

    private void addMember(String adminToken, String workspaceId, String email, String role) throws Exception {
        String body = """
                {"email": "%s", "role": "%s"}
                """.formatted(email, role);
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** evidence.document_id / .chunk_id are real FKs — see
     * DecisionEventConsumersIT's identical helper for the full rationale. */
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
}

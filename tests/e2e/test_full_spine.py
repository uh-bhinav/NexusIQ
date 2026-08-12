"""The one E2E test for this project (docs/TESTING/STRATEGY.md: "Few E2E
tests... one that covers the full spine is worth more than twenty shallow
ones"). Proves the entire spec: a document uploaded through spring-api is
ingested by ai-service, a decision request is picked up off Kafka, run
through the full LangGraph agent workflow, escalates to a human via the
deterministic ApprovalGate, is approved, and the whole sequence is recorded
in the append-only audit trail — end to end, across all three data stores
and both services, with no mocking of anything this test itself controls.

PRECONDITIONS (this suite does not start these itself — spinning up a JVM
process and a Python/uvicorn process from inside a pytest fixture is slow and
fragile compared to the Testcontainers-managed hermetic suites in
backend/spring-api and ai-service; see conftest.py's `_require_live_stack`,
which skips with an explicit message rather than failing confusingly if
these aren't met):

1. The shared docker-compose stack is up (`make up` from the repo root).
2. spring-api is running on port 8180 against that stack (see
   docs/OPERATIONS/LOCAL_DEV.md's "E2E testing" section for the exact
   command — the short version is `API_PORT=8180 ./mvnw spring-boot:run`
   with the usual host-execution POSTGRES_HOST/KAFKA_BOOTSTRAP_SERVERS/
   REDIS_HOST overrides documented there).
3. ai-service is running on port 8000 against the same stack, with
   LLM_PROVIDER=mock and MOCK_FIXTURES_DIR pointed at
   ai-service/tests/fixtures/llm_e2e_escalate (NOT the default
   tests/fixtures/llm/ — that fixture set is rigged to always cleanly
   APPROVE, which would never exercise the escalate/approve branch this
   test exists to prove; llm_e2e_escalate's RiskAssessment.json returns
   risk_level=HIGH specifically so ApprovalGate's risk>=threshold trigger
   fires deterministically, independent of whatever the uploaded document
   actually says).

Override E2E_API_BASE_URL / E2E_AI_SERVICE_HEALTH_URL (conftest.py) if your
local ports differ from the documented defaults.
"""

import asyncio
import time
from pathlib import Path

import httpx
import pytest

_SAMPLE_DOC = (
    Path(__file__).resolve().parents[2] / "docs" / "sample-enterprise" / "security" / "security-policy-v2.md"
)


async def _poll_until(
    client: httpx.AsyncClient,
    url: str,
    headers: dict[str, str],
    *,
    field: str,
    terminal_values: set[str],
    max_attempts: int = 40,
    interval_seconds: float = 3.0,
) -> dict:
    for attempt in range(1, max_attempts + 1):
        resp = await client.get(url, headers=headers)
        resp.raise_for_status()
        body = resp.json()
        if body[field] in terminal_values:
            return body
        await asyncio.sleep(interval_seconds)
    raise AssertionError(
        f"{url} never reached one of {terminal_values} on field {field!r} after "
        f"{max_attempts} attempts (last value: {body.get(field)!r})"
    )


@pytest.mark.asyncio
async def test_full_spine_upload_ingest_decide_validate_escalate_approve_audit(api_base_url: str) -> None:
    unique = str(int(time.time() * 1000))

    async with httpx.AsyncClient(base_url=api_base_url, timeout=30.0) as client:
        # --- 1. Two users: a requester (becomes workspace ADMIN on creation)
        # and a separate approver. Two distinct people are required, not one
        # wearing two hats: .claude/rules/backend-java.md's separation-of-
        # duties rule forbids a user approving a decision they requested. ---
        requester_resp = await client.post(
            "/auth/register",
            json={
                "email": f"e2e-requester-{unique}@example.com",
                "name": "E2E Requester",
                "password": "password123",
            },
        )
        assert requester_resp.status_code == 201, requester_resp.text
        requester = requester_resp.json()
        requester_headers = {"Authorization": f"Bearer {requester['access_token']}"}

        approver_email = f"e2e-approver-{unique}@example.com"
        approver_resp = await client.post(
            "/auth/register",
            json={"email": approver_email, "name": "E2E Approver", "password": "password123"},
        )
        assert approver_resp.status_code == 201, approver_resp.text
        approver = approver_resp.json()
        approver_headers = {"Authorization": f"Bearer {approver['access_token']}"}

        # --- 2. Workspace + membership ---
        ws_resp = await client.post(
            "/workspaces",
            headers=requester_headers,
            json={"name": f"E2E Workspace {unique}", "description": "Full-spine E2E test"},
        )
        assert ws_resp.status_code == 201, ws_resp.text
        workspace_id = ws_resp.json()["id"]

        member_resp = await client.post(
            f"/workspaces/{workspace_id}/members",
            headers=requester_headers,
            json={"email": approver_email, "role": "APPROVER"},
        )
        assert member_resp.status_code == 200, member_resp.text

        # --- 3. Upload -> ingest. Real document, real chunking, real
        # embedding (no LLM involved yet) — proves the ingestion pipeline,
        # not just the decision workflow. ---
        assert _SAMPLE_DOC.exists(), f"sample document missing: {_SAMPLE_DOC}"
        upload_resp = await client.post(
            f"/workspaces/{workspace_id}/documents",
            headers=requester_headers,
            files={
                "file": (_SAMPLE_DOC.name, _SAMPLE_DOC.read_bytes(), "text/markdown"),
                # Spring's @RequestPart binds this JSON part via its message
                # converter, which is selected by Content-Type — a plain form
                # field (httpx `data=`) arrives without one and fails to bind.
                "metadata": (
                    None,
                    '{"name": "Security Policy v2", "document_type": "SECURITY_POLICY"}',
                    "application/json",
                ),
            },
        )
        assert upload_resp.status_code == 202, upload_resp.text
        document_id = upload_resp.json()["id"]

        document = await _poll_until(
            client,
            f"/workspaces/{workspace_id}/documents/{document_id}",
            requester_headers,
            field="status",
            terminal_values={"READY", "FAILED"},
        )
        assert document["status"] == "READY", document
        assert document["chunk_count"] > 0, "ingestion produced zero chunks"

        # --- 4. Decide -> validate. A real decision request, consumed off
        # Kafka by ai-service, run through the full seven-node LangGraph
        # workflow (intent -> context_planner -> retrieval -> policy_analyst
        # || risk_analyzer -> decision -> validator -> approval_router)
        # against the document just ingested. ---
        decision_resp = await client.post(
            f"/workspaces/{workspace_id}/decisions",
            headers=requester_headers,
            json={
                "title": "Vendor Alpha EU production approval (E2E)",
                "question": "Should Vendor Alpha be approved for EU production?",
                "priority": "NORMAL",
            },
        )
        assert decision_resp.status_code == 202, decision_resp.text
        decision_id = decision_resp.json()["id"]

        decision = await _poll_until(
            client,
            f"/workspaces/{workspace_id}/decisions/{decision_id}",
            requester_headers,
            field="status",
            terminal_values={"WAITING_FOR_APPROVAL", "APPROVED", "REJECTED", "FAILED"},
        )
        assert decision["status"] == "WAITING_FOR_APPROVAL", (
            "expected the decision to escalate to a human (ApprovalGate's risk>=threshold "
            "trigger) — got "
            f"{decision['status']!r} instead. This test's module docstring documents a "
            "required precondition: ai-service must be started with "
            "MOCK_FIXTURES_DIR=ai-service/tests/fixtures/llm_e2e_escalate, not the default "
            "fixture set (which always cleanly APPROVEs and would never reach this branch). "
            f"Full response: {decision}"
        )
        run = decision["run"]
        assert run["status"] == "COMPLETED"
        assert decision["outcome"]["requires_human_approval"] is True
        assert "risk_level=HIGH" in decision["outcome"]["escalation_reasons"][0]
        assert decision["findings"], "validator ran with zero findings"
        assert decision["evidence"], "recommendation carries no evidence citations"

        # --- 5. Escalate -> approve, by the separate approver (not the
        # requester — enforced server-side, see step 1's comment). ---
        approvals_resp = await client.get(f"/workspaces/{workspace_id}/approvals", headers=approver_headers)
        assert approvals_resp.status_code == 200, approvals_resp.text
        pending = [a for a in approvals_resp.json()["content"] if a["decision_request_id"] == decision_id]
        assert len(pending) == 1, f"expected exactly one pending approval for this decision, got {pending}"
        approval_id = pending[0]["id"]

        approve_resp = await client.post(
            f"/workspaces/{workspace_id}/approvals/{approval_id}/approve",
            headers=approver_headers,
            json={"notes": "E2E: risk reviewed and accepted."},
        )
        assert approve_resp.status_code == 200, approve_resp.text
        assert approve_resp.json()["status"] == "APPROVED"

        decision = await _poll_until(
            client,
            f"/workspaces/{workspace_id}/decisions/{decision_id}",
            requester_headers,
            field="status",
            terminal_values={"APPROVED", "REJECTED", "FAILED"},
        )
        assert decision["status"] == "APPROVED", decision
        assert decision["outcome"]["final_status"] == "HUMAN_APPROVED"

        # --- 6. Audit: every security-relevant step of the spine above must
        # have left an append-only trail (.claude/rules/security.md
        # "Auditability"). ---
        audit_resp = await client.get(
            "/audit", headers=requester_headers, params={"workspaceId": workspace_id, "size": 50}
        )
        assert audit_resp.status_code == 200, audit_resp.text
        event_types = {e["event_type"] for e in audit_resp.json()["content"]}
        for expected in {
            "WORKSPACE_CREATED",
            "WORKSPACE_MEMBER_ADDED",
            "DOCUMENT_UPLOADED",
            "DOCUMENT_READY",
            "DECISION_REQUESTED",
            "APPROVAL_GRANTED",
        }:
            assert expected in event_types, f"missing audit event {expected!r}; got {event_types}"

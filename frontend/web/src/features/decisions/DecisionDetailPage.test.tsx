import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { Routes, Route } from 'react-router-dom'
import { server } from '@/test/msw-server'
import { renderWithProviders } from '@/test/render'
import { DecisionDetailPage } from '@/features/decisions/DecisionDetailPage'

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/w/:workspaceId/decisions/:decisionId" element={<DecisionDetailPage />} />
    </Routes>,
    { route: '/w/ws1/decisions/d1' },
  )
}

function mockAudit(events: unknown[] = []) {
  server.use(
    http.get('/api/v1/audit/resource/decision/d1', () =>
      HttpResponse.json({ content: events, page: 0, size: 20, total_elements: events.length, total_pages: 1 }),
    ),
    http.post('/api/v1/workspaces/ws1/decisions/d1/stream-token', () =>
      HttpResponse.json({ token: 'fake-stream-token' }),
    ),
  )
}

const populatedDecision = {
  id: 'd1',
  title: 'Vendor Alpha approval',
  question: 'Should Vendor Alpha be approved for EU production?',
  priority: 'HIGH',
  status: 'WAITING_FOR_APPROVAL',
  created_at: new Date().toISOString(),
  run: {
    id: 'r1',
    workflow_version: 'v1',
    prompt_version: 'v1',
    llm_model: 'gemini-2.5-flash',
    embedding_model: 'BAAI/bge-small-en-v1.5',
    status: 'COMPLETED',
    confidence: 0.82,
    total_input_tokens: 1200,
    total_output_tokens: 400,
    estimated_cost_usd: 0.0021,
    latency_ms: 7500,
    failure_reason: null,
    started_at: new Date().toISOString(),
    completed_at: new Date().toISOString(),
  },
  agent_executions: [
    {
      id: 'ae1',
      agent_name: 'policy_analyst',
      sequence_index: 3,
      status: 'SUCCESS',
      model: 'gemini-2.5-flash',
      input_tokens: 800,
      output_tokens: 200,
      latency_ms: 2000,
      estimated_cost_usd: 0.0012,
      error: null,
      started_at: new Date().toISOString(),
      completed_at: new Date().toISOString(),
    },
  ],
  evidence: [
    {
      id: 'e1',
      document_id: 'doc1',
      chunk_id: 'c1',
      evidence_text: 'EU/EEA data residency is documented.',
      relevance_score: 0.91,
      citation_reference: 'SP-102 §1',
    },
  ],
  findings: [
    {
      id: 'f1',
      category: 'POLICY',
      policy_name: 'Data Residency Policy',
      status: 'SATISFIED',
      severity: null,
      title: 'Data Residency Policy (SP-102 §1)',
      description: 'EU/EEA storage requirement is met.',
      confidence: 0.9,
      evidence_ids: ['e1'],
    },
  ],
  outcome: {
    recommendation: 'APPROVE',
    reasoning_summary: 'Data residency policy requirements are satisfied and risk is low.',
    confidence: 0.82,
    risk_level: 'LOW',
    evidence_coverage: 0.9,
    validation_passed: true,
    requires_human_approval: true,
    escalation_reasons: ['confidence below threshold'],
    required_actions: [],
    conditions: [],
    unresolved_questions: [],
    final_status: 'PENDING',
  },
}

describe('DecisionDetailPage', () => {
  it('renders populated state: recommendation, findings, evidence, agent timeline, run totals', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions/d1', () => HttpResponse.json(populatedDecision)),
    )
    mockAudit([
      {
        id: 'a1',
        workspace_id: 'ws1',
        actor_id: 'u1',
        event_type: 'DECISION_REQUESTED',
        resource_type: 'decision',
        resource_id: 'd1',
        correlation_id: 'corr-1',
        metadata: null,
        occurred_at: new Date().toISOString(),
      },
    ])
    renderPage()

    expect(await screen.findByText('Vendor Alpha approval')).toBeInTheDocument()
    expect(screen.getByText('APPROVE')).toBeInTheDocument()
    expect(screen.getByText(/confidence 0.82/)).toBeInTheDocument()
    expect(screen.getByText('Data Residency Policy (SP-102 §1)')).toBeInTheDocument()
    expect(screen.getByText('SP-102 §1')).toBeInTheDocument()
    expect(screen.getByText('policy_analyst')).toBeInTheDocument()
    expect(screen.getByText('$0.0021')).toBeInTheDocument()
    expect(screen.getByText('DECISION_REQUESTED')).toBeInTheDocument()
    expect(screen.getByText(/Escalated for human approval/)).toBeInTheDocument()
  })

  it('renders an empty state for a decision with no agent executions or audit events yet', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions/d1', () =>
        HttpResponse.json({
          id: 'd1',
          title: 'Fresh decision',
          question: 'Is this vendor compliant?',
          priority: 'NORMAL',
          status: 'PENDING',
          created_at: new Date().toISOString(),
          run: null,
          agent_executions: [],
          evidence: [],
          findings: [],
          outcome: null,
        }),
      ),
    )
    mockAudit([])
    renderPage()

    expect(await screen.findByText('Fresh decision')).toBeInTheDocument()
    expect(screen.getByText('No agent has completed yet.')).toBeInTheDocument()
    expect(screen.getByText('No audit events for this decision yet')).toBeInTheDocument()
  })

  it('renders an error state when the decision fails to load', async () => {
    server.use(
      http.get('/api/v1/workspaces/ws1/decisions/d1', () =>
        HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 404,
            error: 'NOT_FOUND',
            message: 'Decision not found',
            path: '/api/v1/workspaces/ws1/decisions/d1',
          },
          { status: 404 },
        ),
      ),
    )
    mockAudit([])
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/decision not found/i)
  })
})

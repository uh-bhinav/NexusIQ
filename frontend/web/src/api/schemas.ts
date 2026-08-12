import { z } from 'zod'

// Mirrors backend enums exactly (backend/spring-api/.../entity/*.java) — a
// value the backend can emit that isn't listed here should fail loudly via
// Zod, not fall through as an unlabelled string.
export const Role = z.enum(['ADMIN', 'ANALYST', 'APPROVER', 'VIEWER'])
export type Role = z.infer<typeof Role>

export const DecisionPriority = z.enum(['LOW', 'NORMAL', 'HIGH', 'URGENT'])
export type DecisionPriority = z.infer<typeof DecisionPriority>
export const DecisionRequestStatus = z.enum([
  'PENDING',
  'PROCESSING',
  'WAITING_FOR_APPROVAL',
  'APPROVED',
  'REJECTED',
  'FAILED',
])
export const FinalStatus = z.enum(['PENDING', 'AUTO_APPROVED', 'HUMAN_APPROVED', 'HUMAN_REJECTED'])
export const RecommendationType = z.enum([
  'APPROVE',
  'CONDITIONAL_APPROVAL',
  'REJECT',
  'INSUFFICIENT_INFORMATION',
  'CONFLICTING_EVIDENCE',
])
export const RiskLevel = z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
export const AgentExecutionStatus = z.enum(['SUCCESS', 'FAILED', 'SKIPPED', 'RETRIED'])
export const FindingCategory = z.enum(['POLICY', 'RISK', 'GAP', 'CONFLICT', 'PROMPT_INJECTION_ATTEMPT'])
export const FindingStatus = z.enum(['SATISFIED', 'PARTIALLY_SATISFIED', 'VIOLATED', 'UNKNOWN'])
export const FindingSeverity = z.enum(['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
export const DecisionRunStatus = z.enum(['QUEUED', 'RUNNING', 'SUSPENDED', 'COMPLETED', 'FAILED'])
export const ApprovalStatus = z.enum(['PENDING', 'APPROVED', 'REJECTED'])
export type ApprovalStatus = z.infer<typeof ApprovalStatus>
export const DocumentStatus = z.enum(['UPLOADED', 'PROCESSING', 'READY', 'FAILED'])
export const DocumentType = z.enum([
  'SECURITY_POLICY',
  'COMPLIANCE_POLICY',
  'PROCUREMENT_POLICY',
  'ARCHITECTURE_STANDARD',
  'VENDOR_DOCUMENT',
  'HISTORICAL_DECISION',
  'INCIDENT_REPORT',
  'OTHER',
])
export type DocumentType = z.infer<typeof DocumentType>

// The backend's global Jackson config (`ALL_NON_NULL`) omits null fields from
// every JSON response rather than sending them as literal `null` — so a
// nullable field must accept a *missing* key, not just `null`. `.nullish()`
// (not `.nullish()`) is required everywhere below for that reason.
function page<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    content: z.array(item),
    page: z.number(),
    size: z.number(),
    total_elements: z.number(),
    total_pages: z.number(),
  })
}

// ---- Auth / users / workspaces ----

export const UserSummary = z.object({
  id: z.string(),
  email: z.string(),
  name: z.string(),
  role: Role,
})
export type UserSummary = z.infer<typeof UserSummary>

export const WorkspaceSummary = z.object({
  id: z.string(),
  name: z.string(),
  slug: z.string(),
  role: Role,
})
export type WorkspaceSummary = z.infer<typeof WorkspaceSummary>

export const AuthResponse = z.object({
  access_token: z.string(),
  refresh_token: z.string(),
  expires_in: z.number(),
  user: UserSummary,
})
export type AuthResponse = z.infer<typeof AuthResponse>

export const MeResponse = z.object({
  user: UserSummary,
  workspaces: z.array(WorkspaceSummary),
})
export type MeResponse = z.infer<typeof MeResponse>

export const Workspace = z.object({
  id: z.string(),
  name: z.string(),
  slug: z.string(),
  description: z.string().nullish(),
  created_by: z.string(),
  created_at: z.string(),
  updated_at: z.string(),
})
export type Workspace = z.infer<typeof Workspace>
export const WorkspacePage = page(Workspace)

export const Member = z.object({
  user_id: z.string(),
  email: z.string(),
  name: z.string(),
  role: Role,
  joined_at: z.string(),
})
export type Member = z.infer<typeof Member>

// ---- Documents ----

export const DocumentSummary = z.object({
  id: z.string(),
  workspace_id: z.string(),
  name: z.string(),
  document_type: DocumentType,
  version: z.number(),
  is_current: z.boolean(),
  status: DocumentStatus,
  failure_reason: z.string().nullish(),
  chunk_count: z.number(),
  uploaded_by: z.string(),
  created_at: z.string(),
  updated_at: z.string(),
})
export type DocumentSummary = z.infer<typeof DocumentSummary>
export const DocumentPage = page(DocumentSummary)

export const Chunk = z.object({
  id: z.string(),
  document_id: z.string(),
  chunk_index: z.number(),
  content: z.string(),
  section: z.string().nullish(),
  subsection: z.string().nullish(),
  page_number: z.number().nullish(),
  is_flagged: z.boolean(),
})
export type Chunk = z.infer<typeof Chunk>
export const ChunkPage = page(Chunk)

// ---- Knowledge search ----

export const SearchResult = z.object({
  chunk_id: z.string(),
  document_id: z.string(),
  document_name: z.string(),
  document_type: z.string(),
  document_version: z.number(),
  is_current: z.boolean(),
  section: z.string().nullish(),
  subsection: z.string().nullish(),
  page_number: z.number().nullish(),
  content: z.string(),
  similarity_score: z.number(),
  rerank_score: z.number().nullish(),
  trust_level: z.string(),
  is_flagged: z.boolean(),
  citation_reference: z.string(),
})
export type SearchResult = z.infer<typeof SearchResult>

export const KnowledgeSearchResponse = z.object({
  results: z.array(SearchResult),
  query: z.string(),
  cached: z.boolean(),
  latency_ms: z.number(),
})
export type KnowledgeSearchResponse = z.infer<typeof KnowledgeSearchResponse>

// ---- Decisions ----

export const DecisionSummary = z.object({
  id: z.string(),
  title: z.string(),
  question: z.string(),
  priority: DecisionPriority,
  status: DecisionRequestStatus,
  created_at: z.string(),
})
export type DecisionSummary = z.infer<typeof DecisionSummary>
export const DecisionPage = page(DecisionSummary)

export const DecisionRun = z.object({
  id: z.string(),
  workflow_version: z.string(),
  prompt_version: z.string(),
  llm_model: z.string().nullish(),
  embedding_model: z.string().nullish(),
  status: DecisionRunStatus,
  confidence: z.number().nullish(),
  total_input_tokens: z.number(),
  total_output_tokens: z.number(),
  estimated_cost_usd: z.number().nullish(),
  latency_ms: z.number().nullish(),
  failure_reason: z.string().nullish(),
  started_at: z.string().nullish(),
  completed_at: z.string().nullish(),
})
export type DecisionRun = z.infer<typeof DecisionRun>

export const AgentExecution = z.object({
  id: z.string(),
  agent_name: z.string(),
  sequence_index: z.number(),
  status: AgentExecutionStatus,
  model: z.string().nullish(),
  input_tokens: z.number(),
  output_tokens: z.number(),
  latency_ms: z.number(),
  estimated_cost_usd: z.number().nullish(),
  error: z.string().nullish(),
  started_at: z.string().nullish(),
  completed_at: z.string().nullish(),
})
export type AgentExecution = z.infer<typeof AgentExecution>

export const Evidence = z.object({
  id: z.string(),
  document_id: z.string(),
  chunk_id: z.string(),
  evidence_text: z.string(),
  relevance_score: z.number().nullish(),
  citation_reference: z.string(),
})
export type Evidence = z.infer<typeof Evidence>

export const Finding = z.object({
  id: z.string(),
  category: FindingCategory,
  policy_name: z.string().nullish(),
  status: FindingStatus.nullish(),
  severity: FindingSeverity.nullish(),
  title: z.string(),
  description: z.string(),
  confidence: z.number().nullish(),
  evidence_ids: z.array(z.string()),
})
export type Finding = z.infer<typeof Finding>

export const DecisionOutcome = z.object({
  recommendation: RecommendationType,
  reasoning_summary: z.string(),
  confidence: z.number().nullish(),
  risk_level: RiskLevel,
  evidence_coverage: z.number().nullish(),
  validation_passed: z.boolean().nullish(),
  requires_human_approval: z.boolean(),
  escalation_reasons: z.array(z.string()),
  required_actions: z.array(z.string()),
  conditions: z.array(z.string()),
  unresolved_questions: z.array(z.string()),
  final_status: FinalStatus,
})
export type DecisionOutcome = z.infer<typeof DecisionOutcome>

export const DecisionDetail = z.object({
  id: z.string(),
  title: z.string(),
  question: z.string(),
  priority: DecisionPriority,
  status: DecisionRequestStatus,
  created_at: z.string(),
  run: DecisionRun.nullish(),
  agent_executions: z.array(AgentExecution),
  evidence: z.array(Evidence),
  findings: z.array(Finding),
  outcome: DecisionOutcome.nullish(),
})
export type DecisionDetail = z.infer<typeof DecisionDetail>

// ---- Approvals ----

export const Approval = z.object({
  id: z.string(),
  decision_run_id: z.string(),
  decision_request_id: z.string(),
  decision_title: z.string(),
  status: ApprovalStatus,
  reasons: z.array(z.string()),
  requested_at: z.string(),
  resolved_by: z.string().nullish(),
  resolved_at: z.string().nullish(),
  resolution_notes: z.string().nullish(),
})
export type Approval = z.infer<typeof Approval>
export const ApprovalPage = page(Approval)

// ---- Audit ----

export const AuditEvent = z.object({
  id: z.string(),
  workspace_id: z.string().nullish(),
  actor_id: z.string().nullish(),
  event_type: z.string(),
  resource_type: z.string(),
  resource_id: z.string().nullish(),
  correlation_id: z.string().nullish(),
  metadata: z.string().nullish(),
  occurred_at: z.string(),
})
export type AuditEvent = z.infer<typeof AuditEvent>
export const AuditEventPage = page(AuditEvent)

// ---- Metrics ----

export const MetricsSummary = z.object({
  total_decisions: z.number(),
  decisions_by_status: z.record(z.string(), z.number()),
  decisions_by_recommendation: z.record(z.string(), z.number()),
  pending_approvals: z.number(),
  avg_confidence: z.number().nullish(),
  avg_cost_usd: z.number().nullish(),
  avg_latency_ms: z.number().nullish(),
})
export type MetricsSummary = z.infer<typeof MetricsSummary>

// ---- Errors ----

export const ApiError = z.object({
  timestamp: z.string(),
  status: z.number(),
  error: z.string(),
  message: z.string(),
  path: z.string(),
  request_id: z.string().nullish(),
  details: z
    .array(z.object({ field: z.string().optional(), issue: z.string().optional() }))
    .optional(),
})
export type ApiError = z.infer<typeof ApiError>

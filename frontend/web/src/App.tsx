import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { AuthProvider } from '@/features/auth/auth-context'
import { RequireAuth } from '@/components/require-auth'
import { AppLayout } from '@/components/app-layout'
import { LoginPage } from '@/features/auth/LoginPage'
import { WorkspaceLandingPage } from '@/features/workspaces/WorkspaceLandingPage'
import { DashboardPage } from '@/features/dashboard/DashboardPage'
import { KnowledgeBasePage } from '@/features/knowledge/KnowledgeBasePage'
import { DecisionRequestsPage } from '@/features/decisions/DecisionRequestsPage'
import { DecisionDetailPage } from '@/features/decisions/DecisionDetailPage'
import { ApprovalQueuePage } from '@/features/approvals/ApprovalQueuePage'
import { AuditLogPage } from '@/features/audit/AuditLogPage'
import { SystemMetricsPage } from '@/features/metrics/SystemMetricsPage'
import { DocumentDetailPage } from '@/features/documents/DocumentDetailPage'

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<RequireAuth />}>
            <Route path="/" element={<WorkspaceLandingPage />} />
            <Route path="/w/:workspaceId" element={<AppLayout />}>
              <Route index element={<DashboardPage />} />
              <Route path="knowledge" element={<KnowledgeBasePage />} />
              <Route path="decisions" element={<DecisionRequestsPage />} />
              <Route path="decisions/:decisionId" element={<DecisionDetailPage />} />
              <Route path="approvals" element={<ApprovalQueuePage />} />
              <Route path="audit" element={<AuditLogPage />} />
              <Route path="metrics" element={<SystemMetricsPage />} />
              <Route path="documents/:documentId" element={<DocumentDetailPage />} />
            </Route>
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}

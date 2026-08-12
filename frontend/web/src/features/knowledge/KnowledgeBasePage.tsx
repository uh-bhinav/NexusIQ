import { useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { searchKnowledge } from '@/api/knowledge'
import { listDocuments, uploadDocument } from '@/api/documents'
import type { DocumentType } from '@/api/schemas'
import { AsyncState } from '@/components/async-state'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { HttpError } from '@/api/client'

const DOCUMENT_TYPES: DocumentType[] = [
  'SECURITY_POLICY',
  'COMPLIANCE_POLICY',
  'PROCUREMENT_POLICY',
  'ARCHITECTURE_STANDARD',
  'VENDOR_DOCUMENT',
  'HISTORICAL_DECISION',
  'INCIDENT_REPORT',
  'OTHER',
]

function documentStatusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'success' | 'warning' {
  if (status === 'READY') return 'success'
  if (status === 'FAILED') return 'destructive'
  return 'warning'
}

function UploadDocumentForm({ workspaceId }: { workspaceId: string }) {
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [name, setName] = useState('')
  const [documentType, setDocumentType] = useState<DocumentType>('OTHER')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('unreachable: submit is disabled without a file')
      return uploadDocument(workspaceId, file, { name, documentType })
    },
    onSuccess: () => {
      setFile(null)
      setName('')
      setDocumentType('OTHER')
      if (fileInputRef.current) fileInputRef.current.value = ''
      void queryClient.invalidateQueries({ queryKey: ['documents', workspaceId] })
    },
    onError: (err) => setError(err instanceof HttpError ? err.message : 'Failed to upload document.'),
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    mutation.mutate()
  }

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0] ?? null
    setFile(selected)
    if (selected && !name) setName(selected.name)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-foreground">Upload a document</CardTitle>
      </CardHeader>
      <CardContent>
        <form
          onSubmit={handleSubmit}
          noValidate
          className="flex flex-col gap-3"
          aria-label="Upload a document"
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="doc-file">File</Label>
            <input
              id="doc-file"
              ref={fileInputRef}
              type="file"
              required
              accept=".pdf,.docx,.txt,.md"
              onChange={handleFileChange}
              className="flex h-9 w-full rounded-md border border-input bg-background text-sm file:mr-3 file:h-full file:border-0 file:bg-accent file:px-3 file:text-sm"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="doc-name">Name</Label>
            <Input id="doc-name" required value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="doc-type">Document type</Label>
            <select
              id="doc-type"
              className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
              value={documentType}
              onChange={(e) => setDocumentType(e.target.value as DocumentType)}
            >
              {DOCUMENT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
          {error ? (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          ) : null}
          <Button type="submit" disabled={!file || mutation.isPending}>
            {mutation.isPending ? 'Uploading…' : 'Upload'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}

export function KnowledgeBasePage() {
  const { workspaceId } = useParams<{ workspaceId: string }>()
  if (!workspaceId) throw new Error('KnowledgeBasePage rendered outside a workspace route')

  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState<string | null>(null)

  const documentsQuery = useQuery({
    queryKey: ['documents', workspaceId],
    queryFn: () => listDocuments(workspaceId, 0, 20),
    // Ingestion is async (document.uploaded -> ai-service -> document.processed/failed);
    // poll so an upload's status visibly advances from UPLOADED/PROCESSING to READY/FAILED
    // without the user having to manually refresh (spec §8 step 2).
    refetchInterval: 4000,
  })

  const searchQuery = useQuery({
    queryKey: ['knowledge-search', workspaceId, submittedQuery],
    queryFn: () => searchKnowledge(workspaceId, submittedQuery ?? ''),
    enabled: submittedQuery !== null && submittedQuery.length > 0,
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmittedQuery(query.trim())
  }

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-lg font-semibold">Knowledge Base</h1>

      <UploadDocumentForm workspaceId={workspaceId} />

      <div>
        <h2 className="mb-2 text-sm font-medium text-muted-foreground">Documents</h2>
        <AsyncState
          isLoading={documentsQuery.isLoading}
          isError={documentsQuery.isError}
          error={documentsQuery.error}
          onRetry={() => documentsQuery.refetch()}
          isEmpty={documentsQuery.data?.content.length === 0}
          emptyTitle="No documents yet"
          emptyDescription="Upload one above to get started."
        >
          <ul className="flex flex-col gap-2">
            {documentsQuery.data?.content.map((doc) => (
              <li key={doc.id}>
                <Link
                  to={`/w/${workspaceId}/documents/${doc.id}`}
                  className="flex items-center justify-between rounded-md border p-3 text-sm hover:bg-accent"
                >
                  <div>
                    <p className="font-medium">{doc.name}</p>
                    <p className="text-muted-foreground">
                      {doc.document_type} · v{doc.version} · {doc.chunk_count} chunks
                    </p>
                  </div>
                  <Badge variant={documentStatusVariant(doc.status)}>{doc.status}</Badge>
                </Link>
              </li>
            ))}
          </ul>
        </AsyncState>
      </div>

      <div>
        <h2 className="mb-2 text-sm font-medium text-muted-foreground">Search</h2>
        <form onSubmit={handleSubmit} className="flex gap-2" aria-label="Search knowledge base">
          <Input
            type="search"
            placeholder="Search policies, standards, vendor reports…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search query"
          />
          <Button type="submit" disabled={query.trim().length === 0}>
            Search
          </Button>
        </form>

        {submittedQuery === null ? (
          <p className="mt-3 text-sm text-muted-foreground">
            Search the ingested corpus and get cited results with document, section and similarity.
          </p>
        ) : (
          <AsyncState
            isLoading={searchQuery.isLoading}
            isError={searchQuery.isError}
            error={searchQuery.error}
            onRetry={() => searchQuery.refetch()}
            isEmpty={searchQuery.data?.results.length === 0}
            emptyTitle="No results"
            emptyDescription="Try a different query, or check that documents have finished ingesting."
          >
            <ul className="mt-3 flex flex-col gap-3">
              {searchQuery.data?.results.map((result) => (
                <li key={result.chunk_id}>
                  <Card>
                    <CardContent className="flex flex-col gap-2 pt-4">
                      <div className="flex items-center justify-between">
                        <span className="text-sm font-medium">{result.citation_reference}</span>
                        <div className="flex gap-2">
                          {result.is_flagged ? <Badge variant="destructive">Flagged</Badge> : null}
                          <Badge variant="secondary">
                            {(result.similarity_score * 100).toFixed(0)}% match
                          </Badge>
                        </div>
                      </div>
                      <p className="text-sm text-muted-foreground">{result.content}</p>
                    </CardContent>
                  </Card>
                </li>
              ))}
            </ul>
          </AsyncState>
        )}
      </div>
    </div>
  )
}

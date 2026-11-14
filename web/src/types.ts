/** Shared API and domain types for the Vue console. */

export type AuthMe = {
  name: string
  roles: string[]
  securityEnabled: boolean
  jwtEnabled: boolean
  catalogAcl: boolean
  catalogs: string[]
}

export type LlmConfig = {
  protocol?: string
  baseUrl?: string
  model?: string
  apiKeySet?: boolean
  configured?: boolean
  [key: string]: unknown
}

export type LlmStatus = {
  configured?: boolean
  protocol?: string
  model?: string
  apiKeySet?: boolean
  [key: string]: unknown
}

export type LlmTestResult = {
  success?: boolean
  message?: string
  [key: string]: unknown
}

export type QueryColumn = {
  name: string
  type?: string
  [key: string]: unknown
}

export type QueryResult = {
  success?: boolean
  message?: string
  rowCount?: number
  elapsedMs?: number
  cached?: boolean
  rewrittenSql?: string
  columns?: QueryColumn[]
  rows?: unknown[][]
  [key: string]: unknown
}

export type ExampleItem = {
  name: string
  sql: string
  [key: string]: unknown
}

export type CatalogRecord = {
  name: string
  type: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
  url?: string
  extra?: string
  tls?: boolean
  [key: string]: unknown
}

export type MetadataColumn = {
  name: string
  type?: string
  [key: string]: unknown
}

export type MetadataTable = {
  catalog: string
  schema: string
  table: string
  columns?: MetadataColumn[]
  [key: string]: unknown
}

export type OntologyCondition = {
  attribute?: string
  operator?: string
  value?: string
  [key: string]: unknown
}

export type OntologyEntity = {
  name: string
  labelZh?: string
  labelEn?: string
  aliases?: string[]
  comment?: string
  subject?: string
  parent?: string
  table?: string
  timeColumn?: string
  largeTable?: boolean
  certified?: boolean
  domain?: string
  range?: string
  datatype?: string
  column?: string
  unit?: string
  codeList?: string
  identifier?: boolean
  joinFrom?: string
  joinTo?: string
  inverse?: string
  functional?: boolean
  transitive?: boolean
  value?: string
  base?: string
  conditions?: OntologyCondition[]
  definitionText?: string
  sqlCondition?: string
  sqlExpression?: string
  [key: string]: unknown
}

export type OntologyBundle = {
  concepts: OntologyEntity[]
  attributes: OntologyEntity[]
  relations: OntologyEntity[]
  codes: OntologyEntity[]
  definedConcepts: OntologyEntity[]
  [key: string]: unknown
}

export type DraftResult = {
  concepts?: number
  attributes?: number
  relations?: number
  codes?: number
  definedConcepts?: number
  [key: string]: unknown
}

export type DraftStep = {
  key: string
  label: string
}

export type DraftLogEntry = {
  status: string
  text: string
  notes: string[]
}

export type OntologyMatch = {
  name: string
  matchedText?: string
  labelZh?: string
  kind?: string
  score?: number | string
  [key: string]: unknown
}

export type OntologyDefinedHit = {
  name: string
  labelZh?: string
  sqlCondition?: string
  definitionText?: string
  conditions?: OntologyCondition[]
  [key: string]: unknown
}

export type OntologyPathStep = {
  relation?: string
  join?: string
  [key: string]: unknown
}

export type OntologyPath = {
  from?: string
  to?: string
  steps?: OntologyPathStep[]
  [key: string]: unknown
}

export type AskOntologyContext = {
  mode?: string
  status?: string
  confidence?: number | string
  recovery?: boolean
  matches?: OntologyMatch[]
  definedConcepts?: OntologyDefinedHit[]
  paths?: OntologyPath[]
  warnings?: string[]
  unresolved?: string[]
  [key: string]: unknown
}

export type AskMappedColumn = {
  name: string
  [key: string]: unknown
}

export type AskMetadataTable = {
  table: string
  concept?: string
  mappedColumns?: AskMappedColumn[]
  timeColumn?: string
  [key: string]: unknown
}

export type AskMetadataReport = {
  recovery?: boolean
  tables?: AskMetadataTable[]
  issues?: string[]
  [key: string]: unknown
}

export type AskSqlItem = {
  purpose?: string
  status?: string
  sql?: string
  message?: string
  [key: string]: unknown
}

export type AskRating = 'up' | 'down'

export type AskRunStatus = 'answered' | 'clarify' | 'error'

/** 网关为每次问数留下的记录；评价和建议都引用它。 */
export type AskRun = {
  id: string
  conversationId: string
  userName: string
  question: string
  history: Array<{ role: string; content: string }>
  status: AskRunStatus
  answer?: string | null
  sql?: string | null
  evidence: Record<string, unknown>
  error?: string | null
  createdAt: number
  elapsedMs: number
}

export type AskRunView = {
  run: AskRun
  rating: AskRating | null
}

export type AskSuggestionStatus = 'open' | 'adopted' | 'rejected'

export type AskSuggestionDisposition = 'knowledge' | 'prompt' | 'ontology'

export type AskSuggestion = {
  id: number
  runId: string | null
  runQuestion: string | null
  userName: string
  content: string
  status: AskSuggestionStatus
  disposition: AskSuggestionDisposition | null
  reply: string | null
  handledBy: string | null
  createdAt: number
  updatedAt: number
}

/** null 的字段不改；disposition 传空串表示清掉去向。 */
export type AskSuggestionPatch = {
  content?: string
  status?: AskSuggestionStatus
  disposition?: AskSuggestionDisposition | ''
  reply?: string
}

export function emptyOntology(): OntologyBundle {
  return { concepts: [], attributes: [], relations: [], codes: [], definedConcepts: [] }
}

export function errMessage(e: unknown, fallback = ''): string {
  if (e instanceof Error && e.message) {
    return e.message
  }
  if (typeof e === 'string' && e) {
    return e
  }
  return fallback || String(e)
}

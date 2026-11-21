# Production readiness audit

- **Repo:** `rage-data-ai/federation-test`
- **Commit:** `0bf6087581bafe4663914dc4b5456fd13e1e66a8` (`main` / `cursor/prod-readiness-audit-33c0`)
- **Date:** 2026-11-21
- **Method:** Code verification only (no invented root causes)

Severity: **BLOCKER** (must fix before production) · **HIGH** · **MEDIUM** · **LOW**

---

## 1. Reliability & error handling

### Strengths

- `GlobalExceptionHandler` returns RFC 7807 Problem Details; catch-all `Exception` is sanitized to a generic 500 client message while logging the stack (`src/main/java/com/cmcc/cmdevops/security/federation/api/GlobalExceptionHandler.java`).
- Ask / preview SSE catch `RuntimeException`, emit `error` then `done`, and always `complete()` the emitter (`AskController.java`).
- `FlowRunner` records failures via `AskRunRecorder.failed` / `failAndDiscard`, with `MAX_HOPS = 32` loop guard (`FlowRunner.java`).
- Federal DB connect failure fails fast at startup (`FederalDataSource` constructor).
- Query path uses quota + audit + ACL + row filters (`QueryService.java`).

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **HIGH** | SSE error payloads send raw `e.getMessage()` / `e.toString()` to clients (SQL/driver/LLM internals). REST 500s are sanitized; SSE is not. | `AskController.java` ask/preview catch; `OntologyController.java` draft catch |
| **HIGH** | No dedicated handlers for `DataAccessException` / transient JDBC failures; DB blips surface as generic 500 or SSE error strings with no retry/backoff. | `GlobalExceptionHandler` handlers; JDBC stores throw through Spring JDBC |
| **MEDIUM** | `MAX_HOPS` exit only `log.warn`s; no SSE `error` event and no exception — client may see a silent incomplete run then `done`. | `FlowRunner.runDag` after hop loop |
| **MEDIUM** | `SseEmitter` has timeout (`15 * 60 * 1000`) but no `onTimeout` / `onCompletion` / `onError` callbacks to cancel in-flight LLM/SQL work on the virtual thread. | `AskController`, `OntologyController` |
| **MEDIUM** | Open self-registration (`POST /api/v1/auth/register`) grants `QUERY` with no approval; combined with unmetered `/ask` this is an abuse path. | `AuthController.register`, `docs/auth.md` |
| **LOW** | `IllegalArgumentException` / `UnsupportedOperationException` messages are forwarded verbatim to clients (can leak internal validation wording). | `GlobalExceptionHandler` |

---

## 2. Observability & logging

### Strengths

- Actuator + Prometheus registry + Micrometer OTEL bridge + OTLP exporter wired in `pom.xml` / `application.yml`.
- Query latency timer `federation.query.latency` (`QueryService`).
- AUDIT-style logs for query SQL (truncated) and account lifecycle (`AuditService`, `UserService`).
- Catalog / LLM / embed APIs expose `hasPassword` / `apiKeySet` rather than secrets (`CatalogView`, `LlmClient.view`, `AskEmbedSettings.view`).

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **HIGH** | No request ID / MDC / structured JSON logging; no `logging.pattern` or logback config under `src/main/resources`. Cannot correlate SSE hops, DB errors, and client reports. | Only `application.yml` + SLF4J printf-style logs |
| **HIGH** | `/actuator` (including `prometheus`, `metrics`) is skipped by `BearerAuthFilter` — unauthenticated scrape/leak of operational metrics. | `BearerAuthFilter.shouldNotFilter`; `application.yml` `management.endpoints.web.exposure.include` |
| **MEDIUM** | Custom `/api/v1/health` always returns `status: UP` with config flags only — does not probe MySQL / Trino / Milvus connectivity. Unsuitable as readiness. | `HealthController.java`; Dockerfile `HEALTHCHECK` uses this path |
| **MEDIUM** | Default `TRACING_SAMPLE_PROBABILITY=1.0` is full sampling (cost/noise in prod unless overridden). | `application.yml` |
| **MEDIUM** | Metrics coverage is thin: query timer exists; Ask/SSE/FlowRunner/LLM/Milvus paths have no dedicated meters. | `QueryService.recordMetrics` vs Ask stack |
| **LOW** | AUDIT logs include SQL text (truncated to 300 chars); usually acceptable but can still hold PII in predicates. | `AuditService.compact` |

---

## 3. Testing & CI

### Strengths

- CI on `ubuntu-24.04`: `mvn -B verify` (unit + Testcontainers `*IT`) and frontend lint / typecheck / vitest / build / Playwright e2e (`.github/workflows/ci.yml`).
- Jacoco gates: instruction ≥ 0.75, line ≥ 0.70 (`pom.xml`).
- SpotBugs (High, fail), Spotless, Error Prone subset, CycloneDX SBOM, Dependabot, npm audit in CI/release.
- Six Testcontainers ITs with `disabledWithoutDocker = true` (MySQL / Postgres).
- Frontend unit tests (~8) + e2e smoke with visual baselines.

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **MEDIUM** | No frontend coverage gate (vitest has no coverage threshold / CI coverage step). | `web/package.json` scripts; CI frontend job |
| **MEDIUM** | ITs are opt-out without Docker (`disabledWithoutDocker`); local `mvn test` without Docker silently skips JDBC/pgvector coverage. | `*IT.java` annotations; CI comment assumes Docker on runner |
| **LOW** | Release workflow builds frontend but does not re-run Playwright e2e. | `.github/workflows/release.yml` |
| **LOW** | No obvious flaky `Thread.sleep` / retry patterns found in tests (positive). | Grep over `src/test`, `web` |

---

## 4. Performance & resources

### Strengths

- Federal Hikari pool capped at 4, connection timeout 8s (`FederalDataSource`).
- Per-catalog Hikari pools max 4 (`CatalogDataSourceRegistry`).
- `federation.max-rows: 10000`; JDBC `setMaxRows`; Trino query timeout 120s.
- Ask history window default 12 (`history-window-turns`); start node can override; frontend mirrors `DEFAULT_HISTORY_WINDOW = 12`.
- Conversation list capped (`DEFAULT_CONVERSATION_LIMIT=50`, max 200, scan 500).
- Result cache bounded (Caffeine max-size 256, TTL 30s).
- `MAX_HOPS = 32` on DAG walker.

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **HIGH** | `QuotaService` applies only to `/query` (`QueryService`); `/ask` and `/ask/preview` (LLM + SQL + long SSE) are unmetered. | Grep: `quotaService` only in `QueryService` |
| **HIGH** | `QuotaService` is process-local `ConcurrentHashMap` — ineffective across replicas. | `QuotaService.java` |
| **MEDIUM** | Several list APIs are unbounded: flows, apps, knowledge, prompts, users, metadata tables. | `JdbcChatflowStore` / `JdbcAskKnowledgeStore` / `JdbcAskPromptStore` / `JdbcUserStore` / `metadataService.list` |
| **MEDIUM** | SSE timeout 15 minutes holds a virtual thread for the whole Ask; no cooperative cancellation on client disconnect (send failures only logged at debug). | `AskController.TIMEOUT_MS`, `send()` |
| **LOW** | Federal pool size fixed at 4 — may under-serve concurrent Ask + admin under load (tunable ops concern, not a bug). | `FederalDataSource` |

---

## 5. Deployment & ops

### Strengths

- Multi-stage Dockerfile: Node build → Maven package with static UI → non-root `uid 10001`, `MaxRAMPercentage=75`, HEALTHCHECK.
- Compose stack with MySQL / Trino / Milvus healthchecks and gateway `depends_on: service_healthy`.
- `FEDERAL_DB_URL` required (no silent empty DB).
- SECURITY.md documents tokens, CORS, OTEL, revocation Redis/file, SBOM.
- Secrets pattern: env vars / `.env.example`; `.env` gitignored.

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **BLOCKER** | Default static tokens `demo-token` (ADMIN+QUERY) and `reader-token` ship in `application.yml` / `.env.example`. Unchanged deploy = known privileged Bearer. | `application.yml` `federation.security.tokens`; `.env.example` |
| **HIGH** | Actuator prometheus/metrics publicly reachable (see §2). | `BearerAuthFilter`, `application.yml` |
| **HIGH** | Health used as container HEALTHCHECK is liveness-shaped, not readiness (always UP). Orchestrators will keep sending traffic when MySQL is down after startup. | `HealthController`; `Dockerfile` HEALTHCHECK |
| **HIGH** | LLM / embed `api_key` and catalog `password` stored plaintext in MySQL (`fed_llm`, `fed_ask_embed`, `fed_catalog`). | `FederalDataSource.ensureSchema` |
| **MEDIUM** | `docker-compose.yml` embeds `FEDERAL_DB_PASSWORD: federation` (and deps compose uses `root`/`federation`). Fine for local; unsafe if reused as prod template — no Docker secrets / external secret mount. | `docker-compose.yml`, `docker-compose.deps.yml` |
| **MEDIUM** | Default token revocation is in-memory; multi-instance logout/revoke is inconsistent unless Redis/file configured. | `TokenRevocationConfiguration` |
| **MEDIUM** | Swagger UI / OpenAPI (`/swagger-ui.html`, `/v3/api-docs`) are outside `/api/` filter skip logic → unauthenticated API surface documentation. | `BearerAuthFilter.shouldNotFilter`; `springdoc` in `application.yml` |
| **LOW** | No `application-prod.yml` profile with hardened defaults (exposure, sampling, demo tokens empty). | `src/main/resources/` |

---

## 6. Frontend production readiness

### Strengths

- Vite build baked into gateway JAR; History fallback (`WebUiConfiguration`).
- Relative `/api` paths (same-origin in Docker); OIDC PKCE with discovery + refresh (`auth.ts`).
- `ApiError` + `onUnauthorized` session handling; API responses use Problem Details `message`/`detail`.
- Lint max-warnings 0, vue-tsc, Playwright a11y tooling present.
- Catalog/LLM forms treat secrets as write-only (empty placeholder when set).

### Findings

| Sev | Finding | Evidence |
|-----|---------|----------|
| **HIGH** | Access + refresh tokens in `localStorage` — any XSS steals session. Hint in UI acknowledges this; no httpOnly cookie / BFF option. | `web/src/auth.ts` `TOKEN_KEY`; `App.vue` hint |
| **MEDIUM** | No Vue `app.config.errorHandler` / error boundary — uncaught render errors blank the shell. | `web/src/main.js` only `createApp(App).mount` |
| **MEDIUM** | Connection errors hardcode `localhost:18080` even when UI is served from another host. | `web/src/api.ts` lines ~97, ~604; `DatasourceView.vue` |
| **LOW** | OIDC / Vite env is build-time (`VITE_*`); runtime config change requires rebuild (expected for Vite, but ops must bake per environment). | `auth.ts` `import.meta.env`; Dockerfile `npm run build` |
| **LOW** | No frontend coverage gate (see §3). | `web/package.json` |

---

## Priority remediation (suggested order)

1. **BLOCKER:** Force empty / override demo static tokens in production; fail startup if defaults detected when a prod profile/flag is set.
2. **HIGH:** Protect `/actuator/**` (and ideally swagger) with auth or network policy; split liveness vs readiness (DB/Trino checks).
3. **HIGH:** Sanitize SSE error events; meter and quota `/ask`; encrypt or vault-wrap DB-held API keys and catalog passwords.
4. **HIGH:** Add request correlation IDs + structured logging; document/restrict open registration.
5. **MEDIUM:** Emit SSE error on `MAX_HOPS`; wire emitter timeout → cancel; paginate list APIs; Redis revocation + shared quota for HA; Vue error handler; drop hardcoded localhost strings.

---

## Summary scorecard

| Area | Verdict |
|------|---------|
| Reliability & error handling | Solid REST handler; SSE and hop-limit gaps |
| Observability & logging | OTEL/Prometheus present; missing IDs, open actuator, shallow health |
| Testing & CI | Strong backend gates; frontend coverage weaker |
| Performance & resources | Good caps on rows/pools/hops; Ask unmetered; some unbounded lists |
| Deployment & ops | Good container hygiene; default tokens and plaintext secrets block prod |
| Frontend | Build/OIDC OK; localStorage tokens and no global error handler |

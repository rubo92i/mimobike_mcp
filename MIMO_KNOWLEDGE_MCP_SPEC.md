# Mimo Knowledge MCP — Specification

Status: approved for implementation
Date: 2026-08-22
Repository: `rubo92i/mimobike_mcp` (this repo, `D:\Projects\mimobike\mcp`)

## 1. Goal

Expose approved Markdown / OpenAPI documentation from the private Mimo backend
repositories to MCP-compatible coding agents (Claude Code, Codex, …) through a
single remote endpoint:

```
https://mcp.mimobike.com/mcp
```

GitHub repositories are the single source of truth. There is **no database** of
any kind — documents live in an in-memory cache that is refreshed from GitHub.

```
GitHub backend repositories
  → approved Markdown/OpenAPI files (explicit allowlist)
    → in-memory MCP documentation cache (refreshed every 1–5 min)
      → remote read-only MCP tools (Streamable HTTP, bearer auth)
        → Claude Code / Codex / mobile developers
```

## 2. Workspace findings that drive this design

Inspected `D:\Projects\mimobike` (all repos, remotes, build files):

| Fact | Consequence |
|---|---|
| The previous MCP server in this repo (Node + MongoDB→PostgreSQL sync) was removed at the owner's request; the repo was emptied for reuse. Commit `6fc7112`. | This repo is rebuilt as the knowledge MCP server. There are **no pre-existing MCP tools left to preserve** (the old `save/get/search/delete` tools were deleted together with their database backends before this project started). |
| Mimo backend is Java/Spring. Most services are Spring Boot 2.5.14 (Java 8/11); `powerBank` and `relink@master` are already Spring Boot 3.5.7 / Java 21. | New server uses **Java 21 + Spring Boot 3.5.16** — consistent with the newest in-house convention. |
| Spring AI `1.1.8` is the newest 1.1.x MCP server starter compatible with Spring Boot 3.x (2.0.x requires Spring Boot 4). It supports the **Streamable HTTP** protocol. | Use `spring-ai-starter-mcp-server-webmvc` 1.1.8 with `protocol: STREAMABLE`. No legacy SSE. Synchronous WebMVC (no service in the workspace is reactive). |
| Deployment convention: every Mimo Java service runs on the CentOS 8 Stream host as jar in `/opt/services/<name>` + `<name>.sh` launcher in `/usr/local/bin` + `<name>.service` systemd unit + external `application.properties`, nginx in front. | Same convention: `mcp.sh` + `mcp.service` + `/opt/services/mcp/application.properties`, app on port `3100` so existing nginx/DNS for `mcp.mimobike.com` keeps working. No Docker. |
| GitHub access today is PAT-based (no GitHub App / org automation exists anywhere in the infrastructure). | Initial auth to GitHub: **fine-grained read-only PAT** via `GITHUB_TOKEN` env var. A GitHub App is documented as a follow-up hardening step, not implemented now (no practical App infrastructure exists). |
| No company-wide developer-auth solution is reusable for this server (services use their own JWT auth for *mobile end-users*, not for developer tooling). | `/mcp` uses **static bearer tokens from env** (named per developer for auditing). `/internal/reload` uses a **separate** reload token. |
| Local checkouts: `relink` was on stale `backup` branch; `master` is the active branch (Boot 3.5.7 migration, 2026-07). | MCP config uses `relink@master`. |

## 3. Service scope (confirmed by owner)

Only these Java backend services (local directory → GitHub repository → branch):

| service id | Local directory | Repository | Branch |
|---|---|---|---|
| `accounts` | `mimobike_accounts` | `rubo92i/mimobike_accounts` | `master` |
| `admin` | `mimobike_admin` | `rubo92i/mimobike_admin` | `master` |
| `ai-gateway` | `mimobike-ai-gateway` | `rubo92i/mimobike_ai_gateway` | `master` |
| `ev-charger` | `mimobike_ev_charger` | `rubo92i/mimobike_ev_charger` | `master` |
| `ipay` | `mimobike_ipay` | `rubo92i/mimobike_ipay` | `master` |
| `locale` | `mimobike_locale` | `rubo92i/mimobike_locale` | `master` |
| `qr` | `mimobike_qr_server` | `rubo92i/mimobike_qr_server` | `master` |
| `scooter` | `mimobike_scooter` | `rubo92i/mimobike_scooter` | `master` |
| `sharing` | `mimobike_sharing` | `rubo92i/mimobike_sharing` | `master` |
| `ocpp` | `mimobike-ocpp-server` | `rubo92i/mimobike_ocpp_server` | `master` |
| `powerbank` | `powerBank` | `rubo92i/mimobike_charger` | `master` |
| `relink` | `relink` | `rubo92i/mimobike_relink` | `master` |

Excluded (per owner instruction "Java services only"): `srv-iot` (Go),
`srv-adm`, `mimobike_iam`, `mimobike_privacy`, `mimobike_charger_admin` (Node),
`mimo-tcp-srvc`, `mimo_back_pyton` (Python), mobile/web apps, `monitoring`,
and repos without a GitHub remote (`mimobike_auth`, `tcp_spring`, `teltonnika`,
`mimobike_connector`, `mimobike_reports`, `mimobike_websocket_client`).

## 4. Configuration format

`application.yml` (checked in, no secrets):

```yaml
mimo:
  knowledge:
    refresh-interval: ${KNOWLEDGE_REFRESH_INTERVAL:PT3M}   # 1–5 min, ISO-8601
    max-document-chars: 8000        # larger docs return a TOC instead of full text
    max-excerpt-chars: 500
    github:
      api-base-url: ${GITHUB_API_BASE_URL:https://api.github.com}
      token: ${GITHUB_TOKEN:}
    repositories:
      - service: accounts
        repository: rubo92i/mimobike_accounts
        branch: master
        allowed-paths: ["docs/**/*.md", "openapi/**/*.yaml", "openapi/**/*.yml"]
      # … one entry per service in §3, same shape
```

Rules:

* The repository list and `allowed-paths` are an **explicit allowlist**. A file
  is served only if its repo-relative path matches an allowed glob
  (Ant-style matching). Everything else in a repository is invisible —
  source code, `.env*`, workflows, manifests can never be fetched or exposed.
* `allowed-paths` never match paths containing `..`, absolute paths, or paths
  outside the repository tree (the tree listing from GitHub is the only path
  source; client-supplied paths are looked up against the cache, never sent to
  GitHub).
* Secrets come exclusively from environment variables (see §9 / `.env.example`).

## 5. Documentation cache

In-memory only (`ConcurrentHashMap`), one snapshot per service.

Startup:

1. For each configured repository: resolve branch head commit SHA
   (`GET /repos/{owner}/{repo}/commits/{branch}` → sha).
2. List the tree (`GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1`),
   filter blobs against `allowed-paths`.
3. Fetch each allowed blob (`GET /repos/{owner}/{repo}/git/blobs/{file_sha}`,
   base64) and parse it.
4. Markdown is split **by headings** (`#`–`######`), never by token counts.
   YAML front matter is parsed into metadata. The document title is the front
   matter `title`, else the first `#` heading, else the file name.
5. OpenAPI files (`openapi/**`) are parsed into operations keyed by
   `HTTP method + path`, with summary/operationId/tags captured per operation.
6. Every cached document records: service, repository, branch, path, title,
   headings (with levels), full content, sections, commit SHA, canonical GitHub
   URL (`https://github.com/{repo}/blob/{sha}/{path}`), and last refresh time.

Refresh:

* A scheduled task runs every `refresh-interval` (configurable, default 3 min;
  valid range enforced 1–5 min).
* For each repository: fetch head SHA only; if unchanged → skip (no tree/blob
  calls). If changed → load the new snapshot, then atomically replace the old
  one.
* Any failure (GitHub down, rate limit, bad parse) keeps the **last good
  snapshot** serving; the failure is logged and reflected in `/health` as
  `STALE`. A working cache is never cleared by a failed refresh.
* Per-repository locks make scheduled refresh and `/internal/reload` safe to
  run concurrently; snapshot replacement is atomic (single volatile map put).

## 6. MCP interface

Server-level instructions (sent to every client):

```
This server provides authoritative internal documentation for Mimo and EVUP.

Before modifying integrations with Mimo backend services, search this server
for the relevant API, DTO, authentication, payment, wallet, rental or EV
charging documentation.

Never guess endpoint paths, field names, enum values or error codes.

Prefer documents from the newest Git commit. If sources conflict, report the
conflict and identify both sources.
```

Tools (read-only, strict JSON schemas, all responses bounded):

### `list_services`
No arguments. Returns every documented service with its repository, branch,
current commit SHA, last refresh time, and the list of available document paths
(path + title + type).

### `search_docs`
```json
{ "query": "string (required, 2–200 chars)",
  "service": "string (optional, one of the configured service ids)",
  "limit": "integer (optional, default 5, max 10)" }
```
* Searches titles, headings, paths and content of all cached documents
  (and OpenAPI operations).
* Ranking: exact endpoint-path matches (`/api/...`, method + path) rank
  first, then exact identifier matches (DTO names, enum constants,
  error codes — case-sensitive token match), then title/heading hits, then
  content hits.
* Returns at most 10 results, each with: service, repository, path, title,
  matched heading, a short excerpt (≤ `max-excerpt-chars`), commit SHA,
  canonical GitHub URL, and the `fetch` id. Never full documents.

### `read_doc`
```json
{ "service": "string (required)",
  "path": "string (required, repo-relative, must be cached/allowlisted)",
  "heading": "string (optional — return only this section)" }
```
* Serves only from the cache (allowlisted docs); unknown service/path → error
  listing valid options. Path traversal input cannot escape: paths are cache
  keys, not filesystem/GitHub lookups.
* With `heading`: returns that section only (fuzzy match on heading text or
  anchor).
* Without `heading`: full document if ≤ `max-document-chars`, otherwise its
  table of contents + metadata + instruction to request one heading.
* Always returns source metadata (repository, branch, path, commit SHA,
  canonical URL, last refresh time).

### `search` / `fetch` (compatibility aliases)
* `search {query}` → same implementation as `search_docs`.
* `fetch {id}` → `id` is `service:path` or `service:path#heading` (returned by
  search results); same implementation as `read_doc`.

## 7. HTTP surface

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/mcp` | POST/GET/DELETE | Developer bearer token | MCP Streamable HTTP endpoint |
| `/internal/reload` | POST | Reload bearer token (separate) | Targeted repository refresh |
| `/health` | GET | none | Liveness + cache state (no sensitive data) |
| `/health/ready` | GET | none | Readiness (503 until a usable cache exists) |

### `/internal/reload`

```http
POST /internal/reload
Authorization: Bearer <MCP_RELOAD_TOKEN>
Content-Type: application/json

{ "repository": "rubo92i/mimobike_ipay" }
```

* 401 if the token is missing/wrong (constant-time comparison).
* 404 if the repository is not in the configured allowlist.
* Refreshes **only** that repository, under the same per-repo lock as the
  scheduler. Response:

```json
{ "repository": "rubo92i/mimobike_ipay", "service": "ipay",
  "previousSha": "abc…", "newSha": "def…", "changed": true,
  "status": "refreshed", "documents": 6 }
```

* If the refresh fails, the previous cache is kept and the response reports
  `"status": "failed"` (HTTP 502) with the previous SHA still active.

### `/health`

States, distinguished in the JSON body:

| `cache` value | Meaning |
|---|---|
| `LOADING` | App running, initial load not finished |
| `OK` | All repositories loaded, GitHub reachable at last refresh |
| `STALE` | Serving last good snapshot; most recent refresh of ≥1 repo failed |
| `EMPTY` | No valid documentation cache exists (readiness → 503) |

## 8. Security

* `/mcp` requires `Authorization: Bearer <token>` where token ∈
  `MCP_AUTH_TOKENS` (format `name:token,name:token,…` — the name is the
  developer identity used in audit logs).
* `/internal/reload` requires the separate `MCP_RELOAD_TOKEN`. Reload tokens
  cannot call `/mcp` and developer tokens cannot call `/internal/*`.
* All token comparison uses `MessageDigest.isEqual` (constant time).
* Tokens and `Authorization` headers are never logged; no request-header
  logging is enabled anywhere.
* Audit log (SLF4J logger `AUDIT`, structured): developer identity, MCP tool
  name, service, document path, result size class — never document content.
* GitHub token: fine-grained PAT, **read-only Contents** permission, scoped to
  exactly the 12 repositories in §3.
* Rate limiting: no established in-house solution exists → not added in v1
  (nginx in front can add `limit_req` if needed; documented in the production
  readiness doc).
* The server exposes only cache entries; it never proxies arbitrary GitHub
  paths. `.env*`, source files, deployment manifests are structurally
  unreachable (not in `allowed-paths`, so never even fetched).

## 9. Secrets / external configuration

`application.properties.example` (key names only, committed) → copied to
`/opt/services/mcp/application.properties` on the host and loaded via
`--spring.config.additional-location` by `mcp.sh`:

```
mimo.knowledge.github.token=     # fine-grained PAT, read-only Contents on the 12 repos
mimo.security.auth-tokens=       # dev tokens: alice:token1,bob:token2
mimo.security.reload-token=      # separate token for POST /internal/reload
mimo.knowledge.refresh-interval= # optional, ISO-8601, default PT3M (min PT1M, max PT5M)
server.port=                     # optional, default 3100
```

(The equivalent env vars `GITHUB_TOKEN`, `MCP_AUTH_TOKENS`, `MCP_RELOAD_TOKEN`,
`KNOWLEDGE_REFRESH_INTERVAL`, `PORT` also work when exported.)

## 10. Per-service repository changes

In each of the 12 service repositories:

1. `docs/` — only meaningful files from:
   `overview.md`, `mobile-api.md`, `authentication.md`, `error-codes.md`,
   `events.md`, `configuration.md` — derived **strictly from the actual code**
   (controllers, DTOs, security config, exception handlers, event contracts,
   `application.*` config). Front matter on every file:

   ```yaml
   ---
   service: <id>
   audience: [mobile, backend]
   owner: backend-team
   status: verified
   last_verified_at: 2026-08-22
   ---
   ```

   No real credentials, tokens, card data, phone numbers or customer records.
2. `CLAUDE.md` — merged (not replaced) with the “Documentation maintenance”
   rules (update docs when externally observable behavior changes; never invent
   documentation).
3. `.claude/settings.json` — merged Stop hook that blocks finishing when
   externally observable changes lack doc updates (agent hook on
   Claude Code ≥ 2.x, which is installed: 2.1.240).
4. `.github/workflows/mcp-docs-sync.yml` — on push to the production branch
   touching `docs/**`, `openapi/**`, `asyncapi/**`, `README.md`, calls
   `POST https://mcp.mimobike.com/internal/reload` with secret
   `MIMO_MCP_RELOAD_TOKEN` and the repository name in the body; bounded retry;
   temporary MCP unavailability does not fail the workflow permanently.

## 11. Technology summary

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (workspace JDK: `openjdk-21.0.2`) |
| Framework | Spring Boot 3.5.16, WebMVC, synchronous |
| MCP | Spring AI 1.1.8 `spring-ai-starter-mcp-server-webmvc`, Streamable HTTP, stateless-capable, endpoint `/mcp` |
| GitHub access | REST API via Spring `RestClient`, PAT from env |
| YAML/OpenAPI parsing | SnakeYAML (already on the Boot classpath) |
| Markdown parsing | Own heading-splitter (small, deterministic, no extra deps) |
| Build | Maven + wrapper (`mvnw`, Maven 3.9.x) |
| Tests | JUnit 5, Spring Boot Test, MockMvc, MockRestServiceServer (no real GitHub) |
| Logging | Rotated file `/opt/log/mcp.log` in prod profile (same values as the other services: 100MB/file, 365 days, 100GB cap); console in dev |
| Packaging | Boot fat jar; `mcp.sh` + `mcp.service` (CentOS host convention, like every other Mimo Java service) |

## 12. Test plan

Covered by automated tests (all GitHub traffic mocked):

GitHub document loading; repository allowlist enforcement; file path allowlist
enforcement; Markdown heading parsing; OpenAPI operation parsing; exact
endpoint search; DTO / error-code search; result limits (≤10); oversized
document handling (TOC); cache refresh; unchanged-SHA short-circuit; GitHub
failure fallback (stale cache kept); reload endpoint auth; MCP endpoint auth;
path-traversal prevention; secret/unauthorized file non-exposure; tool schema
validation. Manual MCP verification questions run after deployment (see
production readiness doc).

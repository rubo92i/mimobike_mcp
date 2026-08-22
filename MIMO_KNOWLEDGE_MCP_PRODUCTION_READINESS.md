# Mimo Knowledge MCP — Production Readiness

Date: 2026-08-22 · Companion to [MIMO_KNOWLEDGE_MCP_SPEC.md](MIMO_KNOWLEDGE_MCP_SPEC.md)

This document lists what must be true before `https://mcp.mimobike.com/mcp`
serves developers, how each requirement is met, and which actions remain
manual.

## 1. Runtime & availability

| Requirement | How it is met |
|---|---|
| Single JVM service, no database | Spring Boot 3.5.16 fat jar; state = in-memory cache only; restart cost = one GitHub reload |
| Liveness | `GET /health` — 200 as soon as the app is up; body reports cache state (`LOADING` / `OK` / `STALE` / `EMPTY`) |
| Readiness | `GET /health/ready` — 503 until at least one repository snapshot is loaded; used by nginx/orchestrator before routing |
| GitHub outage | Refresh failures never clear the last good snapshot; state degrades to `STALE`, tools keep answering from stale cache; recovery is automatic on the next successful refresh |
| Startup with GitHub down | App starts, `/health/ready` stays 503 (`EMPTY`), scheduler keeps retrying every `refresh-interval` — no crash loop |
| Memory bound | Only allowlisted `docs/**` + `openapi/**` files are cached (Markdown text; MBs, not GBs). Response sizes are bounded (`max-document-chars`, ≤10 search results, excerpt caps) |
| Concurrency | Snapshot replacement is a single atomic map write; per-repository `ReentrantLock` serializes scheduled refresh vs `/internal/reload`; reads are lock-free |

## 2. Security checklist

| Item | Status |
|---|---|
| `/mcp` requires developer bearer token (`MCP_AUTH_TOKENS`, `name:token` pairs) | implemented (servlet filter, applies to every `/mcp*` request) |
| `/internal/reload` requires **separate** `MCP_RELOAD_TOKEN`; tokens are not interchangeable | implemented |
| Constant-time comparison (`MessageDigest.isEqual`) | implemented |
| Tokens / `Authorization` headers never logged | no header logging anywhere; audit log records developer *name* only; verified by test + code review |
| Audit log: developer, tool, service, path — never content | implemented (`AUDIT` logger) |
| GitHub token read-only, contents-only, scoped to the 11 repos | **manual action** — create the fine-grained PAT this way; the server only ever calls read endpoints (`commits`, `git/trees`, `git/blobs`) |
| No secrets in the repo | `application.properties.example` has key names only; `application.yml` uses `${ENV}` placeholders; verified before every commit |
| Arbitrary-file exposure impossible | files outside `allowed-paths` are never fetched from GitHub, so they cannot exist in the cache; client paths are cache-map keys (no filesystem, no GitHub passthrough); traversal inputs (`../`, absolute, encoded) find no key and return a not-found error — covered by tests |
| Source code / `.env` / manifests unreachable | same mechanism; allowlist is `docs/**` + `openapi/**` only |
| Rate limiting | **not included in v1** — no established in-house solution exists to reuse (spec rule). Mitigations: token-gated endpoint, bounded response sizes, cheap cache-only reads. If needed, enable nginx `limit_req` (example in `deploy/nginx-mcp.conf`) |
| TLS | terminated at nginx (`mcp.mimobike.com`), as for all existing Mimo services |

## 3. Configuration & secrets

Required settings in `/opt/services/mcp/application.properties`
(template: `application.properties.example`; equivalent env vars
`GITHUB_TOKEN`/`MCP_AUTH_TOKENS`/`MCP_RELOAD_TOKEN` also work if exported):

| Property | Purpose | Rotation |
|---|---|---|
| `mimo.knowledge.github.token` | fine-grained PAT, read-only Contents on the 11 configured repos | rotate in GitHub → update the properties file → `systemctl restart mcp` (cache rebuilds on start) |
| `mimo.security.auth-tokens` | `alice:tokenA,bob:tokenB` — one entry per developer | add/remove entries + restart; removing an entry revokes that developer |
| `mimo.security.reload-token` | GitHub Actions → `/internal/reload` | rotate here **and** in each repo's `MIMO_MCP_RELOAD_TOKEN` Actions secret |
| `mimo.knowledge.refresh-interval` | optional, `PT1M`–`PT5M`, default `PT3M` | — |
| `server.port` | optional, default `3100` (matches existing nginx upstream) | — |

Token generation: `openssl rand -hex 32` per developer / per purpose. Never
reuse the reload token as a developer token.

## 4. Deployment

Same convention as every other Mimo Java service on the CentOS 8 Stream host
(documented in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)): Jenkins (or manual)
runs `scripts/jenkins-deploy.sh`, which builds with `./mvnw -DskipTests
package`, copies the jar to `/opt/services/mcp/mcp.jar` (previous jar kept as
`mcp.jar.prev`), installs the `mcp.sh` launcher to `/usr/local/bin` and the
`mcp.service` systemd unit, restarts, then polls `/health/ready`. Secrets stay
in `/opt/services/mcp/application.properties` (never overwritten by deploys).

Rollback: `mv mcp.jar.prev mcp.jar && systemctl restart mcp`. The service is
stateless — rollback has no data migration concerns.

Nginx: `deploy/nginx-mcp.conf` — TLS, `proxy_pass http://127.0.0.1:3100`,
buffering off for streaming responses, optional `limit_req` block.

## 5. Observability

* Structured JSON logs (Spring Boot ECS format) under the `prod` profile;
  human-readable logs in dev. journald picks them up via the systemd unit.
* `AUDIT` logger: one line per MCP tool call — developer, tool, service, path.
* `/health` (public) reports only the aggregate cache state + counts; a
  curl-based external monitor (existing `monitoring` host) can alert on
  `STALE`/`EMPTY`. Per-repository SHAs, last errors and refresh timestamps are
  behind the reload token at `GET /internal/status`.
* GitHub API quota: 1 SHA probe per repo per interval (11 requests / 3 min ≈
  220/h) + tree/blob calls only on change — far below the 5 000/h PAT limit.

## 6. Test matrix (automated, GitHub fully mocked)

| Area | Test |
|---|---|
| GitHub document loading | `GitHubClientTest`, `RepositoryLoaderTest` |
| Repository + path allowlist | `AllowlistTest` (allowed globs, disallowed files never fetched/served) |
| Markdown heading parsing / front matter | `MarkdownParserTest` |
| OpenAPI operation parsing | `OpenApiParserTest` |
| Exact endpoint search / DTO & error-code search / limits / excerpts | `SearchServiceTest` |
| Oversized doc → TOC; heading extraction | `ReadDocToolTest` |
| Cache refresh / unchanged SHA skip / failure fallback / concurrent reload | `RefreshServiceTest` |
| `/mcp` auth, `/internal/reload` auth, token separation, health openness | `SecurityFilterTest` (MockMvc) |
| Reload endpoint behavior (SHA before/after, changed flag, unknown repo) | `ReloadControllerTest` |
| Path traversal, secret/unauthorized file non-exposure | `AllowlistTest`, `ReadDocToolTest` |
| MCP tool wiring + schemas + bounded responses | `KnowledgeToolsTest`, `McpServerIntegrationTest` |

CI-friendly: `./mvnw test` needs no network beyond Maven Central and no
credentials.

## 7. Manual verification after first deployment

Connect Claude Code (`claude mcp add --transport http mimo-knowledge
https://mcp.mimobike.com/mcp`) and ask:

1. “How does Mimo authentication work?” → must cite `accounts`/`sharing`
   authentication docs with repo, path, SHA.
2. “Which endpoint starts an EVUP charging session?” → `ev-charger` docs.
3. “What errors can the wallet top-up endpoint return?” → `ipay` error docs.
4. “Which service owns localization?” → `locale`.
5. “Show the source and commit SHA for this answer.” → every tool response
   carries repository, path, commit SHA, canonical URL.

## 8. Remaining manual actions (cannot be automated from this workspace)

1. Create the fine-grained read-only GitHub token (Contents: read on the 11
   repos) and place it in `/opt/services/mcp/application.properties`.
2. Generate developer token entries and the reload token in the same file;
   distribute developer tokens out of band.
3. Add the `MIMO_MCP_RELOAD_TOKEN` Actions secret in each of the 11 service
   repositories (same value as `MCP_RELOAD_TOKEN`).
4. Point `mcp.mimobike.com` nginx at port 3100 with `deploy/nginx-mcp.conf`
   (or confirm the existing vhost).
5. Review + commit + push the generated `docs/`, `CLAUDE.md`,
   `.claude/settings.json`, and `.github/workflows/mcp-docs-sync.yml` in each
   service repository (nothing is pushed automatically).
6. Rotate the classic PAT currently embedded in local git remote URLs
   (pre-existing issue, unrelated to this service, but it is a broad-scope
   classic token — replace with fine-grained tokens).

## 9. Known limitations / accepted trade-offs (v1)

* Static bearer tokens instead of SSO/OIDC — acceptable for a small internal
  developer group; revocable per developer; upgrade path: GitHub App + OIDC
  device flow.
* No rate limiting in-app (see §2).
* Search is deterministic lexical scoring, not semantic — intentional (no
  vector DB allowed); documentation is small and identifier queries are the
  primary use case.
* OpenAPI directories don't exist yet in most services; the allowlist already
  covers `openapi/**` so specs become available the moment they are committed.

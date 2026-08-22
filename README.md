# Mimo Knowledge MCP

Read-only remote MCP server that gives Claude Code, Codex and other
MCP-compatible agents access to **approved documentation** from the private
Mimo/EVUP backend repositories:

```
GitHub backend repositories
  → approved Markdown/OpenAPI files (explicit allowlist)
    → in-memory documentation cache (refreshed every 1–5 min)
      → read-only MCP tools over Streamable HTTP
        → https://mcp.mimobike.com/mcp
```

**No database.** GitHub is the single source of truth; the only state is an
in-memory cache that survives GitHub outages by serving the last good
snapshot.

## Stack

Java 21 · Spring Boot 3.5 · Spring AI MCP Server (WebMVC, Streamable HTTP,
synchronous) · no persistence.

## MCP tools

| Tool | Purpose |
|---|---|
| `list_services` | Documented services + available document paths |
| `search_docs` | Ranked search (exact endpoints/DTOs/error codes first), ≤10 excerpt results |
| `read_doc` | One document or one heading-scoped section, with source metadata; large docs return a TOC |
| `search` / `fetch` | Compatibility aliases over the same implementation |

Every response includes repository, path, commit SHA and canonical GitHub URL.

## HTTP surface

| Endpoint | Auth |
|---|---|
| `POST /mcp` | developer bearer token (`MCP_AUTH_TOKENS`, `name:token` pairs) |
| `POST /internal/reload` · `GET /internal/status` | separate reload token (`MCP_RELOAD_TOKEN`) |
| `GET /health` · `GET /health/ready` | none (liveness/readiness) |

## Configuration

Repositories and path allowlists live in
[`src/main/resources/application.yml`](src/main/resources/application.yml)
(`mimo.knowledge.repositories`); only `docs/**` and `openapi/**` files can ever
be fetched or served. Secrets come exclusively from the environment — see
[`.env.example`](.env.example).

## Build & test

```bash
./mvnw test
./mvnw package
```

Tests mock GitHub entirely; no credentials or network access to GitHub needed.

## Docs

* [MIMO_KNOWLEDGE_MCP_SPEC.md](MIMO_KNOWLEDGE_MCP_SPEC.md) — design + decisions
* [MIMO_KNOWLEDGE_MCP_PRODUCTION_READINESS.md](MIMO_KNOWLEDGE_MCP_PRODUCTION_READINESS.md) — go-live checklist
* [docs/CLIENT_SETUP.md](docs/CLIENT_SETUP.md) — connecting Claude Code / Codex
* [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — systemd/Docker deploy, nginx, rollback

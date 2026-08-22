# Mimo Knowledge MCP — Deployment

Target: the existing Mimo host behind nginx, domain `mcp.mimobike.com`,
app port **3100** (same port the previous MCP service used, so the nginx
upstream/DNS needs no change). The service is stateless — its only state is
the in-memory documentation cache, rebuilt from GitHub on startup.

## 1. One-time host provisioning

```bash
sudo mkdir -p /opt/services/mcp
sudo cp .env.example /opt/services/mcp/.env
sudo chmod 600 /opt/services/mcp/.env
sudo nano /opt/services/mcp/.env    # fill in real values
```

Required values (see `.env.example`):

| Variable | Value |
|---|---|
| `GITHUB_TOKEN` | fine-grained PAT, **read-only Contents**, scoped to the 11 configured repos |
| `MCP_AUTH_TOKENS` | `name:token` pairs, one per developer (`openssl rand -hex 32` each) |
| `MCP_RELOAD_TOKEN` | separate token for GitHub Actions (`openssl rand -hex 32`) |

Deploys never overwrite `/opt/services/mcp/.env` (same rule as before).
A JRE/JDK 21 must be installed on the host (`java -version` → 21).

## 2. Deploy (systemd path — current convention)

From the repo root (Jenkins job or manually):

```bash
./scripts/jenkins-deploy.sh
```

The script builds the jar, keeps the previous one as `app.jar.prev`, installs
`scripts/mcp-knowledge.service`, restarts the unit and polls
`/health/ready` for up to 60 s.

## 3. Deploy (Docker path)

```bash
docker build -t mimo-knowledge-mcp:1.0.0 .
docker run -d --name mcp-knowledge \
  --env-file /opt/services/mcp/.env \
  -p 127.0.0.1:3100:3100 \
  --restart unless-stopped \
  mimo-knowledge-mcp:1.0.0
```

The image runs as a non-root user and has a built-in `HEALTHCHECK` on
`/health/ready`.

## 4. Nginx

Install `deploy/nginx-mcp.conf` as the `mcp.mimobike.com` vhost (or merge into
the existing one), then `sudo nginx -t && sudo systemctl reload nginx`.
Key points: `proxy_buffering off` (streamable responses), generous
`proxy_read_timeout`, TLS terminated here.

## 5. Health semantics

| Endpoint | Meaning |
|---|---|
| `GET /health` → `cache: LOADING` | app running, initial GitHub load in progress |
| `GET /health` → `cache: OK` | all repositories loaded, last refresh succeeded |
| `GET /health` → `cache: STALE` | GitHub temporarily unavailable — **stale cache still serving** (acceptable; auto-recovers) |
| `GET /health` → `cache: EMPTY` | no valid cache at all (GitHub unreachable since startup) |
| `GET /health/ready` | 200 when OK/STALE, 503 when LOADING/EMPTY — use for routing decisions |

Detailed per-repository state (SHAs, last errors):
`GET /internal/status` with `Authorization: Bearer <MCP_RELOAD_TOKEN>`.

## 6. Logs

* systemd: `journalctl -u mcp-knowledge -f`
* `SPRING_PROFILES_ACTIVE=prod` (set by the unit/Docker image) switches the
  console format to structured ECS JSON.
* Audit trail: logger `AUDIT` — one line per MCP tool call with developer,
  tool, service and path (never content, never tokens).

## 7. Rollback

```bash
sudo mv /opt/services/mcp/app.jar.prev /opt/services/mcp/app.jar
sudo systemctl restart mcp-knowledge
curl -fsS http://127.0.0.1:3100/health/ready
```

(Docker: rerun with the previous image tag.) No data migrations exist; the
cache rebuilds itself from GitHub on start.

## 8. GitHub Actions secret (per service repository)

Each backend repo's `mcp-docs-sync.yml` workflow needs the Actions secret
`MIMO_MCP_RELOAD_TOKEN` = the server's `MCP_RELOAD_TOKEN` value:
repo → Settings → Secrets and variables → Actions → New repository secret.
Without it the workflow fails with a clear error; docs still reach the server
via the scheduled refresh (≤ 5 min).

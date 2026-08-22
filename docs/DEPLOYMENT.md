# Mimo Knowledge MCP — Deployment

Target: the existing Mimo CentOS 8 Stream host behind nginx, domain
`mcp.mimobike.com`, app port **3100**. The service runs exactly like the other
Mimo Java services: a fat jar in `/opt/services/mcp`, a `mcp.sh` launcher in
`/usr/local/bin`, a `mcp.service` systemd unit, and external configuration in
`/opt/services/mcp/application.properties`. The service is stateless — its
only state is the in-memory documentation cache, rebuilt from GitHub on
startup.

## 1. One-time host provisioning

Java 21 must be available at `/usr/lib/jvm/java-21` (same as the powerbank
service). Then:

```bash
sudo mkdir -p /opt/services/mcp /opt/log
sudo cp application.properties.example /opt/services/mcp/application.properties
sudo chmod 600 /opt/services/mcp/application.properties
sudo nano /opt/services/mcp/application.properties   # fill in real values
```

Required values (see `application.properties.example`):

| Property | Value |
|---|---|
| `mimo.knowledge.github.token` | fine-grained PAT, **read-only Contents**, scoped to the 11 configured repos |
| `mimo.security.auth-tokens` | `name:token` pairs, one per developer (`openssl rand -hex 32` each) |
| `mimo.security.reload-token` | separate token for GitHub Actions (`openssl rand -hex 32`) |

Deploys never overwrite `/opt/services/mcp/application.properties`.
The repository allowlist itself lives inside the jar
(`src/main/resources/application.yml`) — the external file only adds secrets
and overrides (`mcp.sh` passes it via `--spring.config.additional-location`).

## 2. Deploy

From the repo root (Jenkins job or manually):

```bash
./scripts/jenkins-deploy.sh
```

The script builds the jar, keeps the previous one as `mcp.jar.prev`, installs
`mcp.sh` to `/usr/local/bin`, installs `mcp.service`, restarts the unit and
polls `/health/ready` for up to 60 s.

Manual control, same as every other service:

```bash
sudo systemctl status mcp
sudo systemctl restart mcp
```

(or directly `/usr/local/bin/mcp.sh start|stop|restart`).

## 3. Nginx

Install `deploy/nginx-mcp.conf` as the `mcp.mimobike.com` vhost (or merge into
the existing one), then `sudo nginx -t && sudo systemctl reload nginx`.
Key points: `proxy_buffering off` (streamable responses), generous
`proxy_read_timeout`, TLS terminated here.

## 4. Health semantics

| Endpoint | Meaning |
|---|---|
| `GET /health` → `cache: LOADING` | app running, initial GitHub load in progress |
| `GET /health` → `cache: OK` | all repositories loaded, last refresh succeeded |
| `GET /health` → `cache: STALE` | GitHub temporarily unavailable — **stale cache still serving** (acceptable; auto-recovers) |
| `GET /health` → `cache: EMPTY` | no valid cache at all (GitHub unreachable since startup) |
| `GET /health/ready` | 200 when OK/STALE, 503 when LOADING/EMPTY — use for routing decisions |

Detailed per-repository state (SHAs, last errors):
`GET /internal/status` with `Authorization: Bearer <reload token>`.

## 5. Logs

Same as the other services: `mcp.sh` discards stdout and the app (prod
profile, activated by `mcp.sh`) writes to a rotated file — 100MB per file,
365 days history, 100GB cap, rolled files in `/opt/log/mcp/`:

```bash
tail -f /opt/log/mcp.log
```

Audit trail: logger `AUDIT` — one line per MCP tool call with developer,
tool, service and path (never content, never tokens).

## 6. Rollback

```bash
sudo mv /opt/services/mcp/mcp.jar.prev /opt/services/mcp/mcp.jar
sudo systemctl restart mcp
curl -fsS http://127.0.0.1:3100/health/ready
```

No data migrations exist; the cache rebuilds itself from GitHub on start.

## 7. GitHub Actions secret (per service repository)

Each backend repo's `mcp-docs-sync.yml` workflow needs the Actions secret
`MIMO_MCP_RELOAD_TOKEN` = the server's `mimo.security.reload-token` value:
repo → Settings → Secrets and variables → Actions → New repository secret.
Without it the workflow fails with a clear error; docs still reach the server
via the scheduled refresh (≤ 5 min).

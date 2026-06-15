# mimobike MCP

An MCP (Model Context Protocol) server backed by PostgreSQL, plus a sync worker
that mirrors MongoDB collections into PostgreSQL in real time.

## Components

| Component   | Entry point      | Description                                                   |
|-------------|------------------|---------------------------------------------------------------|
| MCP server  | `src/server.mjs` | Exposes `save` / `get` / `search` / `delete` tools over SSE.  |
| Sync worker | `src/sync.mjs`   | Initial load + change-stream sync of Mongo → PostgreSQL.      |

## Project layout

```
src/
  config.mjs    # Loads & validates configuration from environment / .env
  db.mjs        # Shared PostgreSQL connection pool
  server.mjs    # MCP HTTP/SSE server
  sync.mjs      # MongoDB -> PostgreSQL sync worker
db/
  schema.sql    # PostgreSQL schema (idempotent)
scripts/
  migrate.mjs         # Applies db/schema.sql
  jenkins-deploy.sh   # Jenkins deploy script (copies app + installs systemd units)
  mcp-server.service  # systemd unit for the MCP server
  mcp-sync.service    # systemd unit for the sync worker
.env.example    # Template for configuration / secrets
```

## Configuration

All credentials and connection settings come from environment variables
(loaded from a local `.env` file via dotenv). **No secrets live in source.**

1. Copy the template and fill in real values:
   ```bash
   cp .env.example .env
   ```
2. Set at least `PG_DATABASE`, `PG_USER`, `PG_PASSWORD` and `MONGO_URI`.

The `.env` file is git-ignored and must never be committed.

## Local development

```bash
npm install
cp .env.example .env   # then edit .env
npm run migrate        # create tables
npm start              # run the MCP server
npm run start:sync     # (separate terminal) run the sync worker
```

Health check: `GET http://localhost:3100/health`.

## Deployment (Jenkins)

The app is deployed to `/opt/services/mcp`. The target host needs Node.js >= 20,
network access to PostgreSQL/MongoDB, and a populated `/opt/services/mcp/.env`
(provisioned once on the host — never overwritten by deploys).

From the root of the Jenkins workspace (checked-out repo):

```bash
bash scripts/jenkins-deploy.sh
```

`jenkins-deploy.sh` syncs the app into `/opt/services/mcp` (preserving `.env`),
installs production dependencies, applies the schema, installs the systemd units
(`mcp-server`, `mcp-sync`), then enables and restarts them.

Check status / logs on the host:

```bash
systemctl status mcp-server mcp-sync
journalctl -u mcp-server -f
journalctl -u mcp-sync -f
```

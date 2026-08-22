# Mimo Knowledge MCP — Developer Setup

Connect your coding agent to the internal documentation server at
`https://mcp.mimobike.com/mcp`. Access requires a personal bearer token —
ask the backend team for yours (each developer gets a named token; it is your
identity in the audit log).

Keep your token out of git. Everything below stores it in user-level
configuration or environment variables, never in committed files.

## Claude Code

Recommended (user scope — available in every project):

```bash
claude mcp add \
  --transport http \
  --scope user \
  --header "Authorization: Bearer ${MIMO_MCP_TOKEN}" \
  mimo-knowledge \
  https://mcp.mimobike.com/mcp
```

Set `MIMO_MCP_TOKEN` in your shell profile (e.g. `.bashrc` / PowerShell
`$PROFILE`) so the header resolves at startup. Verify with `/mcp` inside
Claude Code — the server should show tools `list_services`, `search_docs`,
`read_doc`, `search`, `fetch`.

### Project-level `.mcp.json`

To pin the server for a whole repository, commit a `.mcp.json` that references
the token via environment expansion — **never commit the token itself**:

```json
{
  "mcpServers": {
    "mimo-knowledge": {
      "type": "http",
      "url": "https://mcp.mimobike.com/mcp",
      "headers": {
        "Authorization": "Bearer ${MIMO_MCP_TOKEN}"
      }
    }
  }
}
```

Each developer defines `MIMO_MCP_TOKEN` locally; the file stays secret-free.

## Codex

In `~/.codex/config.toml`:

```toml
[mcp_servers.mimo_knowledge]
url = "https://mcp.mimobike.com/mcp"
required = true
tool_timeout_sec = 30

[mcp_servers.mimo_knowledge.http_headers]
Authorization = "Bearer YOUR_PERSONAL_TOKEN"
```

`~/.codex/config.toml` is user-local (not in any repo). If your Codex version
supports env expansion in headers, prefer
`Authorization = "Bearer ${MIMO_MCP_TOKEN}"`.

## Authentication model (v1)

* Transport: MCP Streamable HTTP (single endpoint, `POST /mcp`).
* Auth: static per-developer bearer tokens issued by the backend team
  (`MCP_AUTH_TOKENS` on the server). Lost/leaked token → tell the backend
  team; removing your entry revokes it immediately.
* The server is read-only: it can only return approved `docs/**` and
  `openapi/**` files from the configured backend repositories.

## What to ask it

* "How does Mimo authentication work?"
* "Which endpoint starts an EVUP charging session?"
* "What errors can the wallet top-up endpoint return?"
* "Which service owns localization?"

Every answer carries the repository, file path, commit SHA and a canonical
GitHub URL — prefer those citations over guessing, and report conflicts
between documents instead of picking silently.

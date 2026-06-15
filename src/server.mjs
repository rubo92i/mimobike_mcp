import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { z } from "zod";
import http from "http";
import { config } from "./config.mjs";
import { pool } from "./db.mjs";

const transports = {};

function createServer() {
  const server = new McpServer({ name: "evup-mcp", version: "1.0.0" });

  server.tool("save", {
    key: z.string(),
    content: z.string(),
    category: z.string().optional(),
    metadata: z.record(z.any()).optional(),
  }, async ({ key, content, category = "general", metadata = {} }) => {
    await pool.query(`
      INSERT INTO memory (key, content, category, metadata, updated_at)
      VALUES ($1, $2, $3, $4, NOW())
      ON CONFLICT (key) DO UPDATE
      SET content = $2, category = $3, metadata = $4, updated_at = NOW()
    `, [key, content, category, JSON.stringify(metadata)]);
    return { content: [{ type: "text", text: `Saved: ${key}` }] };
  });

  server.tool("get", { key: z.string() }, async ({ key }) => {
    const res = await pool.query(
      "SELECT key, content, category, metadata, updated_at FROM memory WHERE key = $1",
      [key]
    );
    if (!res.rows.length) return { content: [{ type: "text", text: `Not found: ${key}` }] };
    return { content: [{ type: "text", text: JSON.stringify(res.rows[0]) }] };
  });

  server.tool("search", {
    category: z.string().optional(),
    query: z.string().optional(),
  }, async ({ category, query }) => {
    let sql = "SELECT key, content, category, updated_at FROM memory WHERE 1=1";
    const params = [];
    if (category) { params.push(category); sql += ` AND category = $${params.length}`; }
    if (query) { params.push(`%${query}%`); sql += ` AND (key ILIKE $${params.length} OR content ILIKE $${params.length})`; }
    sql += " ORDER BY updated_at DESC LIMIT 50";
    const res = await pool.query(sql, params);
    return { content: [{ type: "text", text: JSON.stringify(res.rows) }] };
  });

  server.tool("delete", { key: z.string() }, async ({ key }) => {
    await pool.query("DELETE FROM memory WHERE key = $1", [key]);
    return { content: [{ type: "text", text: `Deleted: ${key}` }] };
  });

  return server;
}

const httpServer = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/sse") {
    const transport = new SSEServerTransport("/messages", res);
    transports[transport.sessionId] = transport;
    req.on("close", () => delete transports[transport.sessionId]);
    const server = createServer();
    await server.connect(transport);
  } else if (req.method === "POST" && req.url?.startsWith("/messages")) {
    const sessionId = new URL(req.url, "http://localhost").searchParams.get("sessionId");
    const transport = transports[sessionId];
    if (!transport) { res.writeHead(404); res.end("Session not found"); return; }
    let body = "";
    req.on("data", chunk => body += chunk);
    req.on("end", async () => {
      try {
        req.body = JSON.parse(body);
        await transport.handlePostMessage(req, res);
      } catch (err) {
        console.error("Failed to handle POST message:", err.message);
        if (!res.headersSent) { res.writeHead(400); res.end("Invalid request"); }
      }
    });
  } else if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200);
    res.end("OK");
  } else {
    res.writeHead(404);
    res.end("Not found");
  }
});

httpServer.listen(config.server.port, config.server.host, () => {
  console.log(`MCP Server running on ${config.server.host}:${config.server.port}`);
});

function shutdown(signal) {
  console.log(`Received ${signal}, shutting down...`);
  httpServer.close(() => {
    pool.end().finally(() => process.exit(0));
  });
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

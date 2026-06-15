import pg from "pg";
import { config } from "./config.mjs";

// Single shared connection pool used by both the MCP server and the sync worker.
export const pool = new pg.Pool(config.postgres);

pool.on("error", (err) => {
  console.error("Unexpected PostgreSQL pool error:", err.message);
});

export async function closePool() {
  await pool.end();
}

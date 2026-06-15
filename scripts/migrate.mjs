import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import { dirname, join } from "path";
import { pool, closePool } from "../src/db.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const schemaPath = join(__dirname, "..", "db", "schema.sql");

async function migrate() {
  const sql = readFileSync(schemaPath, "utf8");
  console.log(`Applying schema from ${schemaPath} ...`);
  await pool.query(sql);
  console.log("Schema applied successfully.");
}

migrate()
  .then(() => closePool())
  .catch(async (err) => {
    console.error("Migration failed:", err.message);
    await closePool();
    process.exit(1);
  });

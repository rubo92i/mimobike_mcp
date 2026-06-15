import dotenv from "dotenv";

dotenv.config();

function required(name) {
  const value = process.env[name];
  if (value === undefined || value === "") {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function optional(name, fallback) {
  const value = process.env[name];
  return value === undefined || value === "" ? fallback : value;
}

export const config = {
  postgres: {
    host: optional("PG_HOST", "localhost"),
    port: Number(optional("PG_PORT", "5432")),
    database: required("PG_DATABASE"),
    user: required("PG_USER"),
    password: required("PG_PASSWORD"),
  },
  mongo: {
    uri: required("MONGO_URI"),
    databases: {
      ev: optional("MONGO_DB_EV", "ev_charger"),
      charger: optional("MONGO_DB_CHARGER", "charger"),
      scooter: optional("MONGO_DB_SCOOTER", "scooter"),
      sharing: optional("MONGO_DB_SHARING", "sharing"),
    },
  },
  server: {
    port: Number(optional("MCP_PORT", "3100")),
    host: optional("MCP_HOST", "0.0.0.0"),
  },
};

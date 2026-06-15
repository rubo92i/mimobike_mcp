-- Schema for the mimobike MCP server and sync worker.
-- Safe to run repeatedly (idempotent).

-- Generic key/value memory store used by the MCP tools and for
-- powerbank stations, scooters and bikes in the sync worker.
CREATE TABLE IF NOT EXISTS memory (
    key        TEXT PRIMARY KEY,
    content    TEXT NOT NULL,
    category   TEXT NOT NULL DEFAULT 'general',
    metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_category   ON memory (category);
CREATE INDEX IF NOT EXISTS idx_memory_updated_at ON memory (updated_at DESC);

-- EV charging locations synced from MongoDB (ev_charger.location).
CREATE TABLE IF NOT EXISTS locations (
    id              TEXT PRIMARY KEY,
    status          TEXT,
    country_code    TEXT,
    parking_type    TEXT,
    translations    JSONB NOT NULL DEFAULT '[]'::jsonb,
    facilities      JSONB NOT NULL DEFAULT '[]'::jsonb,
    stations        JSONB NOT NULL DEFAULT '[]'::jsonb,
    coordinates     JSONB NOT NULL DEFAULT '{}'::jsonb,
    working_hours   TEXT,
    charging_types  JSONB NOT NULL DEFAULT '[]'::jsonb,
    connector_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    power_kwts      JSONB NOT NULL DEFAULT '[]'::jsonb,
    logo            JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw             JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_locations_status       ON locations (status);
CREATE INDEX IF NOT EXISTS idx_locations_country_code ON locations (country_code);

-- EV stations synced from MongoDB (ev_charger.station).
CREATE TABLE IF NOT EXISTS ev_stations (
    id               TEXT PRIMARY KEY,
    status           TEXT,
    connected_online BOOLEAN NOT NULL DEFAULT FALSE,
    firmware_version TEXT,
    model            TEXT,
    protocol         TEXT,
    vendor           TEXT,
    state            TEXT,
    connectors       JSONB NOT NULL DEFAULT '[]'::jsonb,
    location         JSONB NOT NULL DEFAULT '{}'::jsonb,
    translations     JSONB NOT NULL DEFAULT '[]'::jsonb,
    working_hours    TEXT,
    charging_type    TEXT,
    logo             JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw              JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ev_stations_status ON ev_stations (status);
CREATE INDEX IF NOT EXISTS idx_ev_stations_online ON ev_stations (connected_online);

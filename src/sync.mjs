import { MongoClient } from "mongodb";
import { config } from "./config.mjs";
import { pool } from "./db.mjs";

// ── Upsert functions ──────────────────────────────────────────────

async function upsertLocation(doc) {
  await pool.query(`
    INSERT INTO locations (id, status, country_code, parking_type, translations, facilities, stations, coordinates, working_hours, charging_types, connector_types, power_kwts, logo, raw, updated_at)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,NOW())
    ON CONFLICT (id) DO UPDATE SET
      status=$2, country_code=$3, parking_type=$4, translations=$5, facilities=$6,
      stations=$7, coordinates=$8, working_hours=$9, charging_types=$10,
      connector_types=$11, power_kwts=$12, logo=$13, raw=$14, updated_at=NOW()
  `, [
    doc._id, doc.status, doc.countryCode, doc.parkingType,
    JSON.stringify(doc.translations || []), JSON.stringify(doc.facilities || []),
    JSON.stringify(doc.stations || []), JSON.stringify(doc.coordinates || {}),
    doc.workingHours, JSON.stringify(doc.chargingTypes || []),
    JSON.stringify(doc.connectorTypes || []), JSON.stringify(doc.powerKwts || []),
    JSON.stringify(doc.logo || {}), JSON.stringify(doc),
  ]);
}

async function upsertEvStation(doc) {
  await pool.query(`
    INSERT INTO ev_stations (id, status, connected_online, firmware_version, model, protocol, vendor, state, connectors, location, translations, working_hours, charging_type, logo, raw, updated_at)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,NOW())
    ON CONFLICT (id) DO UPDATE SET
      status=$2, connected_online=$3, firmware_version=$4, model=$5, protocol=$6,
      vendor=$7, state=$8, connectors=$9, location=$10, translations=$11,
      working_hours=$12, charging_type=$13, logo=$14, raw=$15, updated_at=NOW()
  `, [
    doc._id, doc.status, doc.connected?.online || false,
    doc.firmwareVersion, doc.model, doc.protocol, doc.vendor, doc.state,
    JSON.stringify(doc.connectors || []), JSON.stringify(doc.location || {}),
    JSON.stringify(doc.translations || []), doc.workingHours, doc.chargingType,
    JSON.stringify(doc.logo || {}), JSON.stringify(doc),
  ]);
}

async function upsertPowerBankStation(doc) {
  await pool.query(`
    INSERT INTO memory (key, content, category, metadata, updated_at)
    VALUES ($1,$2,'powerbank_station',$3,NOW())
    ON CONFLICT (key) DO UPDATE SET
      content=$2, category='powerbank_station', metadata=$3, updated_at=NOW()
  `, [
    `pb_station:${doc._id}`,
    `Power bank station ${doc._id} at ${doc.translations?.[0]?.destinationName || ''} ${doc.translations?.[0]?.destinationAddress || ''}. Status: ${doc.status}. Slots: ${doc.slotsCount}. Power banks: ${doc.powerBanksCount}. Online: ${doc.connected?.online}`,
    JSON.stringify(doc),
  ]);
}

async function upsertScooter(doc) {
  await pool.query(`
    INSERT INTO memory (key, content, category, metadata, updated_at)
    VALUES ($1,$2,'scooter',$3,NOW())
    ON CONFLICT (key) DO UPDATE SET
      content=$2, category='scooter', metadata=$3, updated_at=NOW()
  `, [
    `scooter:${doc._id}`,
    `Scooter ${doc._id}. Status: ${doc.status}. Battery: ${doc.battery || 'N/A'}%`,
    JSON.stringify(doc),
  ]);
}

async function upsertBike(doc) {
  await pool.query(`
    INSERT INTO memory (key, content, category, metadata, updated_at)
    VALUES ($1,$2,'bike',$3,NOW())
    ON CONFLICT (key) DO UPDATE SET
      content=$2, category='bike', metadata=$3, updated_at=NOW()
  `, [
    `bike:${doc._id}`,
    `Bike ${doc._id}. Status: ${doc.status}`,
    JSON.stringify(doc),
  ]);
}

// ── Watch helper ──────────────────────────────────────────────────

async function watchCollection(collection, name, upsertFn, deleteKey) {
  const changeStream = collection.watch([], { fullDocument: "updateLookup" });
  console.log(`Watching ${name}...`);
  changeStream.on("change", async (change) => {
    try {
      if (["insert", "update", "replace"].includes(change.operationType)) {
        await upsertFn(change.fullDocument);
        console.log(`[${name}] upserted: ${change.fullDocument._id}`);
      } else if (change.operationType === "delete") {
        await deleteKey(change.documentKey._id);
        console.log(`[${name}] deleted: ${change.documentKey._id}`);
      }
    } catch (err) {
      console.error(`[${name}] error:`, err.message);
    }
  });
  changeStream.on("error", (err) => {
    console.error(`[${name}] change stream error:`, err.message);
  });
  return changeStream;
}

// ── Main ──────────────────────────────────────────────────────────

async function main() {
  const client = new MongoClient(config.mongo.uri);
  await client.connect();
  console.log("Connected to MongoDB");

  const evDb      = client.db(config.mongo.databases.ev);
  const chargerDb = client.db(config.mongo.databases.charger);
  const scooterDb = client.db(config.mongo.databases.scooter);
  const sharingDb = client.db(config.mongo.databases.sharing);

  console.log("Starting initial sync...");

  const locations = await evDb.collection("location").find({}).toArray();
  for (const doc of locations) await upsertLocation(doc);
  console.log(`Synced ${locations.length} locations`);

  const evStations = await evDb.collection("station").find({}).toArray();
  for (const doc of evStations) await upsertEvStation(doc);
  console.log(`Synced ${evStations.length} ev stations`);

  const pbStations = await chargerDb.collection("station").find({}).toArray();
  for (const doc of pbStations) await upsertPowerBankStation(doc);
  console.log(`Synced ${pbStations.length} powerbank stations`);

  const scooters = await scooterDb.collection("scooter").find({}).toArray();
  for (const doc of scooters) await upsertScooter(doc);
  console.log(`Synced ${scooters.length} scooters`);

  const bikes = await sharingDb.collection("bike").find({}).toArray();
  for (const doc of bikes) await upsertBike(doc);
  console.log(`Synced ${bikes.length} bikes`);

  // Realtime watch
  await watchCollection(evDb.collection("location"), "ev_charger.location", upsertLocation,
    async (id) => pool.query("DELETE FROM locations WHERE id=$1", [id]));

  await watchCollection(evDb.collection("station"), "ev_charger.station", upsertEvStation,
    async (id) => pool.query("DELETE FROM ev_stations WHERE id=$1", [id]));

  await watchCollection(chargerDb.collection("station"), "charger.station", upsertPowerBankStation,
    async (id) => pool.query("DELETE FROM memory WHERE key=$1", [`pb_station:${id}`]));

  await watchCollection(scooterDb.collection("scooter"), "scooter.scooter", upsertScooter,
    async (id) => pool.query("DELETE FROM memory WHERE key=$1", [`scooter:${id}`]));

  await watchCollection(sharingDb.collection("bike"), "sharing.bike", upsertBike,
    async (id) => pool.query("DELETE FROM memory WHERE key=$1", [`bike:${id}`]));

  console.log("Sync running...");

  const shutdown = async (signal) => {
    console.log(`Received ${signal}, shutting down...`);
    try {
      await client.close();
      await pool.end();
    } finally {
      process.exit(0);
    }
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch((err) => {
  console.error("Fatal sync error:", err);
  process.exit(1);
});

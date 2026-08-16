import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import { parseDacRates, parseDomesticBlocks } from "../src/cfe-tariff-source.mjs";
import { SolarResourceService } from "../src/solar-resource.mjs";
import { buildRequestHandler } from "../src/server.mjs";

const domesticHtml = `<table class='table'><tr><td><b>Consumo básico</b></td><td>1.132</td><td>por cada uno de los primeros 75 kilowatts-hora.</td></tr><tr><td><b>Consumo intermedio</b></td><td>1.377</td><td>por cada uno de los siguientes 65 kilowatts-hora.</td></tr><tr><td><b>Consumo excedente</b></td><td>4.028</td><td>por cada kilowatt-hora adicional.</td></tr></table>`;

test("CFE domestic parser preserves progressive limits and current rates", () => {
  assert.deepEqual(parseDomesticBlocks(domesticHtml), [
    { order: 0, label: "Consumo básico", limitKwh: 75, rateMxnPerKwh: 1.132 },
    { order: 1, label: "Consumo intermedio", limitKwh: 140, rateMxnPerKwh: 1.377 },
    { order: 2, label: "Consumo excedente", limitKwh: null, rateMxnPerKwh: 4.028 },
  ]);
});

test("CFE DAC parser keeps every official region and both seasons", () => {
  const seasonalRows = ["Baja California", "Baja California Sur"]
    .map((region) => `<tr><td>${region}</td><td>145.04</td><td>$ 6.447</td><td>$ 5.536</td></tr>`).join("");
  const regularRows = ["Central", "Noroeste", "Norte y Noreste", "Sur y Peninsular"]
    .map((region) => `<tr><td>${region}</td><td>145.04</td><td>$ 6.148</td></tr>`).join("");
  const html = `<table ID='TarifaDacFV'><tr></tr><tr></tr>${seasonalRows}</table><table ID='TarifaDacV'><tr></tr><tr></tr>${regularRows}</table>`;
  const rates = parseDacRates(html);
  assert.equal(rates.length, 6);
  assert.equal(rates[0].fixedChargeMxn, 145.04);
  assert.equal(rates[0].outOfSummerRateMxnPerKwh, 5.536);
});

test("solar resource normalizes official NASA POWER monthly values and caches them", async () => {
  let calls = 0;
  const service = new SolarResourceService({
    fetchImpl: async () => {
      calls++;
      return new Response(JSON.stringify({
        properties: { parameter: { ALLSKY_SFC_SW_DWN: Object.fromEntries(
          ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
            .map((month, index) => [month, 5 + index / 10]),
        ) } },
      }), { status: 200, headers: { "content-type": "application/json" } });
    },
    now: () => 1_000,
  });
  const first = await service.forPoint(19.4326, -99.1332);
  const second = await service.forPoint(19.4326, -99.1332);
  assert.equal(first.monthlyDailyAverage.length, 12);
  assert.equal(first.source.name, "NASA POWER");
  assert.deepEqual(first, second);
  assert.equal(calls, 1);
});

test("public tariff and solar endpoints expose only normalized data", async () => {
  const logs = [];
  const requestedPoints = [];
  const logger = { info(entry) { logs.push(entry); }, error() {} };
  const catalog = { schemaVersion: 1, effectiveYearMonth: "2026-08", domestic: [], dac: [] };
  const handler = buildRequestHandler({
    logger,
    tariffCatalog: { current: () => catalog },
    solarResource: { forPoint: async (latitude, longitude) => {
      requestedPoints.push([latitude, longitude]);
      return { schemaVersion: 1, monthlyDailyAverage: Array(12).fill(5) };
    } },
  });
  const server = createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const tariffResponse = await fetch(`${base}/v1/tariffs/current`);
    assert.equal(tariffResponse.status, 200);
    assert.deepEqual(await tariffResponse.json(), catalog);
    const solarResponse = await fetch(`${base}/v1/solar/resource/19.43/-99.13`);
    assert.equal(solarResponse.status, 200);
    assert.equal((await solarResponse.json()).monthlyDailyAverage.length, 12);
    assert.equal(logs.at(-1).path, "/v1/solar/resource/:latitude/:longitude");

    const localizedSolarResponse = await fetch(`${base}/v1/solar/resource/19,43/-99,13`);
    assert.equal(localizedSolarResponse.status, 200);
    await localizedSolarResponse.json();
    assert.deepEqual(requestedPoints.at(-1), [19.43, -99.13]);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});

test("solar cache stays bounded and keeps the most recently used point", async () => {
  let upstreamCalls = 0;
  const fetchImpl = async () => {
    upstreamCalls += 1;
    return {
      ok: true,
      json: async () => ({
        properties: {
          parameter: {
            ALLSKY_SFC_SW_DWN: Object.fromEntries(
              ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
                .map((month) => [month, 5.5]),
            ),
          },
        },
      }),
    };
  };
  const service = new SolarResourceService({ fetchImpl, maxEntries: 4 });

  await service.forPoint(20.0, -89.0);
  for (let index = 1; index <= 8; index += 1) {
    await service.forPoint(20.0 + index, -89.0);
    // Keep the first point warm so it is not the least recently used one.
    await service.forPoint(20.0, -89.0);
  }

  assert.ok(service.cache.size <= 4, `cache grew to ${service.cache.size}`);
  // The repeatedly requested point was served from cache after its first fetch.
  assert.equal(upstreamCalls, 9);
});

test("invalid coordinates never reach the upstream provider", async () => {
  let called = false;
  const service = new SolarResourceService({ fetchImpl: async () => { called = true; } });
  await assert.rejects(() => service.forPoint(95, 0), /invalid_coordinates/);
  await assert.rejects(() => service.forPoint(0, 200), /invalid_coordinates/);
  await assert.rejects(() => service.forPoint(Number.NaN, 0), /invalid_coordinates/);
  assert.equal(called, false);
});

const BASE = "https://app.cfe.mx/Aplicaciones/CCFE/Tarifas/TarifasCREHogar/Tarifas";
const DOMESTIC_CODES = ["1", "1A", "1B", "1C", "1D", "1E", "1F"];

function decodeHtml(value) {
  return value
    .replaceAll("&nbsp;", " ")
    .replaceAll("&oacute;", "ó")
    .replaceAll("&aacute;", "á")
    .replaceAll("&eacute;", "é")
    .replaceAll("&iacute;", "í")
    .replaceAll("&uacute;", "ú")
    .replaceAll("&ntilde;", "ñ")
    .replaceAll("&amp;", "&")
    .replaceAll("&#39;", "'");
}

function text(value) {
  return decodeHtml(value.replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
}

function number(value) {
  const match = text(value).replaceAll(",", "").match(/-?\d+(?:\.\d+)?/);
  return match ? Number(match[0]) : null;
}

function hiddenFields(html) {
  const result = new URLSearchParams();
  for (const match of html.matchAll(/<input[^>]+type="hidden"[^>]+name="([^"]+)"[^>]+value="([^"]*)"/gi)) {
    result.set(match[1], decodeHtml(match[2]));
  }
  return result;
}

function selectValues(html, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const select = new RegExp(`<select[^>]+name="${escaped}"[^>]*>([\\s\\S]*?)<\\/select>`, "i").exec(html)?.[1] ?? "";
  return [...select.matchAll(/<option[^>]+value="(\d+)"/gi)].map((match) => Number(match[1])).filter(Boolean);
}

async function pageAndPostback(url, values, eventTarget, fetchImpl) {
  const initial = await fetchImpl(url, { signal: AbortSignal.timeout(12_000) });
  if (!initial.ok) throw new Error(`cfe_get_${initial.status}`);
  const initialHtml = await initial.text();
  const form = hiddenFields(initialHtml);
  form.set("__EVENTTARGET", eventTarget);
  for (const [key, value] of Object.entries(values)) form.set(key, String(value));
  const response = await fetchImpl(url, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: form,
    signal: AbortSignal.timeout(12_000),
  });
  if (!response.ok) throw new Error(`cfe_post_${response.status}`);
  return { initialHtml, html: await response.text() };
}

export function parseDomesticBlocks(html) {
  const rows = [];
  let cumulative = 0;
  const rowPattern = /<tr><td[^>]*><b>([\s\S]*?)<\/b><\/td><td[^>]*>([\s\S]*?)<\/td><td[^>]*>([\s\S]*?)<\/td><\/tr>/gi;
  for (const match of html.matchAll(rowPattern)) {
    const label = text(match[1]);
    if (!/consumo/i.test(label)) continue;
    const rate = number(match[2]);
    if (!(rate > 0)) continue;
    const description = text(match[3]);
    const quantity = number(/(?:primeros|siguientes)\s+([\d,]+)/i.exec(description)?.[1] ?? "");
    const openEnded = /adicional/i.test(description) || quantity == null;
    if (!openEnded) cumulative += quantity;
    rows.push({
      order: rows.length,
      label,
      limitKwh: openEnded ? null : cumulative,
      rateMxnPerKwh: rate,
    });
  }
  if (rows.length < 2 || rows.at(-1)?.limitKwh !== null) throw new Error("cfe_domestic_parse_failed");
  return rows;
}

export function parseDacRates(html) {
  const result = [];
  for (const tableMatch of html.matchAll(/<table[^>]+ID='(TarifaDacFV|TarifaDacV)'[^>]*>([\s\S]*?)<\/table>/gi)) {
    const seasonal = tableMatch[1] === "TarifaDacFV";
    const rows = [...tableMatch[2].matchAll(/<tr>([\s\S]*?)<\/tr>/gi)].slice(2);
    for (const row of rows) {
      const cells = [...row[1].matchAll(/<td[^>]*>([\s\S]*?)<\/td>/gi)].map((cell) => text(cell[1]));
      if (cells.length < 3) continue;
      const fixed = number(cells[1]);
      const summer = number(cells[2]);
      const outOfSummer = seasonal ? number(cells[3]) : summer;
      if (!(fixed > 0) || !(summer > 0) || !(outOfSummer > 0)) continue;
      result.push({
        region: cells[0],
        fixedChargeMxn: fixed,
        summerRateMxnPerKwh: summer,
        outOfSummerRateMxnPerKwh: outOfSummer,
      });
    }
  }
  if (result.length !== 6) throw new Error("cfe_dac_parse_failed");
  return result;
}

function isSummer(month, startMonth) {
  return Array.from({ length: 6 }, (_, index) => ((startMonth - 1 + index) % 12) + 1).includes(month);
}

async function fetchDomestic(code, month, fetchImpl) {
  const url = `${BASE}/Tarifa${code}.aspx`;
  if (code === "1") {
    const target = "ctl00$ContentPlaceHolder1$MesVerano1$ddMesConsulta";
    const { html } = await pageAndPostback(url, { [target]: month }, target, fetchImpl);
    return [{ tariffCode: code, summerStartMonth: null, season: "NONE", blocks: parseDomesticBlocks(html), sourceUrl: url }];
  }
  const summerField = "ctl00$ContentPlaceHolder1$MesVerano1$ddMesVerano";
  const monthField = "ctl00$ContentPlaceHolder1$MesVerano2$ddMesConsulta";
  const initial = await fetchImpl(url, { signal: AbortSignal.timeout(12_000) });
  if (!initial.ok) throw new Error(`cfe_get_${initial.status}`);
  const initialHtml = await initial.text();
  const starts = selectValues(initialHtml, summerField);
  return Promise.all(starts.map(async (summerStartMonth) => {
    const { html } = await pageAndPostback(
      url,
      { [summerField]: summerStartMonth, [monthField]: month },
      monthField,
      fetchImpl,
    );
    return {
      tariffCode: code,
      summerStartMonth,
      season: isSummer(month, summerStartMonth) ? "SUMMER" : "OUT_OF_SUMMER",
      blocks: parseDomesticBlocks(html),
      sourceUrl: url,
    };
  }));
}

async function fetchDac(month, fetchImpl) {
  const url = `${BASE}/TarifaDAC.aspx`;
  const target = "ctl00$ContentPlaceHolder1$Fecha1$ddMes";
  const { html } = await pageAndPostback(url, { [target]: month }, target, fetchImpl);
  return parseDacRates(html).map((rate) => ({ ...rate, sourceUrl: url }));
}

export async function fetchCurrentCfeCatalog({ fetchImpl = fetch, now = new Date() } = {}) {
  const mexicoParts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "America/Mexico_City",
      year: "numeric",
      month: "numeric",
    }).formatToParts(now).map(({ type, value }) => [type, value]),
  );
  const month = Number(mexicoParts.month);
  const year = Number(mexicoParts.year);
  const domesticNested = [];
  // Avoid bursting dozens of ASP.NET postbacks at the public CFE site.
  for (const code of DOMESTIC_CODES) domesticNested.push(await fetchDomestic(code, month, fetchImpl));
  const dac = await fetchDac(month, fetchImpl);
  return {
    schemaVersion: 1,
    effectiveYearMonth: `${year}-${String(month).padStart(2, "0")}`,
    fetchedAt: new Date().toISOString(),
    source: { name: "CFE", url: `${BASE}/TarifaDAC.aspx` },
    domestic: domesticNested.flat(),
    dac,
  };
}

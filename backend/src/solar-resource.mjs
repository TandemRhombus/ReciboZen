const NASA_POWER_URL = "https://power.larc.nasa.gov/api/temporal/climatology/point";
const CACHE_MS = 24 * 60 * 60 * 1000;
const MONTH_KEYS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

export class SolarResourceService {
  constructor({ fetchImpl = fetch, now = () => Date.now() } = {}) {
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.cache = new Map();
  }

  async forPoint(latitude, longitude) {
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
      !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
      throw new Error("invalid_coordinates");
    }
    const roundedLat = Math.round(latitude * 100) / 100;
    const roundedLon = Math.round(longitude * 100) / 100;
    const key = `${roundedLat},${roundedLon}`;
    const cached = this.cache.get(key);
    if (cached && this.now() - cached.cachedAt < CACHE_MS) return cached.value;

    const url = new URL(NASA_POWER_URL);
    url.searchParams.set("parameters", "ALLSKY_SFC_SW_DWN");
    url.searchParams.set("community", "RE");
    url.searchParams.set("longitude", String(roundedLon));
    url.searchParams.set("latitude", String(roundedLat));
    url.searchParams.set("format", "JSON");
    const response = await this.fetchImpl(url, { signal: AbortSignal.timeout(12_000) });
    if (!response.ok) throw new Error(`nasa_power_${response.status}`);
    const body = await response.json();
    const parameter = body?.properties?.parameter?.ALLSKY_SFC_SW_DWN;
    const months = MONTH_KEYS.map((key) => {
      const value = Number(parameter?.[key]);
      if (!Number.isFinite(value) || value <= 0) throw new Error("nasa_power_invalid_payload");
      return value;
    });
    const value = {
      schemaVersion: 1,
      latitude: roundedLat,
      longitude: roundedLon,
      parameter: "ALLSKY_SFC_SW_DWN",
      unit: "kWh/m2/day",
      monthlyDailyAverage: months,
      source: {
        name: "NASA POWER",
        url: NASA_POWER_URL,
      },
      fetchedAt: new Date(this.now()).toISOString(),
    };
    this.cache.set(key, { cachedAt: this.now(), value });
    return value;
  }
}

import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fetchCurrentCfeCatalog } from "./cfe-tariff-source.mjs";

const REFRESH_MS = 24 * 60 * 60 * 1000;

export class TariffCatalogService {
  constructor({
    storePath = "/var/lib/recibozen/tariff-catalog.json",
    fetchCatalog = fetchCurrentCfeCatalog,
    logger = console,
  } = {}) {
    this.storePath = storePath;
    this.fetchCatalog = fetchCatalog;
    this.logger = logger;
    this.snapshot = null;
    this.timer = null;
  }

  async initialize() {
    this.snapshot = await this.#readStored();
    this.refresh().catch((error) => {
      this.logger.error({ event: "tariff_refresh_failed", error: error.message });
    });
    this.timer = setInterval(() => {
      this.refresh().catch((error) => this.logger.error({ event: "tariff_refresh_failed", error: error.message }));
    }, REFRESH_MS);
    this.timer.unref();
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
  }

  current() {
    return this.snapshot;
  }

  async refresh() {
    const next = await this.fetchCatalog();
    const canonical = JSON.stringify(next);
    const snapshot = {
      ...next,
      sha256: createHash("sha256").update(canonical).digest("hex"),
    };
    await mkdir(dirname(this.storePath), { recursive: true });
    const temporary = `${this.storePath}.tmp`;
    await writeFile(temporary, `${JSON.stringify(snapshot)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.storePath);
    this.snapshot = snapshot;
    this.logger.info({ event: "tariff_refresh_complete", effectiveYearMonth: snapshot.effectiveYearMonth });
    return snapshot;
  }

  async #readStored() {
    try {
      const parsed = JSON.parse(await readFile(this.storePath, "utf8"));
      return parsed?.schemaVersion === 1 ? parsed : null;
    } catch {
      return null;
    }
  }
}

"""Build ReciboZen's compact, searchable ENERGY STAR model asset.

Usage:
    python tools/build_energy_star_catalog.py SOURCE.zip app/src/main/assets/catalog

The source archive remains the auditable master. The Android asset intentionally
contains only records whose source field is a real annual/TEC kWh value. Commercial
ice machines are excluded because their source metric is kWh per 100 lb of ice, not
kWh per year, despite the v1 master column name.
"""

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path


MASTER = "energy_device_dataset/master/devices_master.csv"
SOURCES = "energy_device_dataset/sources/sources.csv"
MANIFEST = "energy_device_dataset/manifest.json"
MISCLASSIFIED_SUBCATEGORY = "maquina_hielo_comercial"


def as_bool(value: str | None) -> bool:
    return str(value).strip().lower() == "true"


def as_float(value: str | None) -> float | None:
    try:
        parsed = float(str(value).strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def latest_row(rows: list[dict[str, str]]) -> dict[str, str]:
    return max(rows, key=lambda row: (row.get("date_certified", ""), row.get("device_id", "")))


def main(source_zip: Path, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    source_sha256 = hashlib.sha256(source_zip.read_bytes()).hexdigest()

    with zipfile.ZipFile(source_zip) as archive:
        manifest = json.loads(archive.read(MANIFEST))
        source_rows = csv.DictReader(io.TextIOWrapper(archive.open(SOURCES), encoding="utf-8-sig", newline=""))
        source_urls = {row["source_id"]: row["source_url"] for row in source_rows}
        master_rows = csv.DictReader(io.TextIOWrapper(archive.open(MASTER), encoding="utf-8-sig", newline=""))

        eligible: list[dict[str, str]] = []
        input_rows = 0
        excluded_bad_metric = 0
        excluded_incomplete = 0
        for row in master_rows:
            input_rows += 1
            if row.get("subcategory") == MISCLASSIFIED_SUBCATEGORY and row.get("annual_kwh"):
                excluded_bad_metric += 1
                continue
            if not as_bool(row.get("technical_data_valid")):
                continue
            if as_float(row.get("annual_kwh")) is None:
                continue
            if not row.get("brand_normalized") or not row.get("model_number_norm"):
                excluded_incomplete += 1
                continue
            eligible.append(row)

    grouped: dict[tuple[str, str, str, str], list[dict[str, str]]] = defaultdict(list)
    for row in eligible:
        key = (
            row["category"],
            row["subcategory"],
            row["brand_normalized"],
            row["model_number_norm"],
        )
        grouped[key].append(row)

    records: list[dict[str, object]] = []
    duplicate_groups = 0
    conflicting_energy_groups = 0
    for rows in grouped.values():
        chosen = latest_row(rows)
        annual_values = sorted({as_float(row.get("annual_kwh")) for row in rows} - {None})
        if len(rows) > 1:
            duplicate_groups += 1
        if len(annual_values) > 1:
            conflicting_energy_groups += 1
        records.append(
            {
                "id": chosen["device_id"],
                "category": chosen["category"],
                "subcategory": chosen["subcategory"],
                "brand": chosen["brand_raw"].strip(),
                "model": chosen["model_number_raw"].strip(),
                "annualKwh": as_float(chosen["annual_kwh"]),
                "annualKwhMin": annual_values[0],
                "annualKwhMax": annual_values[-1],
                "capacityValue": as_float(chosen.get("capacity_value")),
                "capacityUnit": chosen.get("capacity_unit") or None,
                "modelFamily": any(as_bool(row.get("is_model_family")) for row in rows),
                "marketMexicoVerified": any(as_bool(row.get("market_mexico_verified")) for row in rows),
                "sourceDatasetId": chosen["source_dataset_id"],
                "sourceUrl": source_urls.get(chosen["source_dataset_id"]),
                "certifiedDate": chosen.get("date_certified") or None,
                "markets": chosen.get("markets_raw") or None,
                "certificationCount": len(rows),
            }
        )

    records.sort(key=lambda item: (str(item["brand"]).casefold(), str(item["model"]).casefold(), str(item["id"])))
    # AAPT expands and strips a trailing .gz at package time. Keep a neutral
    # suffix so AssetManager exposes the same gzip bytes and name at runtime.
    asset_path = output_dir / "energy_star_models_v1.jsonl.gzip"
    with asset_path.open("wb") as binary:
        with gzip.GzipFile(filename="", mode="wb", fileobj=binary, compresslevel=9, mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="\n") as text:
                for record in records:
                    text.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")

    metadata = {
        "schemaVersion": 1,
        "datasetVersion": manifest["dataset_version"],
        "sourceName": "ENERGY STAR certified products",
        "sourceArchiveSha256": source_sha256,
        "sourceRows": input_rows,
        "eligibleRowsBeforeDeduplication": len(eligible),
        "searchableModels": len(records),
        "duplicateModelGroupsCollapsed": duplicate_groups,
        "groupsWithAnnualKwhRange": conflicting_energy_groups,
        "excludedMisclassifiedIceMachineRows": excluded_bad_metric,
        "excludedIncompleteRows": excluded_incomplete,
        "marketMexicoVerifiedModels": sum(bool(record["marketMexicoVerified"]) for record in records),
        "license": "U.S. federal government public data; ENERGY STAR marks/logos not included",
    }
    metadata_path = output_dir / "energy_star_models_v1.metadata.json"
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({**metadata, "assetBytes": asset_path.stat().st_size}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Expected SOURCE.zip and OUTPUT_DIR")
    main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())

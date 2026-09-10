"""Stage 1/2 ZIP integrity validator, including scan freshness and observation-time GPS joins."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timedelta
import hashlib
import io
import json
import math
from pathlib import Path
import re
import zipfile

VERSION = 1
REQUIRED = {"metadata.json", "connected_wifi.csv", "locations.csv", "events.csv", "track.geojson",
            "schema_version.txt", "checksums.sha256", "scan_snapshots.csv", "scan_results.csv", "indoor_anchors.csv"}
BSSID = re.compile(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\Z")
COMMON = {"id", "session_id", "sequence_no", "timestamp_utc", "timestamp_elapsed_ns"}
HEADERS = {
    "connected_wifi.csv": COMMON | {"source", "ssid", "bssid", "rssi_dbm", "frequency_mhz", "band", "channel", "channel_width",
        "rx_link_speed_mbps", "tx_link_speed_mbps", "wifi_standard", "network_transport_state", "location_id_at_capture",
        "location_age_ms", "location_accuracy_m_at_capture", "quality_flags"},
    "locations.csv": COMMON | {"latitude", "longitude", "accuracy_m", "altitude_m", "vertical_accuracy_m", "bearing_deg",
        "bearing_accuracy_deg", "speed_mps", "speed_accuracy_mps", "provider", "is_mock_if_available"},
    "events.csv": COMMON | {"type", "payload_json"},
    "scan_snapshots.csv": {"session_id", "snapshot_id", "request_elapsed_ns", "callback_elapsed_ns", "callback_utc", "request_accepted", "results_updated", "result_count"},
    "scan_results.csv": {"id", "session_id", "snapshot_id", "source", "platform_seen_elapsed_us", "fresh", "quality_flags"},
    "indoor_anchors.csv": {"id", "session_id", "timestamp_utc", "timestamp_elapsed_ns", "floorplan_id", "x_m", "y_m", "x_normalized", "y_normalized", "label"},
}


def frequency_channel(frequency: int):
    if frequency == 2484:
        return "2.4GHz", 14
    if 2412 <= frequency <= 2472 and (frequency - 2407) % 5 == 0:
        return "2.4GHz", (frequency - 2407) // 5
    if 4910 <= frequency <= 4980 and frequency % 5 == 0:
        return "5GHz", (frequency - 4000) // 5
    if 5005 <= frequency <= 5895 and frequency % 5 == 0:
        return "5GHz", (frequency - 5000) // 5
    if frequency == 5935:
        return "6GHz", 2
    if 5955 <= frequency <= 7115 and (frequency - 5950) % 5 == 0:
        return "6GHz", (frequency - 5950) // 5
    return None, None


def utc(value: str):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.utcoffset() != timedelta(0):
        raise ValueError("UTC_REQUIRED")
    return result


def finite(value: str) -> float:
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("NON_FINITE_NUMBER")
    return number


def validate_scans(rows, metadata, locations, errors, metrics):
    config = metadata["configuration_snapshot"]
    max_age = finite(str(config.get("max_scan_age_ms", 5000)))
    if not 0 <= max_age <= 60000 or int(config.get("scan_interval_ms", 30000)) < 5000:
        raise ValueError("INVALID_SCAN_CONFIGURATION")
    start, end = int(metadata["started_elapsed_ns"]), int(metadata["ended_elapsed_ns"])
    snapshots, counts, seen, ids = {}, {}, {}, set()
    for snapshot in rows["scan_snapshots.csv"]:
        key = snapshot["snapshot_id"]
        callback = int(snapshot["callback_elapsed_ns"])
        utc(snapshot["callback_utc"])
        if key in snapshots or snapshot["session_id"] != metadata["id"] or not start <= callback <= end:
            errors.append("SCAN_SNAPSHOT_IDENTITY_OR_TIME_INVALID")
        if snapshot["results_updated"] not in ("true", "false") or int(snapshot["result_count"]) < 0:
            errors.append("SCAN_SNAPSHOT_FIELDS_INVALID")
        request, accepted = snapshot["request_elapsed_ns"], snapshot["request_accepted"]
        if bool(request) != bool(accepted) or accepted not in ("", "true", "false"):
            errors.append("SCAN_REQUEST_INVALID")
        if request and not start <= int(request) <= callback:
            errors.append("SCAN_REQUEST_TIME_INVALID")
        snapshots[key] = snapshot
    fresh_count = located_count = 0
    for row in rows["scan_results.csv"]:
        if row["id"] in ids or row["session_id"] != metadata["id"] or row["source"] != "SCAN_RESULT":
            errors.append("SCAN_RESULT_IDENTITY_INVALID")
        ids.add(row["id"])
        snapshot = snapshots.get(row["snapshot_id"])
        if snapshot is None:
            errors.append("SCAN_SNAPSHOT_FOREIGN_KEY_MISSING")
            continue
        counts[row["snapshot_id"]] = counts.get(row["snapshot_id"], 0) + 1
        stamp_us = int(row["platform_seen_elapsed_us"])
        elapsed = stamp_us * 1000 if 1 <= stamp_us <= (2**63 - 1) // 1000 else 0
        age = (int(snapshot["callback_elapsed_ns"]) - elapsed) / 1e6
        if not math.isclose(age, finite(row["result_age_at_callback_ms"]), abs_tol=.001):
            errors.append("SCAN_AGE_MISMATCH")
        if row["fresh"] not in ("true", "false") or row["rtt_responder"] not in ("true", "false"):
            errors.append("SCAN_BOOLEAN_INVALID")
        flags = set(row["quality_flags"].split("|"))
        key = row["bssid"], row["frequency_mhz"]
        if row["bssid"] and not BSSID.fullmatch(row["bssid"]):
            errors.append("SCAN_BSSID_INVALID")
        if not -126 <= int(row["rssi_dbm"]) <= 0 and "RSSI_INVALID" not in flags:
            errors.append("SCAN_RSSI_INVALID")
        band, channel = frequency_channel(int(row["frequency_mhz"]))
        if band is not None and (row["band"] != band or row["channel"] != str(channel)):
            errors.append("SCAN_FREQUENCY_BAND_CHANNEL_MISMATCH")
        if row["fresh"] == "true":
            fresh_count += 1
            if not (snapshot["results_updated"] == "true" and elapsed >= start and 0 <= age <= max_age and stamp_us > seen.get(key, 0)):
                errors.append("STALE_OR_DUPLICATE_SCAN_MARKED_FRESH")
        elif "SCAN_NOT_FRESH" not in flags:
            errors.append("STALE_SCAN_FLAG_MISSING")
        if elapsed > 0 and age >= 0:
            seen[key] = max(seen.get(key, 0), stamp_us)
        location_id = row["location_id_for_seen_time"]
        if location_id:
            location = locations.get(location_id)
            if location is None:
                errors.append("SCAN_LOCATION_FOREIGN_KEY_MISSING")
                continue
            delta = (int(location["timestamp_elapsed_ns"]) - elapsed) / 1e6
            if row["fresh"] != "true" or abs(delta) > config["max_location_age_ms"] or int(location["timestamp_elapsed_ns"]) > int(snapshot["callback_elapsed_ns"]):
                errors.append("SCAN_LOCATION_JOIN_OUTSIDE_WINDOW")
            if not row["location_join_delta_ms"] or not math.isclose(finite(row["location_join_delta_ms"]), delta, abs_tol=.001):
                errors.append("SCAN_LOCATION_DELTA_MISMATCH")
            if "UNLOCATED" in flags:
                errors.append("SCAN_LOCATION_FLAGS_CONTRADICTORY")
            located_count += 1
        elif row["location_join_delta_ms"] or "UNLOCATED" not in flags:
            errors.append("SCAN_UNLOCATED_INVALID")
    if any(int(s["result_count"]) != counts.get(key, 0) for key, s in snapshots.items()):
        errors.append("SCAN_RESULT_COUNT_MISMATCH")
    for key in ("scan_snapshots", "scan_results"):
        if metadata["counts"].get(key) != len(rows[key + ".csv"]):
            errors.append("METADATA_COUNT_MISMATCH:" + key)
    metrics.update(fresh_scan_count=fresh_count, located_scan_count=located_count)


def validate(path: Path) -> dict:
    result = {"validator_version": "0.3.0", "status": "FAIL", "errors": [], "warnings": [], "metrics": {}}
    errors, warnings, metrics = result["errors"], result["warnings"], result["metrics"]
    try:
        with path.open("rb") as stream:
            result["input_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise ValueError("DUPLICATE_ZIP_ENTRY")
            if set(names) != REQUIRED:
                raise ValueError("ZIP_ENTRY_SET_MISMATCH")
            if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
                raise ValueError("ZIP_UNCOMPRESSED_LIMIT_512_MIB")
            files = {name: archive.read(name) for name in names}
        manifest = {}
        for line in files["checksums.sha256"].decode("ascii").splitlines():
            digest, name = line.split("  ", 1)
            if not re.fullmatch(r"[0-9a-f]{64}", digest) or name in manifest:
                raise ValueError("CHECKSUM_MANIFEST_INVALID")
            manifest[name] = digest
        if set(manifest) != REQUIRED - {"checksums.sha256"}:
            raise ValueError("CHECKSUM_COVERAGE_MISMATCH")
        for name, digest in manifest.items():
            if hashlib.sha256(files[name]).hexdigest() != digest:
                errors.append(f"CHECKSUM_MISMATCH:{name}")
        if files["schema_version.txt"].decode().strip() != str(VERSION):
            raise ValueError("UNSUPPORTED_SCHEMA_VERSION")
        metadata = json.loads(files["metadata.json"])
        if metadata["schema_version"] != VERSION:
            raise ValueError("METADATA_SCHEMA_MISMATCH")
        if metadata.get("measurement_source") != "CONNECTED_LINK" or metadata.get("collector_stage") not in (1, 2):
            raise ValueError("UNSUPPORTED_COLLECTOR_STAGE_OR_SOURCE")
        if metadata["status"] not in {"COMPLETED", "INTERRUPTED", "EXPORT_FAILED"}:
            raise ValueError("SESSION_NOT_ENDED")
        result["session_id"] = metadata["id"]
        utc(metadata["started_at_utc"])
        utc(metadata["ended_at_utc"])
        duration_ns = int(metadata["ended_elapsed_ns"]) - int(metadata["started_elapsed_ns"])
        if duration_ns < 0:
            raise ValueError("NEGATIVE_SESSION_DURATION")
        metrics["duration_seconds"] = duration_ns / 1e9
        config = metadata["configuration_snapshot"]
        scan_enabled = config.get("scan_collection_enabled", False)
        if metadata["collector_stage"] != (2 if scan_enabled else 1):
            raise ValueError("SCAN_CONFIGURATION_STAGE_MISMATCH")
        if config != json.loads(metadata["configuration_snapshot_json"]):
            raise ValueError("CONFIGURATION_SNAPSHOT_MISMATCH")
        max_age = finite(str(config["max_location_age_ms"]))
        interval = finite(str(config["connected_interval_ms"]))
        if not 0 <= max_age <= 60000 or not 500 <= interval <= 10000:
            raise ValueError("INVALID_CONFIGURATION")
        rows = {}
        for name, expected in HEADERS.items():
            reader = csv.DictReader(io.StringIO(files[name].decode("utf-8"), newline=""))
            if not expected.issubset(set(reader.fieldnames or [])) or len(reader.fieldnames or []) != len(set(reader.fieldnames or [])):
                raise ValueError(f"CSV_HEADER_INVALID:{name}")
            rows[name] = list(reader)
            if any(None in row or any(value is None for value in row.values()) for row in rows[name]):
                raise ValueError(f"CSV_ROW_WIDTH_INVALID:{name}")
            metrics[name.removesuffix(".csv") + "_count"] = len(rows[name])
        for name in (("indoor_anchors.csv",) if scan_enabled else ("scan_snapshots.csv", "scan_results.csv", "indoor_anchors.csv")):
            if rows[name]:
                errors.append(f"UNEXPECTED_STAGE_1_STREAM:{name}")
        for name in ("connected_wifi.csv", "locations.csv", "events.csv"):
            last_seq, last_elapsed = 0, -1
            ids = set()
            for row in rows[name]:
                sequence, elapsed = int(row["sequence_no"]), int(row["timestamp_elapsed_ns"])
                utc(row["timestamp_utc"])
                if sequence != last_seq + 1:
                    errors.append(f"SEQUENCE_NOT_CONTIGUOUS:{name}:{sequence}")
                if elapsed < last_elapsed or elapsed < 0:
                    errors.append(f"ELAPSED_NOT_MONOTONIC:{name}:{sequence}")
                if name != "locations.csv" and not int(metadata["started_elapsed_ns"]) <= elapsed <= int(metadata["ended_elapsed_ns"]):
                    errors.append(f"RECORD_OUTSIDE_SESSION:{name}:{sequence}")
                if row["session_id"] != metadata["id"] or row["id"] in ids:
                    errors.append(f"IDENTITY_INVALID:{name}:{sequence}")
                ids.add(row["id"])
                last_seq, last_elapsed = sequence, elapsed
        locations = {row["id"]: row for row in rows["locations.csv"]}
        for row in locations.values():
            if not -90 <= finite(row["latitude"]) <= 90 or not -180 <= finite(row["longitude"]) <= 180:
                errors.append("LOCATION_COORDINATES_INVALID")
            if row["accuracy_m"] and finite(row["accuracy_m"]) < 0:
                errors.append("LOCATION_ACCURACY_INVALID")
        if scan_enabled:
            validate_scans(rows, metadata, locations, errors, metrics)
        bound = 0
        poor = sum(bool(row["accuracy_m"]) and finite(row["accuracy_m"]) > config["accuracy_exclusion_m"] for row in locations.values())
        deltas = []
        previous_time = None
        for row in rows["connected_wifi.csv"]:
            if row["source"] != "CONNECTED_LINK":
                errors.append("MIXED_MEASUREMENT_SOURCE")
            flags = set(row["quality_flags"].split("|"))
            if row["bssid"] and not BSSID.fullmatch(row["bssid"]):
                errors.append("BSSID_INVALID")
            if row["rssi_dbm"] and not -126 <= int(row["rssi_dbm"]) <= 0:
                (warnings if "RSSI_INVALID" in flags else errors).append("RSSI_OUT_OF_RANGE")
            if row["frequency_mhz"]:
                band, channel = frequency_channel(int(row["frequency_mhz"]))
                if band is None:
                    (warnings if "FREQUENCY_UNKNOWN" in flags else errors).append("FREQUENCY_UNKNOWN")
                elif row["band"] != band or row["channel"] != str(channel):
                    errors.append("FREQUENCY_BAND_CHANNEL_MISMATCH")
            elapsed = int(row["timestamp_elapsed_ns"])
            if previous_time is not None:
                deltas.append((elapsed - previous_time) / 1e6)
            previous_time = elapsed
            location_id = row["location_id_at_capture"]
            if location_id:
                location = locations.get(location_id)
                if location is None:
                    errors.append("LOCATION_FOREIGN_KEY_MISSING")
                    continue
                age = (elapsed - int(location["timestamp_elapsed_ns"])) / 1e6
                if not 0 <= age <= max_age:
                    errors.append("LOCATION_JOIN_OUTSIDE_WINDOW")
                if not row["location_age_ms"] or not math.isclose(finite(row["location_age_ms"]), age, abs_tol=0.000001):
                    errors.append("LOCATION_AGE_MISMATCH")
                if row["location_accuracy_m_at_capture"] != location["accuracy_m"]:
                    errors.append("LOCATION_ACCURACY_JOIN_MISMATCH")
                if "UNLOCATED" in flags:
                    errors.append("CONTRADICTORY_LOCATION_FLAGS")
                bound += 1
            elif "UNLOCATED" not in flags:
                errors.append("UNLOCATED_FLAG_MISSING")
        for row in rows["events.csv"]:
            payload = json.loads(row["payload_json"])
            if row["type"] == "BSSID_CHANGE":
                if not all(BSSID.fullmatch(payload[key]) for key in ("old_bssid", "new_bssid")):
                    errors.append("TRANSITION_BSSID_INVALID")
                if payload["old_bssid"] == payload["new_bssid"]:
                    errors.append("TRANSITION_NOT_A_CHANGE")
        count = len(rows["connected_wifi.csv"])
        metrics.update(valid_location_join_count=bound, located_fraction=bound / count if count else 0,
                       poor_accuracy_location_count=poor, sampling_gap_count=sum(delta > interval * 2.5 for delta in deltas),
                       max_sampling_gap_ms=max(deltas, default=0),
                       observed_connected_hz=1000 * len(deltas) / sum(deltas) if deltas and sum(deltas) > 0 else 0)
        if not count:
            warnings.append("NO_CONNECTED_SAMPLES")
        if bound == 0:
            warnings.append("NO_VALID_LOCATION_JOINS")
        if metrics["sampling_gap_count"]:
            warnings.append("CONNECTED_SAMPLING_GAPS")
        if poor:
            warnings.append("POOR_GPS_ACCURACY_RAW_RETAINED")
        if metadata["status"] == "INTERRUPTED":
            warnings.append("INTERRUPTED_SESSION")
        for key, actual in (("connected_wifi", count), ("locations", len(locations)), ("located_connected_wifi", bound)):
            if metadata["counts"][key] != actual:
                errors.append(f"METADATA_COUNT_MISMATCH:{key}")
        geo = json.loads(files["track.geojson"])
        if geo["type"] != "FeatureCollection" or len(geo["features"]) != len(locations):
            errors.append("GEOJSON_COUNT_OR_TYPE_INVALID")
        for feature in geo["features"]:
            location = locations.get(feature["id"])
            if location is None or feature["geometry"]["type"] != "Point" or feature["geometry"]["coordinates"] != [float(location["longitude"]), float(location["latitude"])]:
                errors.append("GEOJSON_LOCATION_MISMATCH")
    except (OSError, ValueError, KeyError, TypeError, UnicodeError, zipfile.BadZipFile, RuntimeError,
            csv.Error, IndexError, OverflowError, RecursionError) as failure:
        errors.append(str(failure) if isinstance(failure, ValueError) else type(failure).__name__)
    result["errors"] = list(dict.fromkeys(errors))
    result["warnings"] = list(dict.fromkeys(warnings))
    result["status"] = "FAIL" if errors else "PASS"
    return result


def write_reports(result: dict, output: Path):
    output.mkdir(parents=True, exist_ok=True)
    (output / "validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    text = f"# Survey format validation: {result['status']}\n\n"
    text += "A format PASS is not a physical-device acceptance or spatial-quality approval.\n\n"
    for group in ("errors", "warnings"):
        text += f"## {group.capitalize()}\n\n" + "\n".join(f"- {item}" for item in result[group]) + "\n\n"
    text += "## Metrics\n\n" + "\n".join(f"- {key}: {value}" for key, value in result["metrics"].items()) + "\n"
    (output / "validation.md").write_text(text, encoding="utf-8")


def main():
    from check_privacy import checked_private_path, PRIVATE_ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    parser.add_argument("--output", type=Path, default=PRIVATE_ROOT / "reports" / "validation")
    args = parser.parse_args()
    output = checked_private_path(args.output)
    result = validate(args.zip)
    write_reports(result, output)
    print(f"{result['status']}: {len(result['errors'])} errors, {len(result['warnings'])} warnings. Reports saved privately.")
    raise SystemExit(0 if result["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()

import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_privacy import checked_private_path, check_rules, PRIVATE_ROOT
from validate_survey import validate, write_reports

FIXTURE = Path(__file__).resolve().parents[1] / "testdata/synthetic"


def fixture_files():
    return {file.name: file.read_bytes() for file in FIXTURE.iterdir() if file.name in {
        "metadata.json", "connected_wifi.csv", "locations.csv", "events.csv", "track.geojson", "schema_version.txt",
        "scan_snapshots.csv", "scan_results.csv", "indoor_anchors.csv"}}


def package(files, path, hashes=True):
    if hashes:
        files = dict(files)
        files["checksums.sha256"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())).encode()
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def edit_csv(files, name, mutate):
    reader = csv.DictReader(io.StringIO(files[name].decode(), newline=""))
    rows = list(reader)
    mutate(rows)
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=reader.fieldnames, lineterminator="\r\n")
    writer.writeheader()
    writer.writerows(rows)
    files[name] = output.getvalue().encode()


class ValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "survey_synthetic.zip"
        self.files = fixture_files()

    def run_validation(self):
        package(self.files, self.path)
        return validate(self.path)

    def assert_invalid(self, token):
        result = self.run_validation()
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any(token in error for error in result["errors"]), result)

    def test_valid_export_and_multiline_unicode(self):
        result = self.run_validation()
        self.assertEqual("PASS", result["status"], result)
        self.assertEqual(4, result["metrics"]["connected_wifi_count"])
        self.assertIn("Zażółć", self.files["events.csv"].decode())
        self.assertIn("POOR_GPS_ACCURACY_RAW_RETAINED", result["warnings"])
        self.assertIn("CONNECTED_SAMPLING_GAPS", result["warnings"])

    def test_corrupt_zip_and_failure_reports(self):
        self.path.write_bytes(b"invalid zip")
        result = validate(self.path)
        self.assertEqual("FAIL", result["status"])
        output = Path(self.directory.name) / "reports"
        write_reports(result, output)
        self.assertTrue((output / "validation.json").exists())
        self.assertIn("FAIL", (output / "validation.md").read_text())

    def test_bad_checksum(self):
        self.files["checksums.sha256"] = "".join(f"{'0' * 64}  {name}\n" for name in self.files).encode()
        package(self.files, self.path, hashes=False)
        self.assertIn("CHECKSUM_MISMATCH:metadata.json", validate(self.path)["errors"])

    def test_missing_checksum_coverage(self):
        self.files["checksums.sha256"] = b""
        package(self.files, self.path, hashes=False)
        self.assertIn("CHECKSUM_COVERAGE_MISMATCH", validate(self.path)["errors"])

    def test_unknown_schema(self):
        self.files["schema_version.txt"] = b"999\n"
        self.assert_invalid("UNSUPPORTED_SCHEMA_VERSION")

    def test_nonmonotonic_elapsed(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[1].update(timestamp_elapsed_ns="1"))
        self.assert_invalid("ELAPSED_NOT_MONOTONIC")

    def test_bad_sequence(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[1].update(sequence_no="1"))
        self.assert_invalid("SEQUENCE_NOT_CONTIGUOUS")

    def test_bad_bssid(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[0].update(bssid="invalid"))
        self.assert_invalid("BSSID_INVALID")

    def test_wrong_channel(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[0].update(channel="999"))
        self.assert_invalid("FREQUENCY_BAND_CHANNEL_MISMATCH")

    def test_join_outside_limit(self):
        first = list(csv.DictReader(io.StringIO(self.files["locations.csv"].decode())))[0]
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[-1].update(location_id_at_capture=first["id"], location_age_ms="5000", location_accuracy_m_at_capture="4.0"))
        self.assert_invalid("LOCATION_JOIN_OUTSIDE_WINDOW")

    def test_orphan_location_join(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[0].update(location_id_at_capture="missing"))
        self.assert_invalid("LOCATION_FOREIGN_KEY_MISSING")

    def test_sources_cannot_mix(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[0].update(source="SCAN_RESULT"))
        self.assert_invalid("MIXED_MEASUREMENT_SOURCE")

    def test_missing_reserved_scan_file(self):
        del self.files["scan_results.csv"]
        self.assert_invalid("ZIP_ENTRY_SET_MISMATCH")

    def test_unlocated_flag_required(self):
        edit_csv(self.files, "connected_wifi.csv", lambda rows: rows[-1].update(quality_flags=""))
        self.assert_invalid("UNLOCATED_FLAG_MISSING")

    def test_missing_gps_is_valid_raw_export(self):
        edit_csv(self.files, "locations.csv", lambda rows: rows.clear())
        edit_csv(self.files, "connected_wifi.csv", lambda rows: [row.update(location_id_at_capture="", location_age_ms="", location_accuracy_m_at_capture="", quality_flags="UNLOCATED") for row in rows])
        metadata = json.loads(self.files["metadata.json"])
        metadata["counts"].update(locations=0, located_connected_wifi=0)
        self.files["metadata.json"] = json.dumps(metadata).encode()
        self.files["track.geojson"] = b'{"type":"FeatureCollection","features":[]}'
        result = self.run_validation()
        self.assertEqual("PASS", result["status"], result)
        self.assertIn("NO_VALID_LOCATION_JOINS", result["warnings"])

    def test_reproducible_synthetic_zip(self):
        package(self.files, self.path)
        first = self.path.read_bytes()
        package(self.files, self.path)
        self.assertEqual(first, self.path.read_bytes())

    def test_committed_fixture_checksums_cover_exact_bytes(self):
        for line in (FIXTURE / "checksums.sha256").read_text(encoding="ascii").splitlines():
            digest, name = line.split("  ", 1)
            self.assertEqual(digest, hashlib.sha256((FIXTURE / name).read_bytes()).hexdigest(), name)

    def test_private_destination_guard_and_ignore_rules(self):
        check_rules()
        self.assertEqual((PRIVATE_ROOT / "raw/probe.zip").resolve(), checked_private_path(PRIVATE_ROOT / "raw/probe.zip"))
        with self.assertRaises(ValueError):
            checked_private_path(FIXTURE / "real.zip")
        with self.assertRaises(ValueError):
            checked_private_path(PRIVATE_ROOT / "../../config/real.json")


if __name__ == "__main__":
    unittest.main()

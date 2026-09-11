"""ADB workflow with serial-free console output and mandatory private-path checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone
from check_privacy import PROJECT, PRIVATE_ROOT, checked_private_path, check_rules
from validate_survey import validate, write_reports

APP_ID = "pl.autoklinika.infrastructure.wifisurvey"
REMOTE = "/sdcard/Download/WorkshopWiFiSurvey"


class Device:
    def __init__(self):
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError("ADB unavailable")
        rows = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
        if len(rows) != 1 or len(rows[0]) < 2 or rows[0][1] != "device":
            raise RuntimeError("Connect exactly one authorized device; accept the USB prompt on the phone")
        self._serial = rows[0][0]  # Process memory only. Never print, persist, or include in errors.

    def run(self, *args, allow_missing_package=False):
        result = subprocess.run(["adb", "-s", self._serial, *args], capture_output=True, text=True)
        if allow_missing_package and args == ("shell", "pm", "path", APP_ID) and result.returncode == 1:
            return ""
        if result.returncode:
            raise RuntimeError("ADB command failed; inspect the phone and private diagnostics")
        return result.stdout.strip()

    def preflight(self):
        data = {"model": self.run("shell", "getprop", "ro.product.model"),
                "android_release": self.run("shell", "getprop", "ro.build.version.release"),
                "android_sdk": self.run("shell", "getprop", "ro.build.version.sdk"),
                "build_fingerprint": self.run("shell", "getprop", "ro.build.fingerprint"),
                "application_installed": bool(self.run("shell", "pm", "path", APP_ID, allow_missing_package=True))}
        if int(data["android_sdk"]) < 34:
            raise RuntimeError("Android API 34 or later required")
        target = checked_private_path(PRIVATE_ROOT / "processed" / "device-preflight.json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print("Device preflight saved privately. Complete permissions, Wi-Fi and location checks on the phone.")
        print("Flip6 model match: " + str(data["model"].startswith("SM-F741")))

    def install(self, apk: Path):
        if not apk.is_file():
            raise RuntimeError("APK missing; build first with gradlew.bat assembleDebug")
        self.preflight()
        output = self.run("install", "-r", str(apk.resolve()))
        if "Success" not in output:
            raise RuntimeError("APK installation did not report success")
        self.run("shell", "am", "start", "-n", APP_ID + "/.MainActivity")
        print("APK installed and opened. Grant precise location, nearby Wi-Fi and notifications in the app.")

    def pull(self):
        checked_private_path(PRIVATE_ROOT / "raw" / "probe" / "survey_probe.zip")
        names = self.run("shell", "ls", "-1", REMOTE).splitlines()
        # Restrict names before passing to the remote shell, including MediaStore duplicate suffixes.
        names = [name for name in names if re.fullmatch(r"survey_\d{8}_\d{6}_[0-9a-f]{8}(?: \(\d+\))?\.zip", name)]
        if not names:
            raise RuntimeError("No completed survey export found")
        name = sorted(names)[-1]
        directory = checked_private_path(PRIVATE_ROOT / "raw" / Path(name).stem)
        target = checked_private_path(directory / name)
        if target.exists():
            raise RuntimeError("Export already downloaded; immutable original will not be overwritten")
        directory.mkdir(parents=True, exist_ok=True)
        temporary = checked_private_path(directory / "download.partial")
        if temporary.exists():
            raise RuntimeError("Previous partial download exists; inspect it locally before retrying")
        self.run("pull", REMOTE + "/" + name, str(temporary))
        temporary.rename(target)
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        (directory / "original.sha256").write_text(digest + "\n", encoding="ascii")
        result = validate(target)
        registry_path = checked_private_path(PRIVATE_ROOT / "processed" / "imports.json")
        registry_path.parent.mkdir(parents=True, exist_ok=True)
        registry = json.loads(registry_path.read_text()) if registry_path.exists() else {}
        session_id = result.get("session_id")
        if digest in registry or (session_id and any(entry.get("session_id") == session_id for entry in registry.values())):
            result["warnings"].append("DUPLICATE_SESSION_OR_EXPORT_IMPORT")
        registry[digest] = {"session_id": session_id, "imported_utc": datetime.now(timezone.utc).isoformat()}
        registry_path.write_text(json.dumps(registry, indent=2) + "\n", encoding="utf-8")
        report = checked_private_path(PRIVATE_ROOT / "reports" / Path(name).stem)
        write_reports(result, report)
        print(f"Downloaded immutable original into ignored data/. Validation: {result['status']}.")
        if result["status"] != "PASS":
            raise RuntimeError("Validation failed; inspect private validation.json and validation.md")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["preflight", "install", "pull"])
    parser.add_argument("--apk", type=Path, default=PROJECT / "android/app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    check_rules()
    device = Device()
    if args.action == "install":
        device.install(args.apk)
    else:
        getattr(device, args.action)()


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError) as failure:
        raise SystemExit(str(failure)) from None

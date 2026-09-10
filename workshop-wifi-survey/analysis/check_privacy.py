"""Fail closed before downloading any operational data into this repository."""
from pathlib import Path
import subprocess

PROJECT = Path(__file__).resolve().parents[1]
REPO = PROJECT.parent
PRIVATE_ROOT = PROJECT / "data" / "WorkshopWiFiSurvey"


def checked_private_path(path: Path) -> Path:
    path = path.resolve()
    if not path.is_relative_to(PRIVATE_ROOT.resolve()):
        raise ValueError("Destination must be inside the private survey data root")
    relative = path.relative_to(REPO).as_posix()
    if subprocess.run(["git", "check-ignore", "-q", "--", relative], cwd=REPO).returncode != 0:
        raise ValueError("Destination is not ignored by Git; download refused")
    tracked = subprocess.run(["git", "ls-files", "--", relative], cwd=REPO,
                             capture_output=True, text=True, check=True).stdout
    if tracked.strip():
        raise ValueError("Destination is already tracked; download refused")
    return path


def check_rules():
    paths = [
        "data/WorkshopWiFiSurvey/raw/probe/survey_probe.zip",
        "data/WorkshopWiFiSurvey/processed/probe/validation.json",
        "data/WorkshopWiFiSurvey/reports/probe/report.md",
        ".local/device-serial.txt", ".local/adb.txt", ".local/gps.json",
        "reports/private/report.md", "config/ap-map.local.json",
        "config/site.private.json", "android/local.properties",
        "arbitrary/survey_probe.zip", "arbitrary/probe.survey.zip",
        "arbitrary/logcat.txt", "arbitrary/bugreport.txt",
        "arbitrary/connected_wifi.csv", "arbitrary/track.geojson",
    ]
    for path in paths:
        subprocess.run(["git", "check-ignore", "-q", "--", f"workshop-wifi-survey/{path}"],
                       cwd=REPO, check=True)
    checked_private_path(PRIVATE_ROOT / "raw" / "probe" / "survey_probe.zip")
    print(f"PASS: {len(paths)} privacy ignore probes and private destination guard")


if __name__ == "__main__":
    check_rules()

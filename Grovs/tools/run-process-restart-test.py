#!/usr/bin/env python3
"""Run the SDK's persisted-queue test in two distinct Android processes."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial; required when more than one device is connected")
    parser.add_argument("--skip-install", action="store_true", help="Use the test APK already installed on the device")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Library/Android/sdk")
    adb = shutil.which("adb") or str(sdk / "platform-tools/adb")
    serial = args.serial or os.environ.get("ANDROID_SERIAL")
    if not serial:
        listing = subprocess.check_output([adb, "devices"], text=True)
        devices = [line.split()[0] for line in listing.splitlines() if line.endswith("\tdevice")]
        if len(devices) != 1:
            parser.error("Connect one emulator/device or select one with --serial")
        serial = devices[0]
    selected_adb = [adb, "-s", serial]
    package = "io.grovs.test"
    runner = package + "/androidx.test.runner.AndroidJUnitRunner"
    results = project / "Grovs/build/reports/process-restart"
    results.mkdir(parents=True, exist_ok=True)

    if not args.skip_install:
        env = dict(os.environ, ANDROID_SERIAL=serial)
        subprocess.run([str(project / "gradlew"), ":Grovs:installDebugAndroidTest"], cwd=project, env=env, check=True)

    def phase(name, method):
        command = selected_adb + ["shell", "am", "instrument", "-w", "-r",
            "-e", "class", "io.grovs.flows.ProcessRestartDeviceTest#" + method,
            "-e", "grovs.phase", name, runner]
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (results / (name + ".txt")).write_text(result.stdout)
        print(result.stdout, flush=True)
        passed = re.search(r"OK \(1 tests?\)", result.stdout)
        completed = f"grovs_process_phase_completed={name}" in result.stdout
        if result.returncode or not passed or not completed:
            raise SystemExit(f"{name} phase failed; see {results / (name + '.txt')}")

    phase("seed", "seedOfflineQueue")
    # Never clear app data here: the second process must read what the first one persisted.
    subprocess.run(selected_adb + ["shell", "input", "keyevent", "KEYCODE_HOME"], check=True)
    subprocess.run(selected_adb + ["shell", "am", "force-stop", package], check=True)
    phase("verify", "recoverQueueInNewProcess")
    print(f"Process restart flow passed. Reports: {results}")


if __name__ == "__main__":
    main()

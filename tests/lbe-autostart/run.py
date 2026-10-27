#!/usr/bin/env python3
"""Run the complete AutoStart rule through real EzHook/API102 with host adapters."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hyperceiler_test_paths as paths

base = Path(__file__).resolve().parent
rule = paths.SOURCE_ROOT / "library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/lbe/AutoStart.java"
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
output = parser.parse_args().output
output.mkdir(parents=True, exist_ok=False)
dependencies = [paths.BUILD_CACHE / "changed-hooks-compile" / name
                for name in ("hook-xposed-102-1.3.0.jar", "api-102.0.0.jar")]
dependencies.append(paths.KOTLIN_HOME / "lib/kotlin-stdlib.jar")
dependencies.append(paths.ANDROID_HOME / "platforms/android-37.0/android.jar")
sources = [rule, *sorted((base / "fixtures").rglob("*.java"))]
before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
with tempfile.TemporaryDirectory(prefix="hc-autostart-") as directory:
    classes = Path(directory) / "classes"
    classpath = os.pathsep.join(map(str, dependencies))
    command = [str(paths.java_tool("javac")), "--release", "21", "-encoding", "UTF-8",
               "-proc:none", "-cp", classpath, "-d", str(classes), *map(str, sources)]
    compiled = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace")
    (output / "compile.txt").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode:
        raise RuntimeError(compiled.stdout + compiled.stderr)
    result = subprocess.run([str(paths.java_tool("java")), "-ea", "-cp",
                             str(classes) + os.pathsep + classpath, "TestMain"],
                            capture_output=True, encoding="utf-8", errors="replace", timeout=60)
    (output / "tests.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout)
    match = re.search(r"RESULT passes=(\d+) failures=(\d+)", result.stdout)
    after = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    passed = result.returncode == 0 and match is not None and int(match[2]) == 0 and before == after
    report = {"passed": passed, "sources": before, "inputs_unchanged": before == after,
              "tests": {"passed": int(match[1]), "failed": int(match[2])} if match else None,
              "scope": "Whole production AutoStart rule and actual EzHook/API102 chain; ROM-shaped targets, Android values and hook backend are host adapters, not ART or phone evidence."}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not passed:
        raise RuntimeError("Auto-start regression failed")

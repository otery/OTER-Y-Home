#!/usr/bin/env python3
"""Offline structural checks for environments without the Android SDK."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def strip_java_literals(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source)
    source = re.sub(r"'(?:\\.|[^'\\])*'", "''", source)
    return source


def check_balanced(path: Path) -> None:
    clean = strip_java_literals(path.read_text(encoding="utf-8"))
    pairs = {')': '(', ']': '[', '}': '{'}
    stack: list[tuple[str, int]] = []
    for index, char in enumerate(clean):
        if char in "([{":
            stack.append((char, index))
        elif char in pairs:
            if not stack or stack[-1][0] != pairs[char]:
                raise AssertionError(f"{path}: unmatched {char} at {index}")
            stack.pop()
    if stack:
        raise AssertionError(f"{path}: unclosed {stack[-1][0]} at {stack[-1][1]}")


def main() -> int:
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    ET.parse(manifest)
    ET.parse(ROOT / "app/src/main/res/values/colors.xml")
    ET.parse(ROOT / "app/src/main/res/values/strings.xml")
    ET.parse(ROOT / "app/src/main/res/values/themes.xml")
    ET.parse(ROOT / "docs/preview.svg")

    java_root = ROOT / "app/src/main/java/com/personal/tensionhome"
    java_files = sorted(java_root.glob("*.java"))
    assert {p.name for p in java_files} == {
        "MainActivity.java",
        "PlaybackNotificationListener.java",
        "TensionHomeView.java",
    }
    for path in java_files:
        source = path.read_text(encoding="utf-8")
        assert "package com.personal.tensionhome;" in source
        check_balanced(path)

    manifest_text = manifest.read_text(encoding="utf-8")
    assert "android.permission.INTERNET" not in manifest_text
    assert 'android.intent.category.HOME' in manifest_text
    assert 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest_text

    home_source = (java_root / "TensionHomeView.java").read_text(encoding="utf-8")
    for feature in (
        "setStreamVolume",
        "drawMediaTypography",
        "drawCalendar",
        "drawSoundWave",
        "drawStatusIndicators",
        "drawDrawerSpinner",
        "drawMediaPage",
        "currentPage",
        "toggleMute",
        "secondaryPackages",
        "drawCoverHome",
        "drawInnerHome",
        "showSlotEditor",
        "notificationPackages",
        "1.48f",
    ):
        assert feature in home_source, f"Missing feature: {feature}"

    print(f"OK: {len(java_files)} Java sources, XML/SVG resources, permissions and feature hooks")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, ET.ParseError) as error:
        print(f"FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)

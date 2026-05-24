#!/usr/bin/env python3
"""Build, install, launch, and screenshot the iOS app on a booted simulator."""

from __future__ import annotations

import argparse
import json
import shutil
import struct
import subprocess
import time
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "Stock Analysis" / "Stock Analysis.xcodeproj"
SCHEME = "Stock Analysis"
BUNDLE_ID = "com.quantbridge.stockanalysis"
MIN_SCREENSHOT_BYTES = 20_000
MIN_PIXEL_RANGE = 8
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def run(cmd: list[str], check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(part) for part in cmd))
    return subprocess.run(
        cmd,
        cwd=str(ROOT),
        text=True,
        check=check,
        capture_output=capture,
    )


def require_xcrun() -> None:
    if not shutil.which("xcrun"):
        raise SystemExit("xcrun not found. Install Xcode command line tools first.")


def booted_simulator() -> str | None:
    result = run(["xcrun", "simctl", "list", "devices", "booted", "-j"], capture=True)
    data = json.loads(result.stdout)
    for devices in data.get("devices", {}).values():
        for device in devices:
            if device.get("state") == "Booted":
                return device.get("udid")
    return None


def verify_screenshot(path: Path) -> None:
    if not path.exists():
        raise SystemExit(f"Screenshot was not created: {path}")
    if path.stat().st_size < MIN_SCREENSHOT_BYTES:
        raise SystemExit(f"Screenshot looks too small to be valid: {path} ({path.stat().st_size} bytes)")

    try:
        from PIL import Image
    except ImportError:
        pixel_range = png_pixel_range(path)
        if pixel_range is None:
            print("[ios-qa] Pillow not installed and PNG format unsupported; skipped pixel range check")
            return
        if pixel_range < MIN_PIXEL_RANGE:
            raise SystemExit(
                "Screenshot looks blank or frozen: "
                f"{path} pixel_range={pixel_range}"
            )
        print(f"[ios-qa] screenshot verified bytes={path.stat().st_size} pixel_range={pixel_range}")
        return

    with Image.open(path) as image:
        rgb = image.convert("RGB")
        extrema = rgb.getextrema()
    widest_channel_range = max(high - low for low, high in extrema)
    if widest_channel_range < MIN_PIXEL_RANGE:
        raise SystemExit(
            "Screenshot looks blank or frozen: "
            f"{path} pixel_range={widest_channel_range}"
        )
    print(f"[ios-qa] screenshot verified bytes={path.stat().st_size} pixel_range={widest_channel_range}")


def png_pixel_range(path: Path) -> int | None:
    raw = path.read_bytes()
    if not raw.startswith(PNG_SIGNATURE):
        return None

    offset = len(PNG_SIGNATURE)
    width = height = bit_depth = color_type = interlace = None
    compressed_parts: list[bytes] = []
    while offset + 8 <= len(raw):
        length = struct.unpack(">I", raw[offset:offset + 4])[0]
        chunk_type = raw[offset + 4:offset + 8]
        chunk_data = raw[offset + 8:offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _compression, _filter, interlace = struct.unpack(">IIBBBBB", chunk_data)
        elif chunk_type == b"IDAT":
            compressed_parts.append(chunk_data)
        elif chunk_type == b"IEND":
            break

    channel_counts = {0: 1, 2: 3, 4: 2, 6: 4}
    if (
        width is None
        or height is None
        or bit_depth != 8
        or interlace != 0
        or color_type not in channel_counts
        or not compressed_parts
    ):
        return None

    channels = channel_counts[color_type]
    bytes_per_pixel = channels
    row_length = width * bytes_per_pixel
    inflated = zlib.decompress(b"".join(compressed_parts))
    previous = bytearray(row_length)
    mins = [255, 255, 255]
    maxes = [0, 0, 0]
    cursor = 0

    for _row_index in range(height):
        if cursor >= len(inflated):
            return None
        filter_type = inflated[cursor]
        cursor += 1
        row = bytearray(inflated[cursor:cursor + row_length])
        cursor += row_length
        if len(row) != row_length:
            return None
        unfilter_png_row(row, previous, bytes_per_pixel, filter_type)
        for pixel in range(0, row_length, bytes_per_pixel):
            if color_type in (0, 4):
                rgb = (row[pixel], row[pixel], row[pixel])
            else:
                rgb = (row[pixel], row[pixel + 1], row[pixel + 2])
            for channel, value in enumerate(rgb):
                mins[channel] = min(mins[channel], value)
                maxes[channel] = max(maxes[channel], value)
        previous = row

    return max(maxes[channel] - mins[channel] for channel in range(3))


def unfilter_png_row(row: bytearray, previous: bytearray, bytes_per_pixel: int, filter_type: int) -> None:
    for index, value in enumerate(row):
        left = row[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
        up = previous[index]
        upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
        if filter_type == 0:
            predictor = 0
        elif filter_type == 1:
            predictor = left
        elif filter_type == 2:
            predictor = up
        elif filter_type == 3:
            predictor = (left + up) // 2
        elif filter_type == 4:
            predictor = paeth_predictor(left, up, upper_left)
        else:
            raise SystemExit(f"Unsupported PNG filter type: {filter_type}")
        row[index] = (value + predictor) & 0xFF


def paeth_predictor(left: int, up: int, upper_left: int) -> int:
    estimate = left + up - upper_left
    distance_left = abs(estimate - left)
    distance_up = abs(estimate - up)
    distance_upper_left = abs(estimate - upper_left)
    if distance_left <= distance_up and distance_left <= distance_upper_left:
        return left
    if distance_up <= distance_upper_left:
        return up
    return upper_left


def main() -> int:
    parser = argparse.ArgumentParser(description="Run iOS simulator smoke QA for QuantBridge")
    parser.add_argument("--simulator", default="", help="Simulator UDID. Defaults to the booted simulator.")
    parser.add_argument("--allow-missing-simulator", action="store_true", help="Build only if no simulator is booted.")
    parser.add_argument("--no-launch", action="store_true", help="Build/install only; do not launch or screenshot.")
    parser.add_argument("--derived-data", default="/tmp/quantbridge-ios-derived")
    parser.add_argument("--reuse-derived-data", action="store_true", help="Reuse the derived data directory instead of clearing it first.")
    parser.add_argument("--artifacts-dir", default=str(ROOT / "artifacts" / "mobile-smoke"))
    parser.add_argument("--skip-screenshot-verify", action="store_true", help="Do not check that the captured screenshot is non-blank.")
    args = parser.parse_args()

    require_xcrun()
    simulator = args.simulator.strip() or booted_simulator()
    destination = f"platform=iOS Simulator,id={simulator}" if simulator else "generic/platform=iOS Simulator"
    derived_data = Path(args.derived_data)
    artifacts_dir = Path(args.artifacts_dir)
    app_path = derived_data / "Build" / "Products" / "Debug-iphonesimulator" / "Stock Analysis.app"

    if not args.reuse_derived_data and derived_data.exists():
        shutil.rmtree(derived_data)

    print("[ios-qa] Build")
    run([
        "xcodebuild",
        "-project",
        str(PROJECT),
        "-scheme",
        SCHEME,
        "-configuration",
        "Debug",
        "-destination",
        destination,
        "-derivedDataPath",
        str(derived_data),
        "build",
    ])

    if not simulator:
        message = "No booted iOS simulator found. Boot a simulator in Xcode, then rerun for install/launch QA."
        if args.allow_missing_simulator:
            print(f"[ios-qa] {message}")
            return 0
        raise SystemExit(message)
    if not app_path.exists():
        raise SystemExit(f"Built app not found: {app_path}")
    if args.no_launch:
        print(f"[ios-qa] build-only ok app={app_path}")
        return 0

    artifacts_dir.mkdir(parents=True, exist_ok=True)
    print(f"[ios-qa] simulator={simulator}")
    print("[ios-qa] Install")
    run(["xcrun", "simctl", "install", simulator, str(app_path)])
    print("[ios-qa] Launch")
    launch = run(["xcrun", "simctl", "launch", "--terminate-running-process", simulator, BUNDLE_ID], capture=True)
    print(launch.stdout.strip())
    time.sleep(4)

    screenshot = artifacts_dir / "ios-home.png"
    run(["xcrun", "simctl", "io", simulator, "screenshot", "--type=png", str(screenshot)])
    print(f"[ios-qa] screenshot={screenshot}")
    if not args.skip_screenshot_verify:
        verify_screenshot(screenshot)
    print("[ios-qa] ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Configure local iOS/Android apps to talk to the Docker FastAPI server."""

from __future__ import annotations

import argparse
import plistlib
import socket
import subprocess
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[1]
QUANT_DIR = ROOT.parent


def _sibling_or_root_dir(*names: str) -> Path:
    for name in names:
        in_root = ROOT / name
        if in_root.exists():
            return in_root
    for name in names:
        sibling = QUANT_DIR / name
        if sibling.exists():
            return sibling
    return ROOT / names[0]


IOS_DIR = _sibling_or_root_dir("Stock Analysis")
ANDROID_DIR = _sibling_or_root_dir("android", "andriod")
IOS_INFO_PLIST = IOS_DIR / "Stock Analysis" / "Info.plist"
ANDROID_LOCAL_PROPERTIES = ANDROID_DIR / "local.properties"


def detect_lan_ip() -> str:
    for iface in ("en0", "en1"):
        try:
            out = subprocess.check_output(["ipconfig", "getifaddr", iface], text=True).strip()
            if out:
                return out
        except Exception:
            pass

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def update_info_plist(url: str) -> None:
    if not IOS_INFO_PLIST.exists():
        print(f"[app-api] iOS project not found; skipped: {IOS_INFO_PLIST}")
        return
    with IOS_INFO_PLIST.open("rb") as f:
        data = plistlib.load(f)
    data["APIBaseURL"] = url
    with IOS_INFO_PLIST.open("wb") as f:
        plistlib.dump(data, f, sort_keys=False)


def update_android_local_properties(url: str) -> None:
    ANDROID_LOCAL_PROPERTIES.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    if ANDROID_LOCAL_PROPERTIES.exists():
        lines = ANDROID_LOCAL_PROPERTIES.read_text(encoding="utf-8").splitlines()

    updated = False
    legacy_updated = False
    has_sdk_dir = False
    out = []
    for line in lines:
        if line.startswith("quantApiDebugBaseUrl="):
            out.append(f"quantApiDebugBaseUrl={url}")
            updated = True
        elif line.startswith("quantApiBaseUrl="):
            out.append(f"quantApiBaseUrl={url}")
            legacy_updated = True
        else:
            out.append(line)
        if line.startswith("sdk.dir="):
            has_sdk_dir = True
    if not has_sdk_dir:
        sdk_candidates = [
            Path.home() / "Library" / "Android" / "sdk",
            Path.home() / "Android" / "Sdk",
        ]
        sdk_dir = next((path for path in sdk_candidates if path.exists()), None)
        if sdk_dir:
            if out and out[-1].strip():
                out.append("")
            out.append(f"sdk.dir={sdk_dir}")
    if not updated:
        if out and out[-1].strip():
            out.append("")
        out.append(f"quantApiDebugBaseUrl={url}")
    if not legacy_updated:
        if out and out[-1].strip():
            out.append("")
        out.append(f"quantApiBaseUrl={url}")

    ANDROID_LOCAL_PROPERTIES.write_text("\n".join(out) + "\n", encoding="utf-8")


def update_swift_fallback(url: str) -> None:
    # API fallback is now supplied by build settings / Info.plist, so this
    # command should only update local runtime configuration.
    return


def check_ready(url: str, timeout: float = 30.0) -> dict:
    response = requests.get(f"{url.rstrip('/')}/ready", timeout=timeout)
    response.raise_for_status()
    return response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description="Configure app API URLs for the local QuantBridge server")
    parser.add_argument("--url", default="", help="Explicit API base URL, e.g. http://10.0.0.5:8000")
    parser.add_argument("--no-check", action="store_true", help="Skip /ready check")
    parser.add_argument("--timeout", type=float, default=30.0, help="/ready timeout in seconds")
    args = parser.parse_args()

    url = args.url.strip() or f"http://{detect_lan_ip()}:8000"
    url = url.rstrip("/")

    update_info_plist(url)
    update_android_local_properties(url)
    update_swift_fallback(url)

    print(f"[app-api] iOS APIBaseURL     = {url}")
    print(f"[app-api] Android BuildConfig = {url}")

    if not args.no_check:
        ready = check_ready(url, timeout=args.timeout)
        print(f"[app-api] /ready status     = {ready.get('status')} cache={ready.get('cache')}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Build, install, launch, and smoke-check the Android app on a connected device."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
QUANT_DIR = ROOT.parent


def _android_dir() -> Path:
    for name in ("android", "andriod"):
        in_root = ROOT / name
        if in_root.exists():
            return in_root
    for name in ("android", "andriod"):
        sibling = QUANT_DIR / name
        if sibling.exists():
            return sibling
    return ROOT / "android"


ANDROID_DIR = _android_dir()
APK_PATH = ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
PACKAGE_NAME = "com.example.myapplication"
MAIN_ACTIVITY = "com.example.myapplication/.MainActivity"


def run(cmd: list[str], cwd: Path = ROOT, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(part) for part in cmd))
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        text=True,
        check=check,
        capture_output=capture,
    )


def find_adb() -> str | None:
    candidates = [
        os.getenv("ADB", ""),
        shutil.which("adb") or "",
        str(Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb"),
        str(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    return None


def connected_devices(adb: str) -> list[str]:
    result = run([adb, "devices"], check=True, capture=True)
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def foreground_package(adb: str, serial: str) -> str:
    result = run([adb, "-s", serial, "shell", "dumpsys", "window"], check=False, capture=True)
    for pattern in (
        r"mCurrentFocus=.*? ([A-Za-z0-9_.]+)/",
        r"mFocusedApp=.*? ([A-Za-z0-9_.]+)/",
    ):
        match = re.search(pattern, result.stdout)
        if match:
            return match.group(1)
    return ""


def ensure_app_foreground(adb: str, serial: str) -> None:
    package = foreground_package(adb, serial)
    if package and package != PACKAGE_NAME:
        raise RuntimeError(f"큐빗 is not foreground; current foreground package is {package}")


def ensure_url(url: str, skip_config: bool) -> None:
    if skip_config:
        return
    cmd = [sys.executable, "tools/configure_app_api.py", "--timeout", "30"]
    if url:
        cmd.extend(["--url", url])
    run(cmd)


def screenshot(adb: str, serial: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [adb, "-s", serial, "exec-out", "screencap", "-p"],
        cwd=str(ROOT),
        check=True,
        capture_output=True,
    )
    output.write_bytes(result.stdout)
    print(f"[android-qa] screenshot={output}")


def dump_ui(adb: str, serial: str) -> ET.Element:
    result = run([adb, "-s", serial, "exec-out", "uiautomator", "dump", "/dev/tty"], capture=True)
    xml_start = result.stdout.find("<?xml")
    if xml_start < 0:
        xml_start = result.stdout.find("<hierarchy")
    xml_end = result.stdout.rfind("</hierarchy>")
    if xml_start < 0 or xml_end < 0:
        tail = result.stdout[-500:].replace("\n", "\\n")
        raise RuntimeError(f"uiautomator XML not found in output tail: {tail}")
    raw_xml = result.stdout[xml_start : xml_end + len("</hierarchy>")]
    return ET.fromstring(raw_xml)


def node_text(node: ET.Element) -> str:
    return (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()


def find_node(root: ET.Element, text: str) -> ET.Element | None:
    needle = text.casefold()
    for node in root.iter("node"):
        haystack = node_text(node).casefold()
        if needle and needle in haystack:
            return node
    return None


def matching_nodes(root: ET.Element, text: str, exact: bool = False) -> list[ET.Element]:
    needle = text.casefold()
    matches = []
    for node in root.iter("node"):
        haystack = node_text(node).casefold()
        if not needle:
            continue
        if (exact and haystack == needle) or (not exact and needle in haystack):
            matches.append(node)
    return matches


def parse_bounds(node: ET.Element, label: str) -> tuple[int, int, int, int]:
    bounds = node.attrib.get("bounds", "")
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        raise RuntimeError(f"Could not parse bounds for {label}: {bounds}")
    return tuple(map(int, match.groups()))


def tap_node(adb: str, serial: str, node: ET.Element, label: str) -> None:
    x1, y1, x2, y2 = parse_bounds(node, label)
    x = (x1 + x2) // 2
    y = (y1 + y2) // 2
    print(f"[android-qa] tap {label} @ {x},{y}")
    run([adb, "-s", serial, "shell", "input", "tap", str(x), str(y)])


def wait_for_text(adb: str, serial: str, text: str, timeout: float = 25.0) -> ET.Element:
    deadline = time.time() + timeout
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            ensure_app_foreground(adb, serial)
            root = dump_ui(adb, serial)
            if find_node(root, text) is not None:
                return root
        except Exception as exc:
            last_error = exc
        time.sleep(1)
    if last_error:
        raise RuntimeError(f"Timed out waiting for text {text!r}: {last_error}") from last_error
    raise RuntimeError(f"Timed out waiting for text {text!r}")


def tap_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    root = wait_for_text(adb, serial, text, timeout=timeout)
    node = find_node(root, text)
    if node is None:
        raise RuntimeError(f"Could not find tappable text {text!r}")
    tap_node(adb, serial, node, text)
    time.sleep(1.2)
    return dump_ui(adb, serial)


def tap_exact_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ensure_app_foreground(adb, serial)
        root = dump_ui(adb, serial)
        candidates = matching_nodes(root, text, exact=True)
        if candidates:
            tap_node(adb, serial, candidates[0], text)
            time.sleep(1.2)
            return dump_ui(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for exact tappable text: {text!r}")


def tap_top_exact_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ensure_app_foreground(adb, serial)
        root = dump_ui(adb, serial)
        candidates = matching_nodes(root, text, exact=True)
        if candidates:
            node = min(candidates, key=lambda candidate: parse_bounds(candidate, text)[1])
            tap_node(adb, serial, node, text)
            time.sleep(1.2)
            return dump_ui(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for top exact tappable text: {text!r}")


def tap_any_text(adb: str, serial: str, labels: list[str], timeout: float = 20.0) -> tuple[str, ET.Element]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ensure_app_foreground(adb, serial)
        root = dump_ui(adb, serial)
        for label in labels:
            node = find_node(root, label)
            if node is not None:
                tap_node(adb, serial, node, label)
                time.sleep(1.2)
                return label, dump_ui(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for any tappable text: {', '.join(labels)}")


def tap_portfolio_candidate(adb: str, serial: str, labels: list[str], timeout: float = 90.0) -> tuple[str, ET.Element]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        ensure_app_foreground(adb, serial)
        root = dump_ui(adb, serial)
        header = find_node(root, "오늘의 포트폴리오 후보")
        if header is None:
            time.sleep(1)
            continue

        _, _, _, header_bottom = parse_bounds(header, "오늘의 포트폴리오 후보")
        next_strip = find_node(root, "SmallCap 관심 후보")
        next_strip_top = parse_bounds(next_strip, "SmallCap 관심 후보")[1] if next_strip is not None else 10_000

        for label in labels:
            candidates = []
            for node in matching_nodes(root, label, exact=True):
                _, y1, _, y2 = parse_bounds(node, label)
                if header_bottom < y1 and y2 < next_strip_top:
                    candidates.append(node)
            if candidates:
                node = min(candidates, key=lambda candidate: parse_bounds(candidate, label)[1])
                tap_node(adb, serial, node, label)
                time.sleep(1.2)
                ensure_app_foreground(adb, serial)
                return label, dump_ui(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for portfolio candidate: {', '.join(labels)}")


def input_text(adb: str, serial: str, value: str) -> None:
    escaped = value.replace("%", "%25").replace(" ", "%s")
    run([adb, "-s", serial, "shell", "input", "text", escaped])
    time.sleep(0.4)


def input_email(adb: str, serial: str, local_part: str, domain: str = "example.com") -> str:
    input_text(adb, serial, local_part)
    run([adb, "-s", serial, "shell", "input", "keyevent", "KEYCODE_AT"])
    time.sleep(0.2)
    input_text(adb, serial, domain)
    return f"{local_part}@{domain}"


def tap_market_ticker(adb: str, serial: str, timeout: float = 20.0) -> ET.Element:
    root = wait_for_text(adb, serial, "큐빗", timeout=timeout)
    search_candidates = [
        candidate
        for candidate in (matching_nodes(root, "검색", exact=True) or matching_nodes(root, "검색"))
        if parse_bounds(candidate, "검색")[3] <= 420
    ]
    if search_candidates:
        x1, y1, _, y2 = parse_bounds(search_candidates[0], "검색")
        x = max(20, x1 - 140)
        y = (y1 + y2) // 2
    else:
        x, y = 930, 220
    print(f"[android-qa] tap market ticker @ {x},{y}")
    run([adb, "-s", serial, "shell", "input", "tap", str(x), str(y)])
    time.sleep(1.2)
    return dump_ui(adb, serial)


def tap_bottom_nav(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    root = wait_for_text(adb, serial, text, timeout=timeout)
    candidates = matching_nodes(root, text, exact=True) or matching_nodes(root, text)
    if not candidates:
        raise RuntimeError(f"Could not find bottom navigation text {text!r}")
    node = max(candidates, key=lambda candidate: parse_bounds(candidate, text)[3])
    tap_node(adb, serial, node, f"bottom nav {text}")
    time.sleep(1.2)
    return dump_ui(adb, serial)


def assert_texts(adb: str, serial: str, labels: list[str], timeout: float = 25.0) -> None:
    for label in labels:
        wait_for_text(adb, serial, label, timeout=timeout)
        print(f"[android-qa] found {label}")


def scroll_until_text(adb: str, serial: str, text: str, attempts: int = 5) -> None:
    for attempt in range(attempts):
        ensure_app_foreground(adb, serial)
        root = dump_ui(adb, serial)
        if find_node(root, text) is not None:
            print(f"[android-qa] found {text}")
            return
        print(f"[android-qa] scroll for {text} ({attempt + 1}/{attempts})")
        run([adb, "-s", serial, "shell", "input", "swipe", "540", "1900", "540", "520", "520"])
        time.sleep(1.0)
    raise RuntimeError(f"Could not find text after scrolling: {text!r}")


def scroll_to_top(adb: str, serial: str, attempts: int = 3) -> None:
    for _ in range(attempts):
        ensure_app_foreground(adb, serial)
        run([adb, "-s", serial, "shell", "input", "swipe", "540", "520", "540", "1900", "350"])
        time.sleep(0.5)


def restart_app(adb: str, serial: str) -> None:
    run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME], check=False)
    run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY])
    time.sleep(3)


def run_account_session_flow(adb: str, serial: str, artifacts_dir: Path) -> None:
    print("[android-qa] UI smoke: Account session flow")
    root = tap_bottom_nav(adb, serial, "Account")
    if find_node(root, "로그아웃") is not None and find_node(root, "이메일") is None:
        print("[android-qa] account session flow skipped: app is already signed in")
        return

    account_id = uuid.uuid4().hex[:10]
    local_part = f"qbqa{account_id}"
    password = f"Qa{account_id}Pass1"
    display_name = f"QA{account_id[:6]}"

    tap_text(adb, serial, "새 계정 만들기")
    tap_text(adb, serial, "이름")
    input_text(adb, serial, display_name)
    tap_text(adb, serial, "이메일")
    email = input_email(adb, serial, local_part)
    tap_text(adb, serial, "비밀번호")
    input_text(adb, serial, password)
    tap_exact_text(adb, serial, "가입하기", timeout=5)

    assert_texts(adb, serial, [email, "KeyStore 암호화", "로그아웃", "계정 삭제"], timeout=45)
    screenshot(adb, serial, artifacts_dir / "android-account-signed-in.png")

    print("[android-qa] verify session restore after process restart")
    restart_app(adb, serial)
    assert_texts(adb, serial, ["큐빗"], timeout=35)
    tap_bottom_nav(adb, serial, "Account")
    assert_texts(adb, serial, [email, "KeyStore 암호화"], timeout=45)
    screenshot(adb, serial, artifacts_dir / "android-account-restored.png")

    print("[android-qa] delete temporary account")
    tap_text(adb, serial, "계정 삭제")
    tap_exact_text(adb, serial, "삭제", timeout=10)
    assert_texts(adb, serial, ["로그인", "이메일", "비밀번호"], timeout=45)
    screenshot(adb, serial, artifacts_dir / "android-account-deleted.png")


def run_ui_flow(adb: str, serial: str, artifacts_dir: Path, account_flow: bool = False) -> None:
    print("[android-qa] UI smoke: Home")
    assert_texts(adb, serial, ["큐빗", "Home", "Portfolio", "Pulse", "Watch", "Account"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-home.png")

    print("[android-qa] UI smoke: Stock detail")
    tapped, _ = tap_portfolio_candidate(adb, serial, ["성호전자", "삼성전자", "Apple", "Microsoft"], timeout=90)
    print(f"[android-qa] opened detail from {tapped}")
    assert_texts(adb, serial, ["요약", "차트", "재무", "데이터", "밸류에이션"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-detail-overview.png")
    tap_top_exact_text(adb, serial, "차트", timeout=10)
    assert_texts(adb, serial, ["빨강 상승", "캔들"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-detail-chart.png")
    tap_top_exact_text(adb, serial, "재무", timeout=10)
    assert_texts(adb, serial, ["시장 정보"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-detail-financial.png")
    tap_top_exact_text(adb, serial, "데이터", timeout=10)
    assert_texts(adb, serial, ["데이터 신뢰도"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-detail-data.png")
    run([adb, "-s", serial, "shell", "input", "keyevent", "4"])
    time.sleep(1.2)
    scroll_to_top(adb, serial)

    print("[android-qa] UI smoke: Market indicators")
    tap_market_ticker(adb, serial, timeout=25)
    assert_texts(adb, serial, ["주요 지수"], timeout=20)
    screenshot(adb, serial, artifacts_dir / "android-market-indicators.png")
    run([adb, "-s", serial, "shell", "input", "keyevent", "4"])
    time.sleep(1.2)

    print("[android-qa] UI smoke: Portfolio")
    tap_bottom_nav(adb, serial, "Portfolio")
    assert_texts(adb, serial, ["포트폴리오", "US", "KR"], timeout=25)
    screenshot(adb, serial, artifacts_dir / "android-portfolio.png")

    print("[android-qa] UI smoke: Pulse KR names")
    tap_bottom_nav(adb, serial, "Pulse")
    tap_text(adb, serial, "KR")
    assert_texts(adb, serial, ["삼성전자", "SK하이닉스", "POSCO홀딩스", "DB손해보험"], timeout=35)
    screenshot(adb, serial, artifacts_dir / "android-pulse-kr.png")

    print("[android-qa] UI smoke: Watch")
    tap_bottom_nav(adb, serial, "Watch")
    assert_texts(adb, serial, ["관심 종목"], timeout=20)
    screenshot(adb, serial, artifacts_dir / "android-watch.png")

    print("[android-qa] UI smoke: Account")
    tap_bottom_nav(adb, serial, "Account")
    assert_texts(adb, serial, ["로그인", "이메일", "비밀번호"], timeout=20)
    screenshot(adb, serial, artifacts_dir / "android-account.png")

    if account_flow:
        run_account_session_flow(adb, serial, artifacts_dir)


def app_pid(adb: str, serial: str) -> str:
    result = run([adb, "-s", serial, "shell", "pidof", PACKAGE_NAME], check=False, capture=True)
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run Android real-device QA for 큐빗")
    parser.add_argument("--url", default="", help="API URL to configure before building")
    parser.add_argument("--skip-config", action="store_true", help="Do not update app API URL")
    parser.add_argument("--skip-api-smoke", action="store_true", help="Skip backend smoke checks")
    parser.add_argument("--allow-missing-device", action="store_true", help="Return success if no device is attached")
    parser.add_argument("--no-install", action="store_true", help="Build only; do not install or launch")
    parser.add_argument("--skip-ui-flow", action="store_true", help="Only launch and screenshot the app")
    parser.add_argument("--account-flow", action="store_true", help="Create, restore, and delete a temporary account through the app UI")
    parser.add_argument("--serial", default="", help="ADB serial to use. Defaults to the first connected device.")
    parser.add_argument("--artifacts-dir", default=str(ROOT / "artifacts" / "mobile-smoke"))
    parser.add_argument("--screenshot", default=str(ROOT / "artifacts" / "android-qa-screenshot.png"))
    args = parser.parse_args()

    base_url_args = ["--url", args.url] if args.url else []

    print("[android-qa] Configure app API")
    ensure_url(args.url, args.skip_config)

    if not args.skip_api_smoke:
        print("[android-qa] Backend endpoint smoke")
        run([sys.executable, "tools/smoke_app_api.py", "--timeout", "30", *base_url_args])
        print("[android-qa] Backend user-flow smoke")
        run([sys.executable, "tools/smoke_user_flow.py", "--timeout", "30", *base_url_args])

    print("[android-qa] Build debug APK")
    run(["./gradlew", "assembleDebug"], cwd=ANDROID_DIR)
    if not APK_PATH.exists():
        raise SystemExit(f"APK not found: {APK_PATH}")

    if args.no_install:
        print(f"[android-qa] build-only ok apk={APK_PATH}")
        return 0

    adb = find_adb()
    if not adb:
        raise SystemExit(
            "adb not found. Install Android platform-tools or set ADB=/path/to/adb."
        )

    devices = connected_devices(adb)
    if not devices:
        message = (
            "No authorized Android device found. Connect a device/emulator, enable USB debugging, "
            "accept the RSA prompt, then rerun this command."
        )
        if args.allow_missing_device:
            print(f"[android-qa] {message}")
            return 0
        raise SystemExit(message)

    if args.serial:
        if args.serial not in devices:
            raise SystemExit(f"Requested ADB serial not connected: {args.serial}. Connected: {', '.join(devices)}")
        serial = args.serial
    else:
        serial = devices[0]
    artifacts_dir = Path(args.artifacts_dir)
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    print(f"[android-qa] device={serial}")
    run([adb, "-s", serial, "logcat", "-c"], check=False)
    print("[android-qa] Install APK")
    run([adb, "-s", serial, "install", "-r", "-t", str(APK_PATH)])

    print("[android-qa] Launch app")
    run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME], check=False)
    run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY])
    time.sleep(3)

    pid = app_pid(adb, serial)
    if not pid:
        logs = run([adb, "-s", serial, "logcat", "-d", "-t", "200"], check=False, capture=True).stdout
        raise SystemExit(f"App did not stay running. Recent logcat tail:\n{logs[-4000:]}")
    print(f"[android-qa] app running pid={pid}")

    screenshot(adb, serial, Path(args.screenshot))
    if not args.skip_ui_flow:
        run_ui_flow(adb, serial, artifacts_dir, account_flow=args.account_flow)

    crash_log = run([adb, "-s", serial, "logcat", "-d", "-b", "crash"], check=False, capture=True).stdout.strip()
    if crash_log:
        raise SystemExit(f"Crash log is not empty:\n{crash_log[-4000:]}")
    print("[android-qa] ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

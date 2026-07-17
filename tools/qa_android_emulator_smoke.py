#!/usr/bin/env python3
"""Run a focused Android emulator smoke test against the staging API."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import uuid
from collections.abc import Callable
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_DIR = ROOT / "android"
PACKAGE_NAME = "com.qubit.quantbridge"
MAIN_ACTIVITY = "com.qubit.quantbridge/.MainActivity"
DEFAULT_AVD = "QuantBridge_Pixel_8_API_36"
DEFAULT_BASE_URL = "https://qbjinho05082315-api.bravepond-eb5e2096.koreacentral.azurecontainerapps.io"
REQUIRED_ENDPOINTS = (
    "/portfolio/us",
    "/portfolio/kr",
    "/smallcap/us",
    "/smallcap/kr",
    "/macro",
)
DETAIL_ENDPOINT_PREFIXES = ("/stock/",)
HOME_PENDING_TEXTS = ("시장 상태 대기",)
WATCH_ADD_SUFFIX = " 관심 종목 추가"
WATCH_REMOVE_SUFFIX = " 관심 종목 제거"
WATCH_DELETE_SUFFIX = " 관심 종목 삭제"
SMOKE_PROFILES = ("quick", "full")


def _local_properties() -> dict[str, str]:
    path = ANDROID_DIR / "local.properties"
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _sdk_dir() -> Path:
    props = _local_properties()
    candidates = [
        os.getenv("ANDROID_HOME", ""),
        os.getenv("ANDROID_SDK_ROOT", ""),
        props.get("sdk.dir", ""),
        str(Path.home() / "Library" / "Android" / "sdk"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return Path(candidate)
    raise SystemExit("Android SDK not found. Set ANDROID_HOME or android/local.properties sdk.dir.")


def _tool_path(name: str) -> str:
    sdk = _sdk_dir()
    candidates = [
        os.getenv(name.upper(), ""),
        shutil.which(name) or "",
        str(sdk / "platform-tools" / name),
        str(sdk / "emulator" / name),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    raise SystemExit(f"{name} not found in PATH or Android SDK.")


def run(
    cmd: list[str],
    *,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
    capture: bool = False,
    check: bool = True,
    timeout: float | None = None,
) -> subprocess.CompletedProcess:
    print("  $ " + " ".join(str(part) for part in cmd))
    result = subprocess.run(
        cmd,
        cwd=str(cwd),
        env=env,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        output = result.stdout or ""
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(cmd)}\n{output[-4000:]}")
    return result


def adb_devices(adb: str) -> list[str]:
    result = run([adb, "devices"], capture=True)
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def emulator_devices(adb: str) -> list[str]:
    return [serial for serial in adb_devices(adb) if serial.startswith("emulator-")]


def wait_for_emulator_serial(adb: str, before: set[str], timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = emulator_devices(adb)
        new_devices = [serial for serial in current if serial not in before]
        if new_devices:
            return new_devices[0]
        if current:
            return current[0]
        time.sleep(1)
    raise RuntimeError("Timed out waiting for emulator device to appear in adb devices.")


def wait_for_boot(adb: str, serial: str, timeout: float) -> None:
    run([adb, "-s", serial, "wait-for-device"], timeout=timeout)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], capture=True, check=False)
        if result.stdout.strip() == "1":
            print(f"[android-emulator-smoke] booted serial={serial}")
            return
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for Android boot completion on {serial}.")


def ensure_emulator(adb: str, emulator: str, avd: str, artifacts_dir: Path, boot_timeout: float) -> tuple[str, subprocess.Popen | None]:
    existing = emulator_devices(adb)
    if existing:
        serial = existing[0]
        wait_for_boot(adb, serial, boot_timeout)
        return serial, None

    log_path = artifacts_dir / "emulator.log"
    log_file = log_path.open("w", encoding="utf-8")
    before = set(existing)
    print(f"[android-emulator-smoke] starting AVD={avd} log={log_path}")
    try:
        proc = subprocess.Popen(
            [
                emulator,
                "-avd",
                avd,
                "-no-snapshot-load",
                "-no-audio",
                "-no-boot-anim",
                "-gpu",
                "swiftshader_indirect",
            ],
            cwd=str(ROOT),
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
        )
    finally:
        log_file.close()
    serial = wait_for_emulator_serial(adb, before, boot_timeout)
    wait_for_boot(adb, serial, boot_timeout)
    return serial, proc


def build_and_install(adb: str, serial: str, base_url: str, skip_tests: bool) -> None:
    env = os.environ.copy()
    env["ANDROID_SERIAL"] = serial
    tasks = [":app:installDebug"] if skip_tests else [":app:testDebugUnitTest", ":app:installDebug"]
    run(
        [
            "./gradlew",
            *tasks,
            "--console=plain",
            "--quiet",
            f"-PquantApiDebugBaseUrl={base_url}",
            f"-PquantApiReleaseBaseUrl={base_url}",
        ],
        cwd=ANDROID_DIR,
        env=env,
        timeout=240,
    )


def launch_app(adb: str, serial: str) -> None:
    run([adb, "-s", serial, "logcat", "-c"], check=False)
    run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME], check=False)
    run([adb, "-s", serial, "shell", "pm", "clear", PACKAGE_NAME], check=False)
    run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY])


def app_pid(adb: str, serial: str) -> str:
    result = run([adb, "-s", serial, "shell", "pidof", "-s", PACKAGE_NAME], capture=True, check=False)
    return result.stdout.strip()


def wait_for_app(adb: str, serial: str, timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid = app_pid(adb, serial)
        if pid:
            return pid
        time.sleep(1)
    raise RuntimeError("App did not stay running after launch.")


def restart_app(adb: str, serial: str) -> None:
    run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME], check=False)
    run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", MAIN_ACTIVITY])
    wait_for_app(adb, serial, timeout=20)
    time.sleep(3)


def capture_screenshot(adb: str, serial: str, path: Path) -> None:
    with path.open("wb") as handle:
        subprocess.run([adb, "-s", serial, "exec-out", "screencap", "-p"], check=True, stdout=handle)
    print(f"[android-emulator-smoke] screenshot={path}")


def dump_ui_xml(adb: str, serial: str, path: Path | None = None) -> ET.Element:
    last_output = ""
    for attempt in range(1, 5):
        result = run([adb, "-s", serial, "exec-out", "uiautomator", "dump", "/dev/tty"], capture=True, check=False)
        last_output = result.stdout or ""
        xml_start = last_output.find("<?xml")
        if xml_start < 0:
            xml_start = last_output.find("<hierarchy")
        xml_end = last_output.rfind("</hierarchy>")
        if result.returncode == 0 and xml_start >= 0 and xml_end >= 0:
            raw = last_output[xml_start : xml_end + len("</hierarchy>")]
            if path is not None:
                path.write_text(raw, encoding="utf-8")
            return ET.fromstring(raw)
        time.sleep(0.7 * attempt)
    raise RuntimeError(f"uiautomator XML not found: {last_output[-500:]}")


def node_label(node: ET.Element) -> str:
    return (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()


def find_node(root: ET.Element, text: str) -> ET.Element | None:
    needle = text.casefold()
    for node in root.iter("node"):
        if needle in node_label(node).casefold():
            return node
    return None


def find_node_matching(root: ET.Element, predicate: Callable[[str], bool]) -> ET.Element | None:
    for node in root.iter("node"):
        label = node_label(node)
        if label and predicate(label):
            return node
    return None


def matching_nodes(root: ET.Element, text: str, exact: bool = False) -> list[ET.Element]:
    needle = text.casefold()
    matches: list[ET.Element] = []
    for node in root.iter("node"):
        label = node_label(node).casefold()
        if not needle:
            continue
        if (exact and label == needle) or (not exact and needle in label):
            matches.append(node)
    return matches


def visible_labels(root: ET.Element, limit: int = 40) -> list[str]:
    labels = [node_label(node) for node in root.iter("node") if node_label(node)]
    return labels[:limit]


def require_text(root: ET.Element, text: str) -> None:
    if find_node(root, text) is None:
        raise RuntimeError(f"Missing UI text {text!r}. Visible labels: {visible_labels(root)}")
    print(f"[android-emulator-smoke] found UI text={text}")


def require_absent_text(root: ET.Element, text: str) -> None:
    if find_node(root, text) is not None:
        raise RuntimeError(f"Unexpected pending UI text {text!r}. Visible labels: {visible_labels(root)}")


def require_any_text(root: ET.Element, labels: tuple[str, ...]) -> str:
    for text in labels:
        if find_node(root, text) is not None:
            print(f"[android-emulator-smoke] found UI text={text}")
            return text
    raise RuntimeError(f"Missing any UI text {labels!r}. Visible labels: {visible_labels(root)}")


def parse_bounds(node: ET.Element) -> tuple[int, int, int, int]:
    bounds = node.attrib.get("bounds", "")
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match:
        raise RuntimeError(f"Could not parse bounds: {bounds}")
    return tuple(map(int, match.groups()))


def tap_node(adb: str, serial: str, node: ET.Element, label: str) -> None:
    x1, y1, x2, y2 = parse_bounds(node)
    x = (x1 + x2) // 2
    y = (y1 + y2) // 2
    print(f"[android-emulator-smoke] tap {label} @ {x},{y}")
    run([adb, "-s", serial, "shell", "input", "tap", str(x), str(y)])


def wait_for_text(adb: str, serial: str, text: str, timeout: float = 25.0) -> ET.Element:
    deadline = time.monotonic() + timeout
    last_root: ET.Element | None = None
    while time.monotonic() < deadline:
        last_root = dump_ui_xml(adb, serial)
        if find_node(last_root, text) is not None:
            return last_root
        time.sleep(1)
    labels = visible_labels(last_root) if last_root is not None else []
    raise RuntimeError(f"Timed out waiting for UI text {text!r}. Visible labels: {labels}")


def wait_for_absent_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    deadline = time.monotonic() + timeout
    last_root: ET.Element | None = None
    while time.monotonic() < deadline:
        last_root = dump_ui_xml(adb, serial)
        if find_node(last_root, text) is None:
            return last_root
        time.sleep(1)
    labels = visible_labels(last_root) if last_root is not None else []
    raise RuntimeError(f"Timed out waiting for pending UI text {text!r} to clear. Visible labels: {labels}")


def wait_for_node_matching(
    adb: str,
    serial: str,
    predicate: Callable[[str], bool],
    description: str,
    timeout: float = 25.0,
) -> tuple[ET.Element, ET.Element, str]:
    deadline = time.monotonic() + timeout
    last_root: ET.Element | None = None
    while time.monotonic() < deadline:
        last_root = dump_ui_xml(adb, serial)
        node = find_node_matching(last_root, predicate)
        if node is not None:
            return last_root, node, node_label(node)
        time.sleep(1)
    labels = visible_labels(last_root) if last_root is not None else []
    raise RuntimeError(f"Timed out waiting for {description}. Visible labels: {labels}")


def tap_text(adb: str, serial: str, root: ET.Element, text: str) -> None:
    node = find_node(root, text)
    if node is None:
        raise RuntimeError(f"Cannot tap missing UI text {text!r}")
    tap_node(adb, serial, node, text)


def tap_exact_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = dump_ui_xml(adb, serial)
        candidates = matching_nodes(root, text, exact=True)
        if candidates:
            node = min(candidates, key=lambda candidate: parse_bounds(candidate)[1])
            tap_node(adb, serial, node, text)
            time.sleep(1.2)
            return dump_ui_xml(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for exact UI text {text!r}")


def tap_top_exact_text(adb: str, serial: str, text: str, timeout: float = 20.0) -> ET.Element:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = dump_ui_xml(adb, serial)
        candidates = matching_nodes(root, text, exact=True)
        if candidates:
            node = min(candidates, key=lambda candidate: parse_bounds(candidate)[1])
            tap_node(adb, serial, node, text)
            time.sleep(1.2)
            return dump_ui_xml(adb, serial)
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for exact UI text {text!r}")


def tap_bottom_tab(adb: str, serial: str, label: str, timeout: float = 20.0) -> ET.Element:
    text = f"{label} 탭"
    root = wait_for_text(adb, serial, text, timeout=timeout)
    candidates = matching_nodes(root, text, exact=True) or matching_nodes(root, text)
    if not candidates:
        raise RuntimeError(f"Cannot tap missing bottom tab {text!r}")
    node = max(candidates, key=lambda candidate: parse_bounds(candidate)[3])
    tap_node(adb, serial, node, text)
    time.sleep(1.4)
    return dump_ui_xml(adb, serial)


def wait_for_stock_row(adb: str, serial: str, timeout: float = 60.0) -> tuple[ET.Element, ET.Element]:
    deadline = time.monotonic() + timeout
    last_root: ET.Element | None = None
    while time.monotonic() < deadline:
        last_root = dump_ui_xml(adb, serial)
        rows = [
            node
            for node in last_root.iter("node")
            if "상세 보기" in node_label(node) and "가격" in node_label(node)
        ]
        if rows:
            rows.sort(key=lambda candidate: parse_bounds(candidate)[1])
            return last_root, rows[0]
        time.sleep(1)
    labels = visible_labels(last_root) if last_root is not None else []
    raise RuntimeError(f"Timed out waiting for a stock row. Visible labels: {labels}")


def capture_step(adb: str, serial: str, artifacts_dir: Path, step: str) -> ET.Element:
    xml_path = artifacts_dir / f"{step}.xml"
    png_path = artifacts_dir / f"{step}.png"
    root = dump_ui_xml(adb, serial, xml_path)
    capture_screenshot(adb, serial, png_path)
    return root


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


def scroll_until_text(adb: str, serial: str, text: str, attempts: int = 5) -> ET.Element:
    for attempt in range(attempts):
        root = dump_ui_xml(adb, serial)
        if find_node(root, text) is not None:
            print(f"[android-emulator-smoke] found UI text={text}")
            return root
        print(f"[android-emulator-smoke] scroll for {text} ({attempt + 1}/{attempts})")
        run([adb, "-s", serial, "shell", "input", "swipe", "540", "1900", "540", "520", "520"])
        time.sleep(1.0)
    root = dump_ui_xml(adb, serial)
    raise RuntimeError(f"Could not find text after scrolling {text!r}. Visible labels: {visible_labels(root)}")


def _watch_name_from_label(label: str, suffix: str) -> str:
    if not label.endswith(suffix):
        raise RuntimeError(f"Watch action label {label!r} does not end with {suffix!r}")
    name = label[: -len(suffix)].strip()
    if not name:
        raise RuntimeError(f"Watch action label does not include a stock name: {label!r}")
    return name


def add_watch_from_detail(adb: str, serial: str, artifacts_dir: Path, detail_root: ET.Element) -> str:
    node = find_node_matching(
        detail_root,
        lambda label: label.endswith(WATCH_ADD_SUFFIX) and label != WATCH_ADD_SUFFIX,
    )
    if node is None:
        raise RuntimeError(f"Cannot find detail watch add button. Visible labels: {visible_labels(detail_root)}")

    label = node_label(node)
    watch_name = _watch_name_from_label(label, WATCH_ADD_SUFFIX)
    print(f"[android-emulator-smoke] UI smoke: add watch item {watch_name}")
    tap_node(adb, serial, node, label)
    wait_for_node_matching(
        adb,
        serial,
        lambda candidate: watch_name in candidate and candidate.endswith(WATCH_REMOVE_SUFFIX),
        f"{watch_name} watch added state",
        timeout=20,
    )
    added = capture_step(adb, serial, artifacts_dir, "detail-watch-added")
    require_any_text(added, (f"{watch_name}{WATCH_REMOVE_SUFFIX}",))
    return watch_name


def verify_and_remove_watch_item(adb: str, serial: str, artifacts_dir: Path, watch_name: str) -> None:
    print(f"[android-emulator-smoke] UI smoke: verify watch item {watch_name}")
    wait_for_text(adb, serial, watch_name, timeout=25)
    watch = capture_step(adb, serial, artifacts_dir, "watch")
    require_text(watch, watch_name)
    require_any_text(watch, ("전체 1", "기업 1", "1/1개"))

    _, delete_node, delete_label = wait_for_node_matching(
        adb,
        serial,
        lambda label: watch_name in label and label.endswith(WATCH_DELETE_SUFFIX),
        f"{watch_name} watch delete button",
        timeout=20,
    )
    print(f"[android-emulator-smoke] UI smoke: remove watch item {watch_name}")
    tap_node(adb, serial, delete_node, delete_label)
    wait_for_node_matching(
        adb,
        serial,
        lambda label: label in {"전체 0", "기업 0", "0/0개"} or "관심 항목 없음" in label,
        "empty watchlist state",
        timeout=20,
    )
    removed = capture_step(adb, serial, artifacts_dir, "watch-after-remove")
    require_any_text(removed, ("관심 항목 없음", "전체 0", "기업 0", "0/0개"))


def run_account_login_screen(adb: str, serial: str, artifacts_dir: Path) -> None:
    print("[android-emulator-smoke] UI smoke: account login")
    tap_bottom_tab(adb, serial, "계정")
    wait_for_text(adb, serial, "로그인", timeout=25)
    account = capture_step(adb, serial, artifacts_dir, "account")
    for text in ("계정", "로그인", "이메일", "비밀번호", "새 계정 만들기"):
        require_text(account, text)


def run_account_session_flow(adb: str, serial: str, artifacts_dir: Path) -> None:
    print("[android-emulator-smoke] UI smoke: account session")
    run_account_login_screen(adb, serial, artifacts_dir)

    account_id = uuid.uuid4().hex[:10]
    local_part = f"qbemu{account_id}"
    password = f"Qa{account_id}Pass1"
    display_name = f"QA{account_id[:6]}"

    tap_exact_text(adb, serial, "새 계정 만들기", timeout=10)
    wait_for_text(adb, serial, "계정 만들기", timeout=10)
    tap_exact_text(adb, serial, "이름", timeout=10)
    input_text(adb, serial, display_name)
    tap_exact_text(adb, serial, "이메일", timeout=10)
    email = input_email(adb, serial, local_part)
    tap_exact_text(adb, serial, "비밀번호", timeout=10)
    input_text(adb, serial, password)
    run([adb, "-s", serial, "shell", "input", "keyevent", "4"], check=False)
    time.sleep(0.8)
    tap_exact_text(adb, serial, "가입하기", timeout=15)

    wait_for_text(adb, serial, email, timeout=45)
    signed_in = capture_step(adb, serial, artifacts_dir, "account-signed-in")
    for text in (email, "계정 관리", "로그아웃", "계정 삭제"):
        if find_node(signed_in, text) is None:
            signed_in = scroll_until_text(adb, serial, text, attempts=4)
        require_text(signed_in, text)

    print("[android-emulator-smoke] UI smoke: account session restore")
    restart_app(adb, serial)
    wait_for_text(adb, serial, "큐빗", timeout=35)
    tap_bottom_tab(adb, serial, "계정")
    wait_for_text(adb, serial, email, timeout=45)
    restored = capture_step(adb, serial, artifacts_dir, "account-restored")
    require_text(restored, email)

    print("[android-emulator-smoke] UI smoke: delete temporary account")
    root = scroll_until_text(adb, serial, "계정 삭제", attempts=4)
    tap_text(adb, serial, root, "계정 삭제")
    wait_for_text(adb, serial, "계정을 삭제할까요?", timeout=10)
    tap_exact_text(adb, serial, "삭제", timeout=10)
    wait_for_text(adb, serial, "새 계정 만들기", timeout=45)
    deleted = capture_step(adb, serial, artifacts_dir, "account-deleted")
    for text in ("로그인", "이메일", "비밀번호", "새 계정 만들기"):
        require_text(deleted, text)


def collect_logcat(adb: str, serial: str, path: Path) -> str:
    result = run([adb, "-s", serial, "logcat", "-d"], capture=True, check=False)
    path.write_text(result.stdout, encoding="utf-8")
    return result.stdout


def _okhttp_lines(logcat: str) -> list[str]:
    return [line for line in logcat.splitlines() if "okhttp.OkHttpClient" in line]


def _missing_required_200s(logcat: str, base_url: str) -> list[str]:
    missing = []
    for path in REQUIRED_ENDPOINTS:
        pattern = re.compile(r"<-- 200 .*" + re.escape(base_url.rstrip("/") + path))
        if not pattern.search(logcat):
            missing.append(path)
    return missing


def _missing_required_prefix_200s(logcat: str, base_url: str) -> list[str]:
    missing = []
    for prefix in DETAIL_ENDPOINT_PREFIXES:
        pattern = re.compile(r"<-- 200 .*" + re.escape(base_url.rstrip("/") + prefix))
        if not pattern.search(logcat):
            missing.append(prefix)
    return missing


def wait_for_required_api_logs(
    adb: str,
    serial: str,
    artifacts_dir: Path,
    base_url: str,
    timeout: float,
) -> str:
    deadline = time.monotonic() + timeout
    last_logcat = ""
    while time.monotonic() < deadline:
        last_logcat = collect_logcat(adb, serial, artifacts_dir / "logcat.txt")
        missing = _missing_required_200s(last_logcat, base_url)
        if not missing:
            for path in REQUIRED_ENDPOINTS:
                print(f"[android-emulator-smoke] staging 200 {path}")
            return last_logcat
        print(f"[android-emulator-smoke] waiting for staging 200s: {', '.join(missing)}")
        time.sleep(2)
    try:
        capture_step(adb, serial, artifacts_dir, "api-wait-timeout")
    except Exception as exc:
        print(f"[android-emulator-smoke] could not capture API timeout UI: {exc}")
    raise RuntimeError(f"Missing staging 200 logs for: {', '.join(_missing_required_200s(last_logcat, base_url))}")


def assert_logs(logcat: str, base_url: str, allow_local_fallback: bool) -> None:
    if "FATAL EXCEPTION" in logcat or "AndroidRuntime: FATAL" in logcat:
        raise RuntimeError("Android fatal exception found in logcat.")
    ok_http_lines = _okhttp_lines(logcat)
    if not allow_local_fallback and any("http://10.0.2.2:8000" in line for line in ok_http_lines):
        raise RuntimeError("Local emulator API fallback was used during staging smoke.")

    missing = _missing_required_200s(logcat, base_url)
    if missing:
        raise RuntimeError(f"Missing staging 200 logs for: {', '.join(missing)}")
    missing_prefixes = _missing_required_prefix_200s(logcat, base_url)
    if missing_prefixes:
        raise RuntimeError(f"Missing staging 200 logs for prefixes: {', '.join(missing_prefixes)}")


def _artifact_files(artifacts_dir: Path) -> list[str]:
    if not artifacts_dir.exists():
        return []
    return sorted(
        str(path.relative_to(artifacts_dir))
        for path in artifacts_dir.rglob("*")
        if path.is_file() and path.name != "report.json"
    )


def write_report(
    artifacts_dir: Path,
    *,
    status: str,
    base_url: str,
    profile: str,
    serial: str,
    error: str = "",
) -> None:
    payload = {
        "status": status,
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "base_url": base_url,
        "profile": profile,
        "serial": serial,
        "package": PACKAGE_NAME,
        "required_endpoints": list(REQUIRED_ENDPOINTS),
        "detail_endpoint_prefixes": list(DETAIL_ENDPOINT_PREFIXES),
        "home_pending_texts": list(HOME_PENDING_TEXTS),
        "artifacts": _artifact_files(artifacts_dir),
    }
    if error:
        payload["error"] = error
    (artifacts_dir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def run_ui_smoke(adb: str, serial: str, artifacts_dir: Path, profile: str) -> None:
    if profile not in SMOKE_PROFILES:
        raise RuntimeError(f"Unknown Android smoke profile: {profile}")

    home = capture_step(adb, serial, artifacts_dir, "home")
    for text in ("큐빗", "오늘의 요약", "오늘 확인할 3가지", "분석 탭", "인사이트 탭", "관심 탭", "계정 탭"):
        require_text(home, text)
    for text in HOME_PENDING_TEXTS:
        wait_for_absent_text(adb, serial, text, timeout=20)
    home_ready = capture_step(adb, serial, artifacts_dir, "home-ready")
    for text in HOME_PENDING_TEXTS:
        require_absent_text(home_ready, text)

    print("[android-emulator-smoke] UI smoke: analysis list")
    tap_bottom_tab(adb, serial, "분석")
    wait_for_text(adb, serial, "기업 순위", timeout=60)
    analysis = capture_step(adb, serial, artifacts_dir, "analysis")
    for text in ("기업", "유형", "일반", "스몰캡", "기업 순위"):
        require_text(analysis, text)

    print("[android-emulator-smoke] UI smoke: stock detail")
    _, row = wait_for_stock_row(adb, serial, timeout=60)
    tap_node(adb, serial, row, "analysis stock row")
    wait_for_text(adb, serial, "요약", timeout=45)
    detail = capture_step(adb, serial, artifacts_dir, "detail-overview")
    for text in ("요약", "차트", "재무", "데이터"):
        require_text(detail, text)
    require_any_text(detail, ("핵심 요약", "판단 요약", "밸류에이션"))
    watched_name = add_watch_from_detail(adb, serial, artifacts_dir, detail)

    tap_top_exact_text(adb, serial, "차트", timeout=20)
    wait_for_text(adb, serial, "6달", timeout=35)
    detail_chart = capture_step(adb, serial, artifacts_dir, "detail-chart")
    for text in ("차트", "1달", "3달", "6달"):
        require_text(detail_chart, text)

    tap_top_exact_text(adb, serial, "재무", timeout=20)
    wait_for_text(adb, serial, "시장 정보", timeout=35)
    detail_financial = capture_step(adb, serial, artifacts_dir, "detail-financial")
    require_text(detail_financial, "시장 정보")

    tap_top_exact_text(adb, serial, "데이터", timeout=20)
    wait_for_text(adb, serial, "데이터 신뢰도", timeout=35)
    detail_data = capture_step(adb, serial, artifacts_dir, "detail-data")
    require_text(detail_data, "데이터 신뢰도")

    run([adb, "-s", serial, "shell", "input", "keyevent", "4"])
    time.sleep(1.4)

    print("[android-emulator-smoke] UI smoke: small-cap filter")
    tap_bottom_tab(adb, serial, "분석")
    wait_for_text(adb, serial, "스몰캡", timeout=25)
    root = dump_ui_xml(adb, serial)
    tap_text(adb, serial, root, "스몰캡")
    wait_for_text(adb, serial, "기업 순위", timeout=60)
    smallcap = capture_step(adb, serial, artifacts_dir, "analysis-smallcap")
    for text in ("스몰캡", "기업 순위"):
        require_text(smallcap, text)

    print("[android-emulator-smoke] UI smoke: insights")
    tap_bottom_tab(adb, serial, "인사이트")
    wait_for_text(adb, serial, "실적", timeout=35)
    insights = capture_step(adb, serial, artifacts_dir, "insights")
    for text in ("실적", "뉴스", "이벤트"):
        require_text(insights, text)
    require_any_text(insights, ("US", "미국"))
    require_any_text(insights, ("KR", "국내"))

    print("[android-emulator-smoke] UI smoke: watch")
    tap_bottom_tab(adb, serial, "관심")
    wait_for_text(adb, serial, "관심", timeout=25)
    verify_and_remove_watch_item(adb, serial, artifacts_dir, watched_name)

    print("[android-emulator-smoke] UI smoke: account")
    if profile == "full":
        run_account_session_flow(adb, serial, artifacts_dir)
    else:
        run_account_login_screen(adb, serial, artifacts_dir)


def resolve_base_url(explicit: str) -> str:
    props = _local_properties()
    candidates = [
        explicit,
        os.getenv("QUANT_STAGING_API_URL", ""),
        props.get("quantApiDebugBaseUrl", ""),
        props.get("quantApiBaseUrl", ""),
        DEFAULT_BASE_URL,
    ]
    for candidate in candidates:
        clean = str(candidate or "").strip().rstrip("/")
        if clean:
            return clean
    return DEFAULT_BASE_URL


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Android emulator staging smoke QA")
    parser.add_argument("--url", default="", help="Staging API base URL")
    parser.add_argument("--avd", default=DEFAULT_AVD, help="AVD name to start if no emulator is running")
    parser.add_argument("--serial", default="", help="Use an already-running emulator serial")
    parser.add_argument("--artifacts-dir", default="", help="Directory for screenshots, UI XML, and logs")
    parser.add_argument("--boot-timeout", type=float, default=180)
    parser.add_argument("--api-wait-timeout", type=float, default=90)
    parser.add_argument("--settle-seconds", type=float, default=3)
    parser.add_argument(
        "--profile",
        choices=SMOKE_PROFILES,
        default="quick",
        help="quick checks core app screens; full also creates, restores, and deletes a temporary account",
    )
    parser.add_argument("--skip-tests", action="store_true", help="Skip :app:testDebugUnitTest before install")
    parser.add_argument("--allow-local-fallback", action="store_true", help="Allow fallback calls to 10.0.2.2")
    parser.add_argument("--keep-emulator", action="store_true", help="Do not shut down an emulator started by this script")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base_url = resolve_base_url(args.url)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    artifacts_dir = Path(args.artifacts_dir or ROOT / "artifacts" / "android-emulator-smoke" / stamp)
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    adb = _tool_path("adb")
    emulator = _tool_path("emulator")
    started_proc: subprocess.Popen | None = None
    serial = ""

    print(f"[android-emulator-smoke] base_url={base_url}")
    print(f"[android-emulator-smoke] artifacts={artifacts_dir}")
    print(f"[android-emulator-smoke] profile={args.profile}")

    try:
        if args.serial:
            serial = args.serial
            if serial not in emulator_devices(adb):
                raise SystemExit(f"Requested emulator serial is not connected: {serial}")
            wait_for_boot(adb, serial, args.boot_timeout)
        else:
            serial, started_proc = ensure_emulator(adb, emulator, args.avd, artifacts_dir, args.boot_timeout)

        build_and_install(adb, serial, base_url, skip_tests=args.skip_tests)
        launch_app(adb, serial)
        pid = wait_for_app(adb, serial, timeout=20)
        print(f"[android-emulator-smoke] app running pid={pid}")
        logcat = wait_for_required_api_logs(adb, serial, artifacts_dir, base_url, timeout=args.api_wait_timeout)
        time.sleep(args.settle_seconds)

        run_ui_smoke(adb, serial, artifacts_dir, profile=args.profile)
        logcat = collect_logcat(adb, serial, artifacts_dir / "logcat.txt")
        assert_logs(logcat, base_url, allow_local_fallback=args.allow_local_fallback)

        write_report(
            artifacts_dir,
            status="OK",
            base_url=base_url,
            profile=args.profile,
            serial=serial,
        )
        print("[android-emulator-smoke] OK")
        return 0
    except Exception as exc:
        write_report(
            artifacts_dir,
            status="FAIL",
            base_url=base_url,
            profile=args.profile,
            serial=serial,
            error=f"{type(exc).__name__}: {exc}",
        )
        raise
    finally:
        if started_proc is not None and serial and not args.keep_emulator:
            run([adb, "-s", serial, "emu", "kill"], check=False)


if __name__ == "__main__":
    raise SystemExit(main())

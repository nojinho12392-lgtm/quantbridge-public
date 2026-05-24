"""DAG-style pipeline runner.

Prefect is used when installed. If the environment does not have Prefect yet,
the same step graph runs sequentially with identical subprocess semantics. This
keeps local development stable while allowing production deployment to move to
Prefect without rewriting every legacy step at once.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from quantbridge.storage import QuantRepository


@dataclass(frozen=True)
class PipelineStep:
    script: str
    description: str
    label: str = ""
    optional: bool = False


def _run_subprocess(step: PipelineStep, cwd: Path, env: dict[str, str], timeout: int = 21600) -> dict:
    started = time.time()
    result = subprocess.run(
        [sys.executable, step.script],
        cwd=str(cwd),
        env=env,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
    )
    elapsed = time.time() - started
    if result.returncode != 0:
        stderr = "\n".join(result.stderr.strip().splitlines()[-20:])
        raise RuntimeError(f"{step.script} failed with exit {result.returncode}\n{stderr}")
    return {"script": step.script, "elapsed_sec": round(elapsed, 3)}


def _base_env(test_mode: bool = False, analyze_only: bool = False, smallcap_market: str | None = None) -> dict[str, str]:
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    for key in ("PREFECT_SERVER_ANALYTICS_ENABLED", "PREFECT_API_ENABLE_TELEMETRY", "PREFECT_TELEMETRY_ENABLED"):
        os.environ.setdefault(key, "false")
        env.setdefault(key, "false")
    if test_mode:
        env["QUANT_TEST_MODE"] = "true"
    if analyze_only:
        env["QUANT_ANALYZE_ONLY"] = "true"
    if smallcap_market:
        env["QUANT_SMALLCAP_MARKET"] = smallcap_market
    return env


def run_steps(
    steps: Iterable[PipelineStep],
    cwd: Path,
    test_mode: bool = False,
    analyze_only: bool = False,
    smallcap_market: str | None = None,
    use_prefect: bool = True,
) -> list[dict]:
    step_list = list(steps)
    env = _base_env(test_mode, analyze_only, smallcap_market)
    run_id = f"quantbridge-{int(time.time())}-{uuid.uuid4().hex[:8]}"
    repo = QuantRepository()
    runner = "prefect" if use_prefect else "local"
    repo.record_run(
        run_id,
        runner,
        "running",
        {"steps": [step.script for step in step_list], "test_mode": test_mode, "analyze_only": analyze_only},
    )

    if use_prefect:
        try:
            from prefect import flow, task
        except Exception:
            use_prefect = False
            runner = "local"

    try:
        if not use_prefect:
            out = []
            for step in step_list:
                try:
                    out.append(_run_subprocess(step, cwd, env))
                except Exception:
                    if step.optional:
                        out.append({"script": step.script, "optional_failed": True})
                        continue
                    raise
            repo.record_run(run_id, runner, "success", {"results": out})
            return out

        from prefect import flow, task

        @task(name="run_quant_step")
        def _task(step: PipelineStep) -> dict:
            try:
                return _run_subprocess(step, cwd, env)
            except Exception:
                if step.optional:
                    return {"script": step.script, "optional_failed": True}
                raise

        @flow(name="quantbridge_pipeline")
        def _flow() -> list[dict]:
            return [_task(step) for step in step_list]

        out = _flow()
        repo.record_run(run_id, runner, "success", {"results": out})
        return out
    except Exception as exc:
        repo.record_run(run_id, runner, "failed", {"error": str(exc)})
        raise

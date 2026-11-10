"""명령행 진입점.

  python -m req_ai demo
      녹화된 LLM 응답 + 가상 앱으로 전체 흐름 시연 (API 키·단말 불필요)

  python -m req_ai run --old v1.md --new v2.md --policy 정책.md --registry registry.json \
                       --regression 회귀폴더 [--device appium --capabilities caps.json] [--record 녹화폴더]
      실제 LLM 으로 실행 (ANTHROPIC_API_KEY 필요)

  python -m req_ai check-code <파일...> --screens <화면 덤프 폴더> [--package com.sampleapp]
      AI 가 만든 테스트 코드 검증 게이트. 차단 항목이 있으면 종료 코드 1
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import code_checker
from .agent import AgentConfig
from .device import AppiumDevice, FakeDevice
from .llm import AnthropicLLM, RecordingLLM, ReplayLLM
from .pipeline import Inputs, run

ROOT = Path(__file__).resolve().parent.parent
SAMPLES = ROOT / "samples"


def _fake_env(flow: Path) -> dict[str, str]:
    return {"QA_DEVICE": "fake", "QA_PROJECT_ROOT": str(ROOT), "QA_FAKE_FLOW": str(flow), "QA_TIMEOUT": "1"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="req_ai")
    sub = parser.add_subparsers(dest="command", required=True)

    demo = sub.add_parser("demo")
    demo.add_argument("--out", type=Path, default=ROOT / "out")

    r = sub.add_parser("run")
    r.add_argument("--old", type=Path, required=True)
    r.add_argument("--new", type=Path, required=True)
    r.add_argument("--policy", type=Path, required=True)
    r.add_argument("--registry", type=Path, required=True)
    r.add_argument("--regression", type=Path, required=True)
    r.add_argument("--device", choices=["fake", "appium"], default="fake")
    r.add_argument("--flow", type=Path, default=SAMPLES / "app" / "flow.json")
    r.add_argument("--server", default="http://127.0.0.1:4723")
    r.add_argument("--capabilities", type=Path)
    r.add_argument("--screens", type=Path)
    r.add_argument("--record", type=Path)
    r.add_argument("--allow-destructive", action="store_true")
    r.add_argument("--out", type=Path, default=ROOT / "out")

    c = sub.add_parser("check-code")
    c.add_argument("files", type=Path, nargs="+")
    c.add_argument("--screens", type=Path, required=True)
    c.add_argument("--package", default="")

    args = parser.parse_args(argv)
    if args.command == "demo":
        return _demo(args.out)
    if args.command == "run":
        return _run(args)
    return _check(args)


def _demo(out: Path) -> int:
    flow = SAMPLES / "app" / "flow.json"
    device = FakeDevice(flow)
    inputs = Inputs(
        spec_old=(SAMPLES / "spec" / "order_status_v1.md").read_text(encoding="utf-8"),
        spec_new=(SAMPLES / "spec" / "order_status_v2.md").read_text(encoding="utf-8"),
        policy_md=(SAMPLES / "policy" / "order_policy.md").read_text(encoding="utf-8"),
        registry=SAMPLES / "regression" / "registry.json",
        regression_dir=SAMPLES / "regression",
        screens_dir=SAMPLES / "app",
        app_package="com.sampleapp",
        run_env=_fake_env(flow),
    )
    run(inputs, ReplayLLM(SAMPLES / "replay"), device, device.reset, out)
    print((out / "report.md").read_text(encoding="utf-8"))
    print(f"\n결과 폴더: {out}")
    return 0


def _run(args: argparse.Namespace) -> int:
    llm = AnthropicLLM()
    if args.record:
        llm = RecordingLLM(llm, args.record)
    package = ""
    if args.device == "appium":
        caps_text = args.capabilities.read_text(encoding="utf-8")
        caps = json.loads(caps_text)
        package = caps.get("appium:appPackage", "")
        device = AppiumDevice(args.server, caps)

        def reset() -> None:
            device.driver.terminate_app(package)
            device.driver.activate_app(package)

        env = {"APPIUM_CAPS_JSON": caps_text, "APPIUM_SERVER": args.server}
    else:
        device = FakeDevice(args.flow)
        reset, env = device.reset, _fake_env(args.flow)
    inputs = Inputs(args.old.read_text(encoding="utf-8"), args.new.read_text(encoding="utf-8"),
                    args.policy.read_text(encoding="utf-8"), args.registry, args.regression,
                    args.screens, package, env)
    try:
        run(inputs, llm, device, reset, args.out, AgentConfig(allow_destructive=args.allow_destructive))
    finally:
        if isinstance(device, AppiumDevice):
            device.quit()
    print((args.out / "report.md").read_text(encoding="utf-8"))
    return 0


def _check(args: argparse.Namespace) -> int:
    screens = code_checker.load_screens(args.screens)
    blocked = False
    for path in args.files:
        report = code_checker.check_code(path, screens, args.package)
        print(f"\n== {path}  →  {report.verdict}")
        for f in report.findings:
            print(f"  [{f.severity}] line {f.line}: {f.rule}\n      {f.snippet}")
        blocked |= report.verdict == "차단"
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())

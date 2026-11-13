#!/usr/bin/env python3
"""端到端冒烟：LOCAL / 离线降级 / 风控拦截 / HYBRID 协同 / CLOUD 全权 / 内置自检。

用法：python3 tools/smoke.py
输出 JSON 报告，任一断言失败退出码为 1。
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

RUNTIME = Path(__file__).resolve().parent.parent
CP = f"{RUNTIME / 'out/nm-headless.jar'}:{RUNTIME / 'libs/json.jar'}"
WORK = Path(os.environ.get("NM_SMOKE_ROOT", "/tmp/nm-smoke-v12"))
PORT = 8787
PORT_RISK = 8788
RESULTS = []


def log(msg):
    """进度写 stderr，stdout 只留 JSON 报告，便于 CI 直接解析。"""
    print(msg, file=sys.stderr, flush=True)


def java_cli(*args, stdin_text=None, env_extra=None, timeout=120):
    env = os.environ.copy()
    if env_extra:
        env.update(env_extra)
    cmd = ["java", "-cp", CP, "nanomuse.headless.MainKt", *args, "--root", str(WORK), "--json"]
    p = subprocess.run(cmd, input=stdin_text, capture_output=True, text=True,
                       timeout=timeout, env=env)
    try:
        return json.loads(p.stdout.strip()), p
    except Exception:
        return {"_parse_error": p.stdout[-800:] + "\n" + p.stderr[-800:]}, p


def events(session):
    f = WORK / "events" / f"{session}.ndjson"
    if not f.exists():
        return []
    return [json.loads(x) for x in f.read_text().splitlines() if x.strip()]


def types(session):
    return [e.get("type") for e in events(session)]


def check(name, ok, detail=""):
    RESULTS.append({"name": name, "ok": bool(ok), "detail": detail})
    log(("  PASS " if ok else "  FAIL ") + name + (f" — {detail}" if detail else ""))


def wait_port(port, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.3):
                return True
        except OSError:
            time.sleep(0.15)
    return False


def start_fake(port, scenario="default"):
    env = os.environ.copy()
    env["NM_SCENARIO"] = scenario
    log = open(f"/tmp/nm-fake-{port}.log", "w")
    p = subprocess.Popen([sys.executable, str(RUNTIME / "tools/fake_model.py"), str(port)],
                         stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_port(port):
        raise RuntimeError(f"fake model on {port} did not start")
    return p


def main():
    # 每次从干净目录开始：同一 sessionId 会复用历史（断点续跑），重复跑会串轮次
    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pkill", "-f", "tools/fake_model.py"], capture_output=True)
    time.sleep(0.3)
    procs = [start_fake(PORT, "default"), start_fake(PORT_RISK, "destructive")]

    try:
        # ── 1. LOCAL 主链路 ──────────────────────────────────────────
        log("[1] LOCAL 本地闭环")
        r, p = java_cli("--task", "写一个 hello.txt 再读回来", "--mode", "local",
                        "--session", "local-1", "--auto-approve")
        t = types("local-1")
        check("local.mode_and_finish", r.get("mode") == "LOCAL" and r.get("stopped_by") == "done",
              f"mode={r.get('mode')} stopped_by={r.get('stopped_by')}")
        check("local.turns_and_tools", r.get("turns") == 3 and r.get("tool_calls") == 2,
              f"turns={r.get('turns')} tool_calls={r.get('tool_calls')}")
        check("local.event_stream", all(x in t for x in
              ["turn_start", "turn", "tool_start", "tool_result", "text", "done"]), str(t[:12]))
        check("local.five_layer_events", all(x in t for x in
              ["router_decision", "task_enqueue", "session_state", "tool_dispatch"]), str(t[:12]))
        check("local.workspace_file", (WORK / "workspaces/local-1/hello.txt").exists())

        # ── 2. 离线自动降级 ──────────────────────────────────────────
        log("[2] 断网降级 LOCAL")
        r2, _ = java_cli("--task", "离线也要能跑", "--session", "offline-1", "--offline", "--auto-approve")
        check("offline.forced_local", r2.get("mode") == "LOCAL" and "断网" in r2.get("decision_reason", ""),
              r2.get("decision_reason", ""))

        # ── 3. 风控拦截（审批输入 n 立即拒绝） ────────────────────────
        log("[3] 高危命令风控拦截")
        r3, _ = java_cli(
            "--task", "用 shell_execute 执行 destructive 冒烟", "--mode", "local", "--session", "risk-1",
            stdin_text="n\n",
            env_extra={"NM_BASE_URL": f"http://127.0.0.1:{PORT_RISK}/v1"},
        )
        tr = types("risk-1")
        check("risk.gate_denied", "approval" in tr and any(
            e.get("outcome") == "DENY" for e in events("risk-1") if e.get("type") == "approval"),
              str([e for e in events("risk-1") if e.get("type") == "approval"]))
        check("risk.file_not_deleted", (WORK / "workspaces/risk-1/hello.txt").exists())

        # ── 4. HYBRID 端云协同闭环 ───────────────────────────────────
        log("[4] HYBRID 端云协同闭环")
        r4, _ = java_cli("--task", "端云协同跑一遍", "--mode", "hybrid", "--session", "hybrid-1")
        t4 = types("hybrid-1")
        check("hybrid.mode", r4.get("mode") == "HYBRID", f"mode={r4.get('mode')}")
        check("hybrid.two_tool_calls", r4.get("tool_calls") == 2 and r4.get("envelopes", 0) >= 3,
              f"tool_calls={r4.get('tool_calls')} envelopes={r4.get('envelopes')}")
        check("hybrid.bus_events", all(x in t4 for x in ["bus_send", "bus_ack", "bus_inbound"]),
              str(t4[:14]))
        check("hybrid.same_risk_chain", "offload_gate" in t4 and "tool_dispatch" in t4)
        check("hybrid.cloud_tool_result_files", (WORK / "workspaces/hybrid-1/hybrid.txt").exists())
        check("hybrid.no_dead_letter", r4.get("dead_letters") == 0, str(r4.get("dead_letters")))

        # ── 5. CLOUD 全权，本地内核零启动 ────────────────────────────
        log("[5] CLOUD 云端全权")
        r5, _ = java_cli("--task", "介绍一下端云协同", "--mode", "cloud", "--session", "cloud-1")
        t5 = types("cloud-1")
        check("cloud.mode", r5.get("mode") == "CLOUD", f"mode={r5.get('mode')}")
        check("cloud.no_local_kernel", r5.get("turns") == 0 and "tool_result" not in t5
              and "turn_start" not in t5)
        check("cloud.stream_and_status", "bus_inbound" in t5 and "cloud" in r5.get("text", "").lower(),
              r5.get("text", ""))

        # ── 6. 内置自检 ──────────────────────────────────────────────
        log("[6] 内置自检（31 项）")
        p6 = subprocess.run(
            ["java", "-cp", CP, "nanomuse.headless.MainKt", "--self-test",
             "--root", str(WORK / "selftest")],
            capture_output=True, text=True, timeout=120,
        )
        st = json.loads(p6.stdout.strip())
        check("selftest.all_passed", st.get("all_passed") is True,
              f"{st.get('passed')}/{st.get('total')}")
    finally:
        for p in procs:
            p.terminate()
        subprocess.run(["pkill", "-f", "tools/fake_model.py"], capture_output=True)

    passed = sum(1 for c in RESULTS if c["ok"])
    report = {"suite": "nm-headless-runtime V1.2 e2e smoke",
              "total": len(RESULTS), "passed": passed, "failed": len(RESULTS) - passed,
              "all_passed": passed == len(RESULTS), "cases": RESULTS}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["all_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())

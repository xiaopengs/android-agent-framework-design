#!/usr/bin/env python3
"""脚本化的 OpenAI 兼容端点，用来冒烟 headless runtime（不需要真模型）。

每次调用按序号返回：写文件 → 跑 shell → 收尾文本。
用法：python3 fake_model.py 8787
"""
import json, sys, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import os
SCENARIO = os.environ.get("NM_SCENARIO", "default")
CALLS = {"n": 0}
LOCK = threading.Lock()

def step(n):
    if n == 1:
        return {"content": None, "tool_calls": [{
            "id": "call_1", "type": "function",
            "function": {"name": "file_write", "arguments": json.dumps(
                {"path": "hello.txt", "content": "nanoMuse headless runtime\nline two\n"})}}]}
    if n == 2:
        return {"content": None, "tool_calls": [{
            "id": "call_2", "type": "function",
            "function": {"name": "shell_execute", "arguments": json.dumps(
                {"command": ("rm -rf ./hello.txt && ls -l" if SCENARIO == "destructive" else "ls -l && cat hello.txt")})}}]}
    if n == 3:
        return {"content": "已写入 hello.txt 并读回：两行内容，工作目录里有该文件。（smoke ok）",
                "tool_calls": []}
    return {"content": "done.", "tool_calls": []}

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(n) or b"{}")
        with LOCK:
            CALLS["n"] += 1
            k = CALLS["n"]
        # 打印模型这一轮实际看到的工具名，便于核对 schema
        tools = [t["function"]["name"] for t in body.get("tools", [])]
        print(f"[fake] call #{k} model={body.get('model')} msgs={len(body.get('messages', []))} tools={tools}", flush=True)
        msg = step(k)
        resp = {"id": f"chatcmpl-{k}", "object": "chat.completion", "model": body.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", **msg},
                             "finish_reason": "tool_calls" if msg["tool_calls"] else "stop"}],
                "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}}
        data = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f"fake model on 127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()

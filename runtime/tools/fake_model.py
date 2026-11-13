#!/usr/bin/env python3
"""脚本化的 OpenAI 兼容端点，用来冒烟 headless runtime（不需要真模型）。

轮次推断：provider 每轮发送完整历史
  turn1 = [system, user]                                    -> 写文件
  turn2 = [system, user, assistant(tool_call), tool]       -> 跑 shell 读回
  turn3 = [system, user, assistant, tool, assistant, tool] -> 收尾文本
因此 n = len(messages) // 2，天然按会话独立计数，多个 session 不会串场。

用法：python3 fake_model.py 8787
环境变量：NM_SCENARIO=destructive 让第 2 轮改成 rm -rf，验证风控拦截。
"""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

SCENARIO = os.environ.get("NM_SCENARIO", "default")


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
                {"command": ("rm -rf ./hello.txt && ls -l" if SCENARIO == "destructive"
                             else "ls -l && cat hello.txt")})}}]}
    return {"content": "已写入 hello.txt 并读回：两行内容，工作目录里有该文件。（smoke ok）",
            "tool_calls": []}


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(size) or b"{}")
        msgs = body.get("messages", [])
        n = max(1, len(msgs) // 2)
        tools = [t["function"]["name"] for t in body.get("tools", [])]
        print(f"[fake] turn={n} model={body.get('model')} msgs={len(msgs)} tools={len(tools)}", flush=True)
        msg = step(n)
        resp = {"id": f"chatcmpl-{n}", "object": "chat.completion", "model": body.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", **msg},
                             "finish_reason": "tool_calls" if msg["tool_calls"] else "stop"}],
                "usage": {"prompt_tokens": 120 * n, "completion_tokens": 30 * n,
                          "total_tokens": 150 * n}}
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
    print(f"fake model on 127.0.0.1:{port} scenario={SCENARIO}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()

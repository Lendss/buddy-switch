#!/usr/bin/env python3
"""Calls Buddy's phone tools from the command line, for testing: the `phone` MCP server from ~/.claude.json.

    tools/mcp-call.py list                      the tools
    tools/mcp-call.py read_screen               a tool without arguments
    tools/mcp-call.py tap '{"element": 5}'      a tool with JSON arguments
"""
import json
import os
import sys
import time
import urllib.request

srv = json.load(open(os.path.expanduser("~/.claude.json")))["mcpServers"]["phone"]
HEADERS = {**srv["headers"], "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
session = None


def rpc(method, params=None, id=1):
    global session
    h = dict(HEADERS)
    if session:
        h["Mcp-Session-Id"] = session
    body = {"jsonrpc": "2.0", "method": method, "params": params or {}}
    if id is not None:
        body["id"] = id
    r = urllib.request.urlopen(urllib.request.Request(srv["url"], json.dumps(body).encode(), h), timeout=600)
    session = r.headers.get("Mcp-Session-Id") or session
    text = r.read().decode()
    if "data:" in text[:20]:  # an SSE reply: the last data line
        text = [l[5:] for l in text.splitlines() if l.startswith("data:")][-1]
    return json.loads(text) if text.strip() else {}


rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-call", "version": "1"}})
rpc("notifications/initialized", id=None)
if sys.argv[1] == "list":
    for t in rpc("tools/list", id=2)["result"]["tools"]:
        print(f"- {t['name']}: {t.get('description', '')[:110]}")
else:
    t0 = time.time()
    r = rpc("tools/call", {"name": sys.argv[1], "arguments": json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}}, id=3)
    for c in r.get("result", {}).get("content", []):
        print(c.get("text", f"<{c.get('type')}: {len(c.get('data', ''))} bytes>"))
    if "error" in r:
        print(r["error"])
    print(f"[{(time.time() - t0) * 1000:.0f} ms]", file=sys.stderr)

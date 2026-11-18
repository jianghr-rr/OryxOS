"""LOCAL TEST ONLY: actual MCP stdio child, with a loopback news source."""
import json
import os
import sys
import urllib.request

for line in sys.stdin:
    request = json.loads(line)
    method = request.get("method")
    if "id" not in request:
        continue
    params = request.get("params", {})
    if method == "initialize":
        result = {"protocolVersion": params["protocolVersion"], "capabilities": {"tools": {}},
                  "serverInfo": {"name": "LOCAL-kernel-news", "version": "1"}}
    elif method == "tools/list":
        result = {"tools": [{"name": "search", "description": "LOCAL controlled news data",
                  "inputSchema": {"type": "object", "properties": {"query": {"type": "string"},
                                  "date": {"type": "string"}}, "required": ["query", "date"],
                                  "additionalProperties": False}}]}
    elif method == "tools/call":
        with open(sys.argv[2], "a") as log:
            log.write(json.dumps({"pid": os.getpid(), "name": params["name"],
                                  "arguments": params["arguments"],
                                  "providerSecretInherited": "EXAMPLE_MODEL_KEY" in os.environ}) + "\n")
        try:
            with urllib.request.urlopen(sys.argv[1], timeout=3) as response:
                value = json.loads(response.read())
            result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": False}
        except Exception:
            result = {"content": [{"type": "text", "text": "LOCAL news source failed"}], "isError": True}
    elif method == "ping":
        result = {}
    else:
        print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": "unsupported"}}), flush=True)
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)

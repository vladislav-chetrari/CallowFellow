"""Minimal MCP server (stdio, JSON-RPC 2.0) for CallowFellow dev-assistant.

Exposes project context as MCP tools (Week 6, Task 1 — День 31):
  - get_git_branch — current git branch (+ commit), minimum requirement
  - list_files     — file listing (optional requirement)
  - get_diff       — git diff / status (optional requirement)

Protocol: line-delimited JSON over stdin/stdout. Implements the subset of MCP
used by assistant.py: initialize, notifications/initialized, tools/list,
tools/call. No third-party dependencies.

Project root: parent dir of this file by default, override with
CALLOWFELLOW_ROOT env var.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys

PROJECT_ROOT = os.path.abspath(
    os.environ.get(
        "CALLOWFELLOW_ROOT",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."),
    )
)

EXCLUDED_DIRS = {".git", ".idea", ".gradle", "build", ".kotlin", "models"}
EXCLUDED_SUFFIXES = (".litertlm", ".gguf", ".tflite", ".mp4", ".pyc")
MAX_DIFF_CHARS = 8000
MAX_FILES = 300


def _run_git(*args: str) -> tuple[bool, str]:
    try:
        p = subprocess.run(
            ["git", *args],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=15,
        )
        return p.returncode == 0, (p.stdout.strip() or p.stderr.strip())
    except FileNotFoundError:
        return False, "git executable not found"
    except Exception as e:  # noqa: BLE001
        return False, f"git error: {e}"


def tool_get_git_branch(_args: dict) -> dict:
    ok, branch = _run_git("branch", "--show-current")
    if not ok or not branch:
        ok2, fallback = _run_git("rev-parse", "--abbrev-ref", "HEAD")
        if ok2 and fallback:
            branch = fallback
            ok = True
    ok_c, commit = _run_git("rev-parse", "--short", "HEAD")
    ok_s, status = _run_git("status", "--short")
    result: dict = {
        "branch": branch if ok else "unknown",
        "commit": commit if ok_c else "unknown",
        "clean": (status == "") if ok_s else None,
        "status_short": status if ok_s else "",
        "root": PROJECT_ROOT,
    }
    if not ok:
        result["error"] = branch
    return result


def _excluded(rel: str) -> bool:
    parts = rel.split(os.sep)
    if any(p in EXCLUDED_DIRS or p == "__pycache__" for p in parts):
        return True
    return rel.endswith(EXCLUDED_SUFFIXES)


def tool_list_files(args: dict) -> dict:
    prefix = (args.get("prefix") or "").strip().strip("/")
    limit = args.get("limit", MAX_FILES)
    try:
        limit = max(1, min(int(limit), 1000))
    except (TypeError, ValueError):
        limit = MAX_FILES
    base = os.path.join(PROJECT_ROOT, prefix) if prefix else PROJECT_ROOT
    if not os.path.isdir(base):
        return {"prefix": prefix, "files": [], "error": f"not a directory: {prefix}"}
    out: list[str] = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDED_DIRS and d != "__pycache__"]
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, PROJECT_ROOT)
            if _excluded(rel):
                continue
            out.append(rel)
            if len(out) >= limit:
                return {"prefix": prefix, "files": out, "truncated": True}
    return {"prefix": prefix, "files": out, "truncated": False}


def tool_get_diff(args: dict) -> dict:
    stat_limit = str(args.get("stat_only") or "").lower() in ("1", "true", "yes")
    ok_stat, stat = _run_git("diff", "HEAD", "--stat")
    ok_diff, diff = _run_git("diff", "HEAD", "--", ".")
    ok_status, status = _run_git("status", "--short")
    truncated = len(diff) > MAX_DIFF_CHARS
    if truncated:
        diff = diff[:MAX_DIFF_CHARS] + "\n…[truncated]"
    result = {
        "stat": stat if ok_stat else "",
        "status_short": status if ok_status else "",
        "has_changes": bool((stat or status).strip()),
    }
    if not stat_limit:
        result["diff"] = diff if ok_diff else ""
    if truncated:
        result["truncated"] = True
    return result


TOOLS = {
    "get_git_branch": {
        "description": "Current git branch and commit of the project.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    "list_files": {
        "description": "List project files (excludes .git/build/models/weights).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "prefix": {"type": "string", "description": "Subdirectory, e.g. docs or shared/src/task3"},
                "limit": {"type": "integer", "description": "Max files, default 300"},
            },
        },
    },
    "get_diff": {
        "description": "git diff HEAD plus short status.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "stat_only": {"type": "string", "description": "If 'true', return only --stat"},
            },
        },
    },
}

HANDLERS = {
    "get_git_branch": tool_get_git_branch,
    "list_files": tool_list_files,
    "get_diff": tool_get_diff,
}


def _response(req_id, result=None, error=None) -> dict:
    resp: dict = {"jsonrpc": "2.0", "id": req_id}
    if error is not None:
        resp["error"] = error
    else:
        resp["result"] = result if result is not None else {}
    return resp


def handle(msg: dict):
    req_id = msg.get("id")
    method = msg.get("method", "")
    params = msg.get("params") or {}
    if method == "initialize":
        return _response(
            req_id,
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "callowfellow-mcp", "version": "0.1.0"},
            },
        )
    if method in ("notifications/initialized", "notifications/cancelled"):
        return None  # notification, no response
    if method == "ping":
        return _response(req_id, {})
    if method == "tools/list":
        return _response(
            req_id,
            {
                "tools": [
                    {"name": name, **spec} for name, spec in TOOLS.items()
                ]
            },
        )
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") or {}
        handler = HANDLERS.get(name)
        if handler is None:
            return _response(req_id, error={"code": -32602, "message": f"unknown tool: {name}"})
        try:
            data = handler(args if isinstance(args, dict) else {})
            return _response(req_id, {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]})
        except Exception as e:  # noqa: BLE001
            return _response(req_id, error={"code": -32603, "message": f"{name} failed: {e}"})
    return _response(req_id, error={"code": -32601, "message": f"unknown method: {method}"})


def main() -> None:
    stdin = sys.stdin
    stdout = sys.stdout
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        resp = handle(msg if isinstance(msg, dict) else {})
        if resp is not None:
            stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            stdout.flush()


if __name__ == "__main__":
    main()

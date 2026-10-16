"""CallowFellow developer assistant — Week 6, Task 1 (День 31).

/help assistant that answers questions about the project using:
  - RAG over README.md + docs/*.md (+ API/data schemas inside docs/07-api.md,
    docs/08-hranilishche-dannyh.md) — TF-IDF retrieval, stdlib only, offline;
  - MCP project context — get_git_branch (minimum), list_files / get_diff
    (optional) via assistant/mcp_server.py over stdio JSON-RPC.

LLM backend: DeepSeek chat API (OpenAI-compatible) if DEEPSEEK_API_KEY is set.
Without a key it works in extractive fallback mode (prints top chunks +
branch) — the minimal result (RAG + MCP + /help) stays reproducible offline.

Usage:
  python3 assistant/assistant.py                 # interactive REPL
  python3 assistant/assistant.py --help-question "Где хранятся чанки RAG?"
  echo "/help Где лежит каталог моделей?" | python3 assistant/assistant.py
  python3 assistant/assistant.py --no-llm --help-question "..."

REPL commands: /help <question> | /branch | /files [prefix] | /diff | /quit
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import urllib.request

TOKEN_RE = re.compile(r"[a-zа-яё0-9]+", re.IGNORECASE)
HEADER_RE = re.compile(r"^(#{1,4})\s+(.*)")
CHUNK_FALLBACK_CHARS = 1200
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.abspath(os.path.join(HERE, ".."))
CORPUS_FILES = ["README.md"] + [f"docs/{n:02d}-{s}.md" for n, s in [
    (1, "obzor"), (2, "arhitektura"), (3, "tehnologii"),
    (4, "struktura-proekta"), (5, "sborka-i-zapusk"), (6, "rag-pipeline"),
    (7, "api"), (8, "hranilishche-dannyh"), (9, "ui"),
    (10, "testy"), (11, "ogranicheniya"),
]]


# Whole-token normalisation: docs mix Latin and Cyrillic spellings
# of the same terms (RAG/РАГ, LLM/ЛЛМ) — fold them to one form for scoring.
NORM_TOKENS = {"rag": "раг", "llm": "ллм", "chunks": "чанк", "chunk": "чанк"}

# Crude Russian stemmer: strip one common ending (longest first).
# Not Snowball, but enough to match собрать/сборка-class pairs at the
# substring level together with the root check in retrieve().
RU_ENDINGS = (
    "ание", "ение", "ость", "ать", "ять", "еть", "ить", "оть", "уть",
    "ания", "ения", "ов", "ев", "ами", "ями", "ах", "ях",
    "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой", "ая", "яя",
    "его", "ого", "ому", "ему", "ом", "ем", "им", "ым", "ую", "юю",
    "а", "я", "о", "е", "и", "ы", "у", "ю", "ь", "й",
)


def stem(tok: str) -> str:
    if len(tok) < 6 or not re.search("[а-яё]", tok):
        return tok
    for suf in RU_ENDINGS:
        if tok.endswith(suf) and len(tok) - len(suf) >= 4:
            return tok[: -len(suf)]
    return tok


def norm_tok(tok: str) -> str:
    tok = tok.lower()
    tok = NORM_TOKENS.get(tok, tok)
    return stem(tok)


def tokenize(text: str) -> list[str]:
    return [norm_tok(t) for t in TOKEN_RE.findall(text)]


def roots_of(text: str) -> set[str]:
    """First-5-chars roots of long words — substring matching that survives
    Russian morphology (same trick as QueryRouter in the app itself)."""
    return {norm_tok(t)[:5] for t in TOKEN_RE.findall(text.lower()) if len(t) >= 4}


def chunk_markdown(path: str, rel: str, text: str) -> list[dict]:
    title = rel
    m = re.search(r"^#\s+(.+)", text, re.MULTILINE)
    if m:
        title = m.group(1).strip()
    chunks: list[dict] = []
    cur_section = "(начало)"
    cur_lines: list[str] = []

    def flush():
        body = "\n".join(cur_lines).strip()
        if len(body) < 40:
            return
        if len(body) <= CHUNK_FALLBACK_CHARS:
            chunks.append({"source": rel, "title": title, "section": cur_section, "text": body})
        else:  # long section — split by paragraphs
            for para in [p.strip() for p in body.split("\n\n") if p.strip()]:
                if len(para) <= CHUNK_FALLBACK_CHARS:
                    chunks.append({"source": rel, "title": title, "section": cur_section, "text": para})
                else:
                    for i in range(0, len(para), CHUNK_FALLBACK_CHARS):
                        part = para[i:i + CHUNK_FALLBACK_CHARS].strip()
                        if len(part) >= 40:
                            chunks.append({"source": rel, "title": title, "section": cur_section, "text": part})

    for line in text.splitlines():
        hm = HEADER_RE.match(line.strip())
        if hm and len("\n".join(cur_lines)) > 200:
            flush()
            cur_lines = [line]
            cur_section = hm.group(2).strip()
        else:
            cur_lines.append(line)
    flush()
    if not chunks and text.strip():
        chunks.append({"source": rel, "title": title, "section": cur_section,
                       "text": text.strip()[:CHUNK_FALLBACK_CHARS]})
    return chunks


def build_index(root: str) -> list[dict]:
    chunks: list[dict] = []
    for rel in CORPUS_FILES:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            chunks.extend(chunk_markdown(path, rel, f.read()))
    # IDF over chunk corpus
    df: dict[str, int] = {}
    for ch in chunks:
        for tok in set(tokenize(ch["title"] + " " + ch["section"] + " " + ch["text"])):
            df[tok] = df.get(tok, 0) + 1
    n = max(1, len(chunks))
    idf = {t: math.log(n / (1 + c)) + 1.0 for t, c in df.items()}
    for ch in chunks:
        tf: dict[str, int] = {}
        for tok in tokenize(ch["text"]):
            tf[tok] = tf.get(tok, 0) + 1
        ch["_tf"] = tf
        ch["_len"] = max(1, len(tokenize(ch["text"])))
        ch["_head_roots"] = roots_of(ch["title"] + " " + ch["section"])
        ch["_text_low"] = (ch["title"] + " " + ch["section"] + " " + ch["text"]).lower()
    return chunks, idf  # type: ignore[return-value]


def retrieve(chunks, idf, query: str, top_k: int) -> list[tuple[float, dict]]:
    qtokens = tokenize(query)
    if not qtokens:
        return []
    qroots = roots_of(query)
    scored = []
    for ch in chunks:
        score = 0.0
        tf = ch["_tf"]
        for tok in set(qtokens):
            c = tf.get(tok, 0)
            if c:
                score += idf.get(tok, 1.0) * (c / (c + 1.2))
        # root-substring gate: Russian morphology + doc-title relevance
        # (title hit > section hit > body hit)
        for r in qroots:
            if r in ch["_head_roots"] or r in ch["title"].lower() or r in ch["section"].lower():
                score += 2.0
            elif r in ch["_text_low"]:
                score += 0.5
        if score > 0:
            scored.append((score / math.sqrt(ch["_len"] / 100 + 1), ch))
    scored.sort(key=lambda p: -p[0])
    return scored[:top_k]


# --- MCP client (spawns mcp_server.py) ---

class McpClient:
    def __init__(self, server_path: str):
        self.proc = subprocess.Popen(
            [sys.executable, server_path],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, bufsize=1,
        )
        self._id = 0
        self._call("initialize", {"protocolVersion": "2024-11-05",
                                 "capabilities": {},
                                 "clientInfo": {"name": "callowfellow-assistant", "version": "0.1.0"}})

    def _call(self, method: str, params: dict):
        self._id += 1
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self._id,
                                          "method": method, "params": params},
                                         ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        for line in self.proc.stdout:
            if not line.strip():
                continue
            resp = json.loads(line)
            if resp.get("id") == self._id:
                if "error" in resp:
                    raise RuntimeError(resp["error"])
                return resp.get("result", {})
        raise RuntimeError("MCP server closed stdout")

    def tool(self, name: str, args: dict | None = None) -> dict:
        res = self._call("tools/call", {"name": name, "arguments": args or {}})
        content = res.get("content", [{}])[0].get("text", "{}")
        return json.loads(content)

    def close(self):
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
        finally:
            self.proc.terminate()


# --- LLM backend ---

def deepseek_complete(api_key: str, system: str, user: str, model: str = DEEPSEEK_MODEL) -> str:
    payload = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.3,
    }).encode()
    req = urllib.request.Request(
        DEEPSEEK_URL, data=payload,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode())
    return data["choices"][0]["message"]["content"].strip()


SYSTEM_PROMPT = (
    "Ты — ассистент разработчика проекта CallowFellow (офлайн LLM-чат + RAG, "
    "Kotlin Multiplatform + LiteRT-LM). Отвечай на вопросы о проекте ТОЛЬКО по "
    "приведённым фрагментам документации; после каждого факта ставь короткую "
    "ссылку на источник вида [docs/04-struktura-proekta.md]. Если в фрагментах "
    "ответа нет — честно скажи и подскажи, где искать. Отвечай кратко, по-русски."
)


def context_block(hits: list[tuple[float, dict]], per_hit: int = 900) -> str:
    parts = []
    for i, (_, ch) in enumerate(hits, 1):
        parts.append(f"[{i}] {ch['source']} / {ch['section']}:\n{ch['text'][:per_hit]}")
    return "\n\n".join(parts)


def answer(question: str, chunks, idf, mcp: McpClient, top_k: int,
           api_key: str | None, model: str) -> str:
    hits = retrieve(chunks, idf, question, top_k)
    branch = {}
    try:
        branch = mcp.tool("get_git_branch")
    except Exception as e:  # noqa: BLE001
        branch = {"branch": f"unknown ({e})"}
    proj_line = f"Текущая ветка: {branch.get('branch', '?')} (commit {branch.get('commit', '?')})."
    if not hits:
        return f"{proj_line}\nВ документации ничего не нашлось — переформулируй вопрос."
    if not api_key:
        lines = [proj_line, "", "Ключа DEEPSEEK_API_KEY нет — показываю релевантные фрагменты:", ""]
        for i, (s, ch) in enumerate(hits, 1):
            lines.append(f"[{i}] {ch['source']} / {ch['section']} (score {s:.2f}):")
            lines.append(ch["text"][:600])
            lines.append("")
        return "\n".join(lines).strip()
    user = (f"{proj_line}\n\nФрагменты документации:\n{context_block(hits)}\n\n"
            f"Вопрос: {question}")
    try:
        return f"{proj_line}\n\n{deepseek_complete(api_key, SYSTEM_PROMPT, user, model)}"
    except Exception as e:  # noqa: BLE001
        return f"{proj_line}\nОшибка LLM ({e}). Фрагменты:\n\n{context_block(hits, 600)}"


HELP_TEXT = (
    "Команды:\n"
    "  /help <вопрос>  — вопрос о проекте (RAG по README+docs + ветка через MCP)\n"
    "  /branch         — текущая git-ветка (MCP get_git_branch)\n"
    "  /files [prefix] — список файлов (MCP list_files)\n"
    "  /diff           — git diff HEAD --stat (MCP get_diff)\n"
    "  /quit           — выход\n"
    "Примеры: /help Где хранятся чанки RAG? | /help Как собрать десктоп-превью?"
)


def repl(chunks, idf, mcp: McpClient, api_key: str | None, model: str, top_k: int) -> int:
    print(f"RAG: {len(chunks)} чанков из README+docs. MCP: {HERE}/mcp_server.py. "
          f"LLM: {'DeepSeek ' + model if api_key else 'fallback (без ключа)'}")
    print("Введи /help <вопрос>. Пустая строка или /quit — выход. /help без аргументов — справка.")
    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not line or line in ("/quit", "/exit"):
            return 0
        if line == "/help":
            print(HELP_TEXT)
        elif line.startswith("/help "):
            print(answer(line[6:].strip(), chunks, idf, mcp, top_k, api_key, model))
        elif line == "/branch":
            print(json.dumps(mcp.tool("get_git_branch"), ensure_ascii=False, indent=2))
        elif line.startswith("/files"):
            parts = line.split(None, 1)
            prefix = parts[1] if len(parts) > 1 else ""
            print(json.dumps(mcp.tool("list_files", {"prefix": prefix}),
                             ensure_ascii=False, indent=2))
        elif line == "/diff":
            print(json.dumps(mcp.tool("get_diff", {"stat_only": "true"}), ensure_ascii=False, indent=2))
        else:  # plain question == /help
            print(answer(line, chunks, idf, mcp, top_k, api_key, model))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="CallowFellow /help dev-assistant (Week 6 Task 1)")
    ap.add_argument("--root", default=DEFAULT_ROOT)
    ap.add_argument("--top-k", type=int, default=4)
    ap.add_argument("--no-llm", action="store_true", help="extractive fallback even if key exists")
    ap.add_argument("--api-key", default=None)
    ap.add_argument("--model", default=DEEPSEEK_MODEL)
    ap.add_argument("--help-question", default=None, help="one-shot /help and exit")
    args = ap.parse_args(argv)
    chunks, idf = build_index(args.root)
    if not chunks:
        print(f"RAG corpus empty under {args.root}", file=sys.stderr)
        return 1
    api_key = None if args.no_llm else (args.api_key or os.environ.get("DEEPSEEK_API_KEY"))
    mcp = McpClient(os.path.join(HERE, "mcp_server.py"))
    try:
        if args.help_question:
            print(answer(args.help_question, chunks, idf, mcp, args.top_k, api_key, args.model))
            return 0
        return repl(chunks, idf, mcp, api_key, args.model, args.top_k)
    finally:
        mcp.close()


if __name__ == "__main__":
    raise SystemExit(main())

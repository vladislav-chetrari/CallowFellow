# Dev-ассистент `/help` — Week 6, Task 1 (День 31)

Ассистент разработчика, понимающий проект CallowFellow.

| Требование задачи | Где |
|---|---|
| RAG: `README` + `docs/` + схемы/API | `assistant.py`: индекс по `README.md` + `docs/01–11` (схемы — `07-api.md`, `08-hranilishche-dannyh.md`), чанкинг по заголовкам, TF-IDF-ретривер, `top_k=4` |
| MCP минимум `git branch` | `mcp_server.py` → `get_git_branch` (ветка + commit + clean) |
| MCP по желанию `list files / diff` | `list_files` (без `.git/build/models/*.litertlm`), `get_diff` (`diff HEAD --stat`) |
| Команда `/help` | REPL + one-shot `--help-question`; ответ использует доки + ветку |

Зависимостей нет, кроме stdlib. `requests` в `requirements.txt` — исторический
хвост (LLM вызывается через `urllib`), ставить ничего не нужно.

## Запуск

```sh
cd /path/to/CallowFellow

# интерактивно (без ключа — экстрактивный fallback, для демо достаточно):
python3 assistant/assistant.py --no-llm

# с генерацией через DeepSeek:
DEEPSEEK_API_KEY=sk-... python3 assistant/assistant.py

# один вопрос без REPL (удобно для видео/проверки):
python3 assistant/assistant.py --no-llm --help-question "Где хранятся чанки RAG?"
```

## Команды REPL

```
/help <вопрос>  — вопрос о проекте (RAG + ветка через MCP)
/branch         — MCP get_git_branch
/files [prefix] — MCP list_files, напр. /files docs или /files shared/src/task3
/diff           — MCP get_diff (stat)
/quit           — выход
```

Строка без команды трактуется как `/help`.

## Примеры вопросов для видео

```
/help Как устроен RAG-пайплайн?
/help Где хранятся чанки и векторы?
/help Как собрать десктоп-превью?
/help Где лежит каталог моделей и откуда они качаются?
/help Что за task2.* / task3.* в shared/?
```

## Как это работает

1. **Индекс** строится при старте: `README.md` + `docs/*.md` режутся по
   markdown-заголовкам (длинные секции — по абзацам до 1200 символов),
   к каждому чанку — метаданные `source/title/section`.
2. **Поиск** — TF-IDF по корпусу чанков + буст ×1.3 за совпадение в
   заголовке/секции. Без внешних моделей и сети — воспроизводимо.
3. **MCP** — `assistant.py` порождает `mcp_server.py` (stdio JSON-RPC:
   `initialize` → `tools/call`) и перед каждым `/help` забирает ветку.
   Сервер также понимает `tools/list`, `ping`.
4. **Ответ** — с ключом: системный промпт + `Текущая ветка: …` + топ-4
   фрагмента → `deepseek-chat` (`temperature 0.3`); без ключа: те же
   фрагменты печатаются как есть.

## Проверка минимального результата

```sh
python3 assistant/mcp_server.py <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_git_branch","arguments":{}}}
EOF
echo "/help Где хранится история чата RAG?" | python3 assistant/assistant.py --no-llm
```

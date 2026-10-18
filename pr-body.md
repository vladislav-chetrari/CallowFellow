Автоправка документации по результатам изменения кода.

Изменённые файлы кода (1):
- `shared/src/task2/data/ModelCatalog.kt`

Что сделал агент:
Остановлен: лимит шагов (16)

Обновлённые документы:
- `docs/03-tehnologii.md`
- `docs/07-api.md`
- `docs/10-testy.md`
- `docs/01-obzor.md`

<details><summary>diff</summary>

```diff
--- a/docs/03-tehnologii.md
+++ b/docs/03-tehnologii.md
@@ -107,0 +107,3 @@
 |---|---|---|---|
 | `qwen25-1.5b-q8` (по умолчанию) | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1524 МБ | 4096 |
 | `qwen3-0.6b-int4` | `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 331 МБ | 1280 |
+| `lfm25-1.2b-int4` | `LFM2.5-1.2B-Instruct_int4.litertlm` | 736 МБ | 4096 |
+| `gemma4-e2b` | `gemma-4-E2B-it.litertlm` | 2588 МБ | 4096 |
+| `qwen3-4b-int4` | `qwen3_4b_mixed_int4.litertlm` | 2659 МБ | 2048 |
 | `embedgemma-270m` | `embeddinggemma-2-text-270m.litertlm` | 157 МБ | 512 токенов, dim 768 |
 
 Источник — HuggingFace, организация `litert-community`, Apache-2.0, без логина.
--- a/docs/03-tehnologii.md
+++ b/docs/03-tehnologii.md
@@ -14,1 +14,1 @@
 | Файлы/I/O | Okio | 3.18.2 |
 | Облачный LLM | Koog (`prompt-executor-deepseek-client`, `http-client-ktor`) | 1.3.0-beta / 1.3.0 |
 | Локальный LLM | LiteRT-LM (`litertlm-android`, `litertlm-jvm`) | 0.18.0 |
-| Генерация | Qwen2.5-1.5B-Instruct Q8 · Qwen3-0.6B int4 | модели, ~1.5 ГБ / ~331 МБ |
+| Генерация | Qwen2.5-1.5B-Instruct Q8 · Qwen3-0.6B int4 · LFM2.5 1.2B int4 · Gemma 4 E2B · Qwen3 4B int4 | модели, ~331 МБ … ~2.6 ГБ |
 | Эмбеддинги | EmbeddingGemma 270M (dim 768) | ~157 МБ |
 | Тесты | kotlin.test + JUnit 5 | 6.1.3 |
 | JDK | 25 (release 25) | — |
--- a/docs/07-api.md
+++ b/docs/07-api.md
@@ -15,0 +15,3 @@
 |---|---|
 | Qwen2.5-1.5B-Instruct Q8 | `https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` |
 | Qwen3-0.6B int4 | `https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm` |
+| LFM2.5 1.2B int4 | `https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int4.litertlm` |
+| Gemma 4 E2B | `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm` |
+| Qwen3 4B int4 | `https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm` |
 | EmbeddingGemma 270M | `https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm` |
 
 URL **захардкожены** в `ModelCatalog` / `EmbedCatalog` — произвольные ссылки
--- a/docs/07-api.md
+++ b/docs/07-api.md
@@ -306,0 +306,5 @@
 |---|---|---|---|---|
 | `qwen25-1.5b-q8` | Qwen2.5 1.5B Q8 | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1524 | 4096 |
 | `qwen3-0.6b-int4` | Qwen3 0.6B | `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 331 | 1280 |
+| `lfm25-1.2b-int4` | LFM2.5 1.2B | `LFM2.5-1.2B-Instruct_int4.litertlm` | 736 | 4096 |
+| `gemma4-e2b` | Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | 2588 | 4096 |
+| `qwen3-4b-int4` | Qwen3 4B | `qwen3_4b_mixed_int4.litertlm` | 2659 | 2048 |
 
+`ModelCatalog.ALL` перечисляет модели в порядке `qwen3-0.6b-int4`, `lfm25-1.2b-int4`,
+`qwen25-1.5b-q8`, `gemma4-e2b`, `qwen3-4b-int4`; `DEFAULT` — `qwen25-1.5b-q8`.
 `ModelCatalog.resolve(id)` возвращает `DEFAULT` для неизвестного/пустого id.
 
 ### Каталог эмбеддингов (`task3.data.EmbedCatalog`)
--- a/docs/10-testy.md
+++ b/docs/10-testy.md
@@ -58,1 +58,1 @@
 ### `LogicTest` (12) — чистые функции
 
 **Каталог моделей**
-* `defaultModelIsQwen2515b` — по умолчанию `qwen25-1.5b-q8`, в каталоге 2 модели.
+* `defaultModelIsQwen2515b` — по умолчанию `qwen25-1.5b-q8`, в каталоге 5 моделей.
 
 **Промпты**
 * `chatPromptHasNoChatMl` — ни system, ни user не содержат `<|im_start|>`: шаблон
--- a/docs/01-obzor.md
+++ b/docs/01-obzor.md
@@ -18,1 +18,1 @@
 
 | Режим | Что делает | Интернет |
 |---|---|---|
-| **Локальный LLM** | Qwen2.5-1.5B-Instruct (Q8) или Qwen3-0.6B (int4) генерируют ответ прямо на устройстве через LiteRT-LM | Не нужен (после скачивания модели) |
+| **Локальный LLM** | Одна из моделей каталога (Qwen2.5-1.5B Q8 по умолчанию, Qwen3-0.6B, LFM2.5 1.2B, Gemma 4 E2B, Qwen3 4B) генерирует ответ прямо на устройстве через LiteRT-LM | Не нужен (после скачивания модели) |
 | **Облачный LLM** | DeepSeek Chat через Koog — для сравнения качества RAG | Нужен + API-ключ |
 
 База знаний наполняется статьями Википедии: приложение скачивает текст, режет на
```
</details>

---
Сгенерировано docs-sync агентом (Week 6, Task 5). Изменены только документы.

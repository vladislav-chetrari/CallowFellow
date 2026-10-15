# 07. API

Три группы контрактов: **внешние HTTP-сервисы**, **нативный runtime LiteRT-LM**
и **внутренние порты** (интерфейсы, которые реализуются в каждой платформе).

---

## 1. Внешние HTTP API

### 1.1 Скачивание моделей — HuggingFace

Модели тянутся с HuggingFace, организации `litert-community`, без логина и API-ключа.

| Модель | URL |
|---|---|
| Qwen2.5-1.5B-Instruct Q8 | `https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` |
| Qwen3-0.6B int4 | `https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm` |
| EmbeddingGemma 270M | `https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm` |

URL **захардкожены** в `ModelCatalog` / `EmbedCatalog` — произвольные ссылки
пользователя не принимаются (KISS, комментарий в `ModelCatalog`).

Функция скачивания — `downloadFile` из
[`shared/src/task2/data/Network.kt`](../shared/src/task2/data/Network.kt):

```kotlin
suspend fun downloadFile(
    url: String,
    destPath: String,
    onProgress: (doneBytes: Long, totalBytes: Long?) -> Unit,
)
```

Гарантии (сформулированы в KDoc и соблюдаются в коде):

| Поведение | Реализация |
|---|---|
| До 3 попыток | `repeat(3)`; при обрыве — пауза 2 с |
| HTTP-ошибки **не** повторяются | `IllegalStateException` → сразу наружу |
| Чистый старт, без `resume` | перед каждой попыткой `.part` удаляется |
| Обрыв хвоста = повторяемая ошибка | `done < total` → обычный `Exception`, а не HTTP-ошибка |
| Атомарное появление файла | запись в `.part`, затем `atomicMove` (с откатом на `copy` + `delete`) |
| Прогресс | по изменению процента, если длина известна; по мегабайту, если нет; всегда финальный вызов |
| Честность прогресса | `totalBytes = null`, если сервер не прислал `Content-Length` (чанковая передача, прокси) |
| Таймауты для больших файлов | `requestTimeoutMillis = 7 суток`, `socketTimeoutMillis = 300 с` (общий таймаут фактически выключен) |

Заголовок `User-Agent: ai-advent` добавляется ко всем запросам.

**Проверка целостности после скачивания** — `validateModelFile(path, expectedSizeMb)`
(в `task3.data.Files`): сравнивается размер, файл короче 80 % от ожидаемого
считается оборванным, такой файл удаляется и показывается ошибка.
Формат `.litertlm` непрозрачный, поэтому глубокая проверка не делается — об этом
явно сказано в KDoc.

### 1.2 Wikipedia API — источник документов

```
GET https://{lang}.wikipedia.org/w/api.php
    ?action=query
    &prop=extracts
    &explaintext=1
    &exsectionformat=wiki
    &redirects=1
    &format=json
    &titles={urlEncode(title)}
```

| Параметр | Зачем |
|---|---|
| `action=query` | стандартный read-эндпоинт MediaWiki |
| `prop=extracts` | текст статьи |
| `explaintext=1` | чистый текст вместо вики-разметки (HTML не нужен) |
| `exsectionformat=wiki` | заголовки секций как `=== Раздел ===` — вход для `StructureChunker` |
| `redirects=1` | следование редиректам |
| `format=json` | разбор через `kotlinx.serialization.json` |

Обёртка: `WikiLoader.fetch(input, defaultLang = "ru"): RawDoc`
(вход принимает и URL, и голое название статьи).
Ошибки сети/формата превращаются в `IllegalStateException` с русским сообщением,
которое показывается в карточке документа (`DocumentEntity.status = "error"`).

### 1.3 DeepSeek Chat API — облачный режим

Прямых вызовов нет: запрос уходит через библиотеку Koog
(`ai.koog:prompt-executor-deepseek-client`, `ai.koog:http-client-ktor`).

```kotlin
object DeepSeekClient {
    const val MODEL = "deepseek-chat"

    suspend fun complete(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        apiKey: String?,
    ): DeepSeekResult
}

sealed interface DeepSeekResult {
    data class Ok(val text: String)
    data class HttpError(val code: Int, val detail: String)
    data class NetworkError(val detail: String)
}
```

Особенности:

* **Модель описана вручную.** `deepseek-chat` отсутствует в пресете `DeepSeekModels`
  беты (там только флагманы V4), поэтому создаётся собственный `LLModel` с
  `LLMProvider.DeepSeek` и набором возможностей
  (`Completion`, `Temperature`, `Tools`, `ToolChoice`, `Schema.JSON.Basic`,
  `Schema.JSON.Standard`, `MultipleChoices`, `Thinking`) — без них executor
  не проходит валидацию.
* **Промпт через DSL Koog:** `prompt("rag-answer", DeepSeekParams(temperature = 0.3)) { … }`.
  Температура едет внутри `Prompt`, а не отдельным аргументом.
* **Явная фабрика HTTP-клиента:**
  ```kotlin
  DeepSeekLLMClient(
      apiKey = apiKey,
      httpClientFactory = KtorKoogHttpClient.Factory(
          baseClient = HttpClient(CIO),
          withSse = false,          // стриминг не используется
      ),
  )
  ```
  Автообнаружение через `ServiceLoader` в APK не работает — `META-INF/services`
  вырезается при упаковке, и Koog падал с `No KoogHttpClient.Factory provider found`.
  Именно поэтому в зависимостях есть `$libs.koog.http.ktor`.
* **Кэширование клиента:** `LLMClient` создаётся один на ключ и переиспользуется
  (`@Volatile cached` + `cachedKey`), иначе на каждый запрос создавались бы
  потоки и сокеты.
* **Ошибки не бросаются** — они становятся текстом ответа, чтобы облачный сбой
  не ломал чат.
* API-ключ хранится в таблице `settings` под ключом `deepseek_key`,
  вводится в маскированном поле, кэшируется в памяти на время работы.

---

## 2. Нативный runtime — LiteRT-LM

Не HTTP и не наш код: Maven-зависимости `litertlm-android` (AAR) и
`litertlm-jvm` (jar с нативными библиотеками). Ничего собирать вручную не нужно.

| Операция | Android | Desktop |
|---|---|---|
| Генеративная модель | `LiteRtLlamaBridge` | `LiteRtLlamaBridge` |
| Эмбеддинги | `LiteRtEmbedBridge` (`EmbeddingEngine`) | `FakeEmbedBridge` (хеш-векторы) |

### Параметры генерации

```kotlin
data class GenParams(
    val temperature: Float = 0.5f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
) { companion object { val DEFAULT = GenParams() } }
```

Передаются в `SamplerConfig(topK, topP, temperature)`; `maxOutputToken = maxTokens`.

### Алгоритм загрузки движка

```
load(modelPath, nCtx, nThreads):
    close()                             # закрыть предыдущий движок
    if tryOpen(modelPath, Backend.GPU(), probe = true) → готово
    else tryOpen(modelPath, Backend.CPU(threadCount = nThreads), probe = false)
```

`probe = true` означает пробную генерацию (`Message.user("hi")`, `maxOutputToken = 16`).
Это принципиально: GPU-движок может успешно инициализироваться и упасть только
на первой генерации с `Can not find OpenCL library`. Откат выполняется **до** того,
как пользователь увидит ошибку в чате, а не посреди диалога.
Исключение пробрасывается наружу только если не удался и CPU-вариант.

### Чат-шаблон

`ConversationConfig(chatTemplate = CHATML_TEMPLATE)` — минимальный Jinja-шаблон,
см. [03. Технологии](03-tehnologii.md#переопределение-чат-шаблона).

### Эмбеддинги

```
EmbeddingEngineConfig(modelPath, backend = Backend.CPU(), maxInputLength = 512)
computeEmbeddingBatch(listOf(listOf(InputData.Text(text)), …), EmbeddingOptions(normalize = true))
```

* `maxInputLength = 512` токенов — отсюда ограничение чанка в 1000 символов.
* `normalize = true` — векторы L2-нормализованы, поэтому косинус считается
  скалярным произведением.
* Размерность определяется пробой при загрузке, а не доверяется каталогу.

---

## 3. Внутренние порты (Kotlin API)

### `LlamaBridge` — порт локального инференса

```kotlin
interface LlamaBridge {
    val isReady: Boolean
    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4)
    fun generateChat(system: String, user: String, params: GenParams = GenParams.DEFAULT): Flow<String>
    fun cancel()
    fun close()
}

expect fun createLlamaBridge(): LlamaBridge
```

Контракт:

* `generateChat` возвращает **поток дельт**; реализация сама переключается на
  `Dispatchers.IO` и проверяет `ensureActive()` на каждом элементе.
* Каждый вызов создаёт **свежую** `Conversation`: история не накапливается в рантайме,
  она передаётся текстом внутри `user` (см. `PromptBuilder.chatUser`).
* `cancel()` закрывает активную `Conversation` — это единственный способ
  прервать нативный декод.
* `close()` идемпотентен: и `cancel()`, и `close()` обёрнуты в `runCatching`.

### `EmbedBridge` — порт эмбеддингов

```kotlin
interface EmbedBridge {
    val isReady: Boolean
    val dim: Int                                   // фактическая размерность
    suspend fun load(modelPath: String, nThreads: Int = 4)
    suspend fun embed(texts: List<String>): List<FloatArray>
    fun close()
}

expect fun createEmbedBridge(): EmbedBridge
```

Контракт: тексты приходят уже с префиксами (если модель их требует); векторы
возвращаются нормализованными; `dim` — истина, а не значение из каталога.

### `ChatRepository` — порт чата (task2)

```kotlin
interface ChatRepository {
    val messages: StateFlow<List<ChatMessage>>
    val status: StateFlow<EngineStatus>

    suspend fun ensureModel(model: AiModel, modelsDir: String)
    suspend fun send(model: AiModel, modelsDir: String, prompt: String)
    fun cancel()
    suspend fun clear()
}
```

### `MemoryStore` — порт долговременной памяти

```kotlin
interface MemoryStore {
    val facts: StateFlow<List<ConversationFact>>
    suspend fun load()
    suspend fun addFacts(texts: List<String>)
    suspend fun clear()
}
```

### Платформенные `expect`-функции

```kotlin
// task2.data
expect fun platformFileExists(path: String): Boolean
expect fun platformCpuThreads(): Int
expect fun nowMillis(): Long

// task3.data
expect fun fileSizeBytes(path: String): Long
expect fun deleteFile(path: String)
```

## 4. Чистые утилиты общего кода

Функции без побочных эффектов — основная точка переиспользования и юнит-тестов.

| Функция | Файл | Назначение |
|---|---|---|
| `l2normalize(v: FloatArray)` | `task3/data/EmbedMath.kt` | L2-нормализация |
| `hashingEmbed(text, dim)` | там же | детерминированный фолбэк: хеш символьных триграмм |
| `cosineTopK(query, candidates, topK)` | там же | точный top-K по косинусу (dot) |
| `FloatArray.toBlob()` / `ByteArray.toFloatArray()` | там же | little-endian float32 ↔ BLOB |
| `urlEncode(s)` / `urlDecode(s)` | `task2/data/Network.kt`, `task3/data/WikiLoader.kt` | percent-кодирование/декодирование без `java.net` |
| `StructureChunker.chunk(doc)` | `task3/data/Chunkers.kt` | структурный чанкинг |
| `PromptBuilder.cleanReply(text)` | `task2/data/PromptBuilder.kt` | вырезает `<think>` и служебные маркеры |
| `PromptBuilder.chatSystem/chatUser/ragSystem` | там же | формирование промптов |
| `FactExtractor.extractFrom(text, maxCount = 3, maxLen = 120)` | `task2/data/FactExtractor.kt` | извлечение фактов |
| `RoomMemoryStore.sameFact(a, b)` | `task2/data/RoomMemoryStore.kt` | дедупликация фактов |
| `QueryRouter.isSmallTalk(query)` | `task3/data/QueryRouter.kt` | маршрутизация small-talk |
| `validateModelFile(path, expectedSizeMb)` | `task3/data/Files.kt` | проверка размера скачанного файла |
| `LlmChoice.fromStored(...)` / `toStored()` | `task3/domain/Models.kt` | сериализация выбора модели в settings |
| `GenPreset.fromId(id)` / `toGenParams()` | там же | пресет ↔ параметры сэмплера |

## 5. Конфигурационные данные приложения

### Каталог LLM (`task2.data.ModelCatalog`)

| id | label | fileName | sizeMb | defaultCtx |
|---|---|---|---|---|
| `qwen25-1.5b-q8` | Qwen2.5 1.5B Q8 | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1524 | 4096 |
| `qwen3-0.6b-int4` | Qwen3 0.6B | `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 331 | 1280 |

`ModelCatalog.resolve(id)` возвращает `DEFAULT` для неизвестного/пустого id.

### Каталог эмбеддингов (`task3.data.EmbedCatalog`)

| id | label | fileName | sizeMb | dim |
|---|---|---|---|---|
| `embedgemma-270m` | EmbeddingGemma 270M (~165 МБ) | `embeddinggemma-2-text-270m.litertlm` | 157 | 768 |

### Ключи настроек (таблица `settings` в `rag.db`)

| Константа | Ключ | Значение |
|---|---|---|
| `K_LLM` | `llm` | id модели или `deepseek`; пусто — не выбрана |
| `K_EMBED` | `embed` | id embedding-модели; пусто — не выбрана |
| `K_DEEPSEEK` | `deepseek_key` | API-ключ |
| `K_PRESET` | `gen_preset` | `precise` \| `balanced` \| `creative` |

### Разрешения Android

`AndroidManifest.xml` объявляет **только** `android.permission.INTERNET` —
он нужен исключительно для скачивания моделей и статей Википедии.
Никаких `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` и сетевых разрешений
на файлы нет: всё хранится в приватном `filesDir`.

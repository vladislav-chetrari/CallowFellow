# 03. Технологии

## Сводка стека

| Слой | Технология | Версия |
|---|---|---|
| Язык | Kotlin | 2.4.10 |
| Сборка | Kotlin Toolchain (Amper) | 0.12.0 |
| UI | Compose Multiplatform + Material 3 | 1.11.1 |
| Асинхронность | kotlinx.coroutines | 1.10.2 |
| Сериализация | kotlinx.serialization (json) | 1.11.0 |
| БД | Room KMP + Bundled SQLite | 2.8.5 / 2.7.1 |
| HTTP-клиент | Ktor client (CIO) | 3.1.3 |
| Файлы/I/O | Okio | 3.18.2 |
| Облачный LLM | Koog (`prompt-executor-deepseek-client`, `http-client-ktor`) | 1.3.0-beta / 1.3.0 |
| Локальный LLM | LiteRT-LM (`litertlm-android`, `litertlm-jvm`) | 0.18.0 |
| Генерация | Qwen2.5-1.5B-Instruct Q8 · Qwen3-0.6B int4 | модели, ~1.5 ГБ / ~331 МБ |
| Эмбеддинги | EmbeddingGemma 270M (dim 768) | ~157 МБ |
| Тесты | kotlin.test + JUnit 5 | 6.1.3 |
| JDK | 25 (release 25) | — |
| Android | minSdk 24, compile/targetSdk 37 | — |

Версии библиотек — в version catalog [`gradle/libs.versions.toml`](../gradle/libs.versions.toml),
подключаются в `module.yaml` как `$libs.*`. Версии Kotlin и Compose приходят из
Kotlin Toolchain (дефолты `$kotlin.version`, `$compose.version`) и в catalog
намеренно не дублируются.

## Kotlin Toolchain (Amper) вместо Gradle

Проект **не использует Gradle**. Файл `gradle/libs.versions.toml` — это
*version catalog*, который Amper читает из корня проекта; ни `settings.gradle`, ни
`build.gradle` в репозитории нет.

* Конфигурация: [`project.yaml`](../project.yaml) (список модулей) и `module.yaml` в каждом модуле.
* Wrapper: [`kotlin`](../kotlin) / [`kotlin.bat`](../kotlin.bat) — POSIX-скрипт на `sh`,
  который скачивает дистрибутив Kotlin CLI, **проверяет SHA-256** и передаёт аргументы дальше.
* Версия CLI зашита в wrapper: `kotlin_cli_version=0.12.0`,
  `kotlin_cli_sha256=626b2d6e…` — цепочка доверия до загружаемого тулчейна.

### Что даёт Amper

* Конфигурация на YAML без Groovy/Kotlin-DSL и без версионных конфликтов плагинов.
* Автоматическая установка Android SDK, build-tools, платформ и образов эмулятора
  (видно в графе задач: `installPlatformAndroid`, `installBuildToolsAndroid`,
  `installSystemImageAndroid`).
* «Из коробки» KMP-модули, Compose, KSP, Room, публикация.

### Что стоит учитывать

* IntelliJ IDEA поддерживает Amper-модули начиная с 2025.2 — у пользователей
  может быть старая версия.
* Каталоги `build/`, `generated/` и `.idea/` в `.gitignore` не попадают в репозиторий.
* Нет CI-конфигурации — сборка запускается локально через wrapper.

## Kotlin Multiplatform

`shared` объявлен как `kmp/lib` с платформами `jvm` и `android`, что даёт
три source set:

| Source set | Что лежит | Виден |
|---|---|---|
| `shared/src` | common-код, весь бизнес-лог и UI | обе платформы |
| `shared/src@android` | actual для LiteRT-LM, `java.io` | Android |
| `shared/src@jvm` | actual для LiteRT-LM, `FakeEmbedBridge`, `java.io` | Desktop |

Реально платформенных файлов всего **10** — по пять в `src@android` и `src@jvm`
при 38 общих файлах, то есть ~79 % кода общее (а с учётом точек входа и тестов
— 57 `.kt` в проекте, из них платформенных 10). Это результат выноса сети в Ktor
и файлов в okio (см. комментарий в `Network.kt`: раньше `HttpURLConnection`
дублировался в двух копиях).

## Compose Multiplatform

* Один UI-код для Android и Desktop: `MaterialTheme { … }`, `Scaffold`, `TopAppBar`,
  `AlertDialog`, `NavigationBar`.
* `collectAsState()` на всей `StateFlow`-модели; рекомпозиция только при изменении состояния.
* Edge-to-edge на Android (`enableEdgeToEdge()` + `WindowInsets.safeDrawing`).
* Иконки — `material-icons-core`, чтобы не тянуть весь `material-icons-extended`.
* Desktop-специфика — обработка `Esc` в `Window.onKeyEvent` как аналог системной кнопки «назад».
* В toolchain доступен `compose-hot-reload-mcp-server` (MCP-сервер горячей перезагрузки).

Ограничение: Material 3 в проекте **без темы** — используется дефолтная тёмная/светлая
палитра Compose, собственных цветов и типографики нет.

## LiteRT-LM (Google AI Edge) — локальный инференс

Нативные библиотеки приезжают Maven-зависимостями (`litertlm-android` — AAR,
`litertlm-jvm` — jar с нативными библиотеками). Собирать нативный код вручную не нужно.

Что используется из API:

| Класс | Назначение |
|---|---|
| `Engine(EngineConfig(modelPath, backend))` | загрузка генеративной модели |
| `Backend.GPU()` / `Backend.CPU(threadCount)` | выбор бэкенда |
| `ConversationConfig(systemInstruction, samplerConfig, maxOutputToken, chatTemplate)` | параметры запроса |
| `SamplerConfig(topK, topP, temperature)` | сэмплирование |
| `Message.user(...)` / `Contents.of(...)` | входные данные |
| `sendMessageAsync(...)` → `Flow` | потоковый вывод |
| `EmbeddingEngine(EmbeddingEngineConfig(modelPath, backend, maxInputLength))` | эмбеддинги |
| `computeEmbedding` / `computeEmbeddingBatch` | одиночный и батчевый расчёт |
| `EmbeddingOptions(normalize = true)` | L2-нормализация (косинус = dot) |

### Модели

| id | Файл | Размер | ctx |
|---|---|---|---|
| `qwen25-1.5b-q8` (по умолчанию) | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | 1524 МБ | 4096 |
| `qwen3-0.6b-int4` | `qwen3_0.6b_q4_block32_ekv1280.litertlm` | 331 МБ | 1280 |
| `embedgemma-270m` | `embeddinggemma-2-text-270m.litertlm` | 157 МБ | 512 токенов, dim 768 |

Источник — HuggingFace, организация `litert-community`, Apache-2.0, без логина.
Суффиксы файлов несут параметры: `multi-prefill-seq` — батчинг префилла,
`ekv4096`/`ekv1280` — размер KV-кэша, `q8`/`q4_block32` — квантизация.
Размер ctx из имени совпадает с `defaultCtx` в `ModelCatalog` и задаёт размер
контекстного окна — маленькое значение и есть причина скользящего окна в 10 сообщений.

### Переопределение чат-шаблона

В `LlamaBridge.kt` объявлен `CHATML_TEMPLATE` — минимальный Jinja-шаблон, который
передаётся в `ConversationConfig.chatTemplate`. Причина: штатный шаблон Qwen
падает на системном сообщении — он делает `'<|im_start|>system\n' + messages[0].content`,
а LiteRT передаёт `content` списком блоков (а не строкой), и рендер падает с
`tried to use + operator on unsupported types string and sequence`.

Ограничения парсера шаблона (проверено пробой на реальном `.litertlm`):

* не поддерживаются dashes-whitespace-control: `{%-`, `-%}`, `{{-`, `-}}`;
* внутри строковых литералов допустим только escape `\n`, реальных переводов строк — нет.

## Room KMP

* Базы: `chat.db` (`ChatDatabase`, v2) и `rag.db` (`RagDatabase`, v2).
* Драйвер — `androidx.sqlite.driver.bundled.BundledSQLiteDriver`: нативная SQLite,
  без `SupportSQLite` на Android.
* Схемы генерируются **KSP** (`androidx.room:room-compiler`, KSP 2.3.11).
* Сборщик БД создаётся **платформенной точкой входа** (она владеет `Context`/путём),
  в контейнер приходит уже готовый `RoomDatabase.Builder` — общий код не знает про Android.
* Миграции объявлены в коде (`MIGRATION_1_2`), а не скриптами Flyway: у Flyway нет
  JDBC-драйвера для Android. Деструктивных миграций нет.
* KMP-специфика Room требует `exported`-видимости `room-runtime` и `sqlite-bundled`
  для потребителей — так и сделано в `shared/module.yaml`.

Подробнее — [08. Хранение данных](08-hranilishche-dannyh.md).

## Ktor + okio — сетевой слой

* `HttpClient(CIO)` — **один на всё время жизни приложения** (переиспользование соединений).
* Таймауты по умолчанию: connect 30 с, socket 60 с, request 120 с.
* Для скачивания моделей таймауты переопределяются на конкретный запрос: request 7 суток
  (в этой версии Ktor нет `INFINITE_TIMEOUT_MS`), socket 300 с — чтобы гигабайтная модель
  не убивалась на медленной сети.
* okio `FileSystem.SYSTEM` — запись, атомарный `move`, удаление. `java.io` в common недоступен,
  но okio — кроссплатформенная абстракция, поэтому `expect`/`actual` для файлов не нужен
  (кроме двух мелких функций: `fileSizeBytes`, `deleteFile`).
* Один `HttpClient` на оба назначения: скачивание моделей и запросы к Википедии.
  Koog использует собственный клиент поверх переданного `HttpClient(CIO)`.

## Koog — облачный путь (DeepSeek)

* Библиотека берёт на себя prompt-DSL, сериализацию, транспорт и таймауты.
  Раньше это был ручной `HttpURLConnection` + ручной JSON.
* Модель `deepseek-chat` отсутствует в пресете `DeepSeekModels` беты
  (там только флагманы V4), поэтому `LLModel` описывается вручную
  с набором `LLMCapability` (`Completion`, `Temperature`, `Tools`, `Schema.JSON.*`,
  `MultipleChoices`, `Thinking`).
* Фабрика HTTP-клиента передаётся **явно**: `KtorKoogHttpClient.Factory(HttpClient(CIO), withSse = false)`.
  Автообнаружение через `ServiceLoader` не работает в APK — при упаковке вырезается
  `META-INF/services`, и DeepSeek падал с `No KoogHttpClient.Factory provider found`
  (проверено на устройстве). Модуль `$libs.koog.http.ktor` подключён именно поэтому.

Подробнее — [07. API](07-api.md).

## kotlinx.coroutines и kotlinx.serialization

* `StateFlow` — основа SSOT; `combine` — слияние 13 потоков в один `Task3UiState`.
* `CancellationException` перебрасывается до `Exception` — иначе отмена стала бы ошибкой.
* `Flow<String>` для потоковой генерации, `flowOn(Dispatchers.IO)` для нативного блокинга.
* Serialization используется точечно: `@Serializable ChatMessage`, `ConversationFact`,
  а также `kotlinx.serialization.json` для парсинга ответа Википедии.
  Зависимость включена через `settings.kotlin.serialization: json` во всех модулях.

## Ручной DI

Ни Hilt, ни Koin: `Task3Container` (и неиспользуемый `AppContainer`) — обычные классы,
которые сами создают зависимости и передают `CoroutineScope` во ViewModel.
Причина — KISS для приложения с одним экраном; см. комментарий
`Manual DI container (no Hilt — KISS for a single screen)`.

## Тесты

`kotlin.test` + JUnit 5 (платформа 6.1.3). Никаких мок-фреймворков: вместо них
написанные вручную `Fake`/`Stale`-реализации портов. Покрытие — 23 теста на 6 классов,
см. [10. Тесты](10-testy.md).

## Сторонние модели — лицензии

* Qwen2.5-1.5B-Instruct, Qwen3-0.6B — Apache-2.0 (указано в `ModelCatalog`).
* EmbeddingGemma 270M — Gemma Terms of Use.
* Код приложения — лицензия репозитория, см. [`LICENSE`](../LICENSE).

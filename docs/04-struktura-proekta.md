# 04. Структура проекта

## Дерево файлов

```
CallowFellow/
├── project.yaml                 # список модулей: androidApp, desktopApp, shared
├── kotlin, kotlin.bat           # wrapper Kotlin Toolchain (Amper) 0.12.0
├── gradle/libs.versions.toml    # version catalog (Amper читает из корня)
├── README.md
├── LICENSE
│
├── shared/                      # kmp/lib, платформы: jvm + android
│   ├── module.yaml
│   ├── src/                     # common-код
│   │   ├── task2/
│   │   │   ├── domain/          # ChatMessage, ChatRepository, MemoryStore, AiModel, EngineStatus
│   │   │   ├── data/            # ChatRepositoryImpl, Network, PromptBuilder, FactExtractor,
│   │   │   │                    #   ModelCatalog, RoomMemoryStore
│   │   │   ├── db/              # ChatDatabase (Room), ChatDao, MIGRATION_1_2
│   │   │   ├── llama/           # LlamaBridge, GenParams, CHATML_TEMPLATE, createLlamaBridge()
│   │   │   ├── presentation/    # ChatViewModel, ChatUiState, ChatIntent
│   │   │   ├── ui/              # App, ChatScreen
│   │   │   └── di/              # AppContainer
│   │   └── task3/
│   │       ├── domain/          # LlmChoice, GenPreset, DocInfo, ScoredChunk, AppVersion
│   │       ├── data/            # RagRepository, Chunkers, EmbedMath, EmbedCatalog,
│   │       │                    #   QueryRouter, WikiLoader, DeepSeekClient, Files, Http
│   │       ├── db/              # RagDatabase (Room), RagDao, entities
│   │       ├── llama/           # EmbedBridge, createEmbedBridge()
│   │       ├── presentation/    # Task3ViewModel, Task3UiState, Task3Intent, Tab
│   │       ├── ui/              # App, Root, ChatTab, DocsTab, SettingsTab, LlmScreen, EmbedScreen
│   │       └── di/              # Task3Container
│   ├── src@android/             # actual для Android
│   │   ├── task2/data/PlatformActual.kt
│   │   ├── task2/llama/LiteRtLlamaBridge.kt
│   │   ├── task3/data/FilesActual.kt, HttpActual.kt
│   │   └── task3/llama/LiteRtEmbedBridge.kt
│   ├── src@jvm/                 # actual для Desktop
│   │   ├── task2/data/PlatformActual.kt
│   │   ├── task2/llama/LiteRtLlamaBridge.kt
│   │   ├── task3/data/FilesActual.kt, HttpActual.kt
│   │   └── task3/llama/FakeEmbedBridge.kt
│   ├── test/                    # common-тесты
│   │   └── task2/LogicTest.kt
│   └── test@jvm/                # JVM-тесты (нужен java.io и реальная SQLite)
│       ├── task2/DbTest.kt
│       ├── task2/llama/FakeLlamaBridge.kt
│       ├── task3/DeleteDeselectTest.kt
│       ├── task3/ModelFileValidationTest.kt
│       ├── task3/QueryRouterTest.kt
│       └── task3/RagStaleNativeTest.kt
│
├── androidApp/                  # android/app, com.callowfellow
│   ├── module.yaml              # versionName/versionCode, minSdk 24
│   ├── proguard-rules.pro
│   └── src/
│       ├── AndroidManifest.xml  # INTERNET, label CallowFellow
│       └── MainActivity.kt
│
├── desktopApp/                  # jvm/app
│   ├── module.yaml
│   └── src/main.kt
│
└── docs/                        # эта документация
```

## Модули

| Модуль | Тип | Назначение |
|---|---|---|
| `shared` | `kmp/lib` (jvm + android) | Вся бизнес-логика, UI, порты и реализации |
| `androidApp` | `android/app` | Точка входа Android: БД, `modelsDir`, `MainActivity` |
| `desktopApp` | `jvm/app` | Точка входа Desktop: окно, `modelsDir`, обработка `Esc` |

Оба приложения — тонкие: создают `modelsDir`, собирают `RoomDatabase.Builder`,
конструируют `Task3Container` и вызывают `task3.ui.App(container.viewModel)`.
Вся логика — в `shared`.

## Разделение ответственности за платформу

| Что | Где решается | Почему |
|---|---|---|
| Путь к `models/` | Точка входа (`filesDir/models` на Android, `./models` на Desktop) | Только платформа знает, где у неё приватный каталог |
| Построитель Room-БД | Точка входа (нужен `Context` на Android) | `RoomDatabase.Builder<RagDatabase>` — платформенная деталь |
| `CoroutineScope` | `Task3Container` | Один scope на приложение; точка входа живёт весь процесс |
| Нативный инференс | `src@android` / `src@jvm` | `expect`/`actual` |
| Файловые утилиты | `src@android` / `src@jvm` | `java.io` недоступен в common |
| Сеть | `shared/src/task2/data/Network.kt` | Ktor + okio кроссплатформенны — `expect` не нужен |

## Соглашения об именовании

| Префикс | Значение |
|---|---|
| `expect fun` / `actual fun` | платформенный порт; сигнатуры обязаны совпадать |
| `Fake*` | тестовая заглушка порта (`FakeLlamaBridge`, `FakeEmbedBridge`) |
| `Stale*Bridge` | заглушка, имитирующая устаревшую `.so` (`UnsatisfiedLinkError`) |
| `*Repository` | реализация интерфейса из `domain`, владелец SSOT-потоков |
| `*Dao` / `*Entity` | слой Room |
| `*Screen` / `*Tab` | Compose-экран |
| `*Container` | ручной DI-контейнер |
| `K_*` (внутри `RagRepository`) | ключи настроек: `llm`, `embed`, `deepseek_key`, `gen_preset` |

Комментарии и KDoc — на русском, UI-строки — на русском; идентификаторы, KDoc
на «общетехнические» темы и сообщения об ошибках местами на английском.

## Историческое наследие `task2.*` / `task3.*`

Имена пакетов — наследие недель челленджа (`task2` = чат, `task3` = RAG, `task4`
= пресеты генерации). При переносе в отдельный репозиторий переименовано было
только приложение:

* `applicationId` / `namespace` → `com.callowfellow`;
* `android:label` → `CallowFellow`;
* заголовок окна Desktop → `CallowFellow`.

Пакеты `task2.*` / `task3.*` оставлены как есть, чтобы не ломать импорты.
`GenPreset` в коде всё ещё называется «Task 4 generation preset» — неделя 4
 челленджа посвящена пресетам, хотя код живёт в `task3.domain`.

Практическое следствие: при поиске «логики чата» смотрите и `task2.data.ChatRepositoryImpl`,
и `task3.data.RagRepository` — второй **не наследует** первый, а реализует свой,
более сложный сценарий. Что из `task2` реально используется продуктом:

| Используется | Не используется (точками входа) |
|---|---|
| `task2.domain.*` | `task2.db.*` |
| `task2.data.{ModelCatalog, Network, PromptBuilder, FactExtractor, RoomMemoryStore}` | `task2.data.ChatRepositoryImpl` |
| `task2.llama.*` | `task2.presentation.*`, `task2.ui.*`, `task2.di.*` |

## Что не попадает в репозиторий

Из [`.gitignore`](../.gitignore): `build/`, `generated/`, `.idea/`, `out/`,
`*.apk`, `*.aab`, `*.so`, `models/`, `*.litertlm`, `*.gguf`, `*.tflite`,
`release.keystore`, `keystore.properties`, `ui*.xml`.

То есть веса моделей и бинарные артефакты — только локально; `INTERNET`-доступ
нужен лишь для их первого скачивания.

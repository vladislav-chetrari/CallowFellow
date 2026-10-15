# 02. Архитектура

## Архитектурный стиль

CallowFellow — **Kotlin Multiplatform** приложение с **Clean Architecture** внутри
модуля `shared`, **MVVM + MVI-intents** на presentation-слое и **SSOT**
(single source of truth) на data-слое. Сборка — Kotlin Toolchain (Amper), DI — ручной
(KISS, без Hilt).

Четыре заявленных принципа (из README проекта):

* **Clean Architecture** — зависимости направлены внутрь: `domain` не знает о данных.
* **MVVM** — экран зависит только от `UiState` и `sendIntent(...)`.
* **SSOT** — единственный источник состояния (`MutableStateFlow` в репозитории).
* **SOLID / KISS** — тонкие порты (`LlamaBridge`, `EmbedBridge`), `expect`/`actual`
  только там, где реально нужен платформенный код.

## Слои

```
┌──────────────────────────────────────────────────────────────┐
│  presentation     ChatViewModel · Task3ViewModel             │
│                   UiState (data class) + Intent (sealed)     │
├──────────────────────────────────────────────────────────────┤
│  domain           ChatRepository · MemoryStore · AiModel      │
│                   EngineStatus · ChatMessage · LlmChoice      │
│                   GenPreset · DocInfo · ScoredChunk            │
├──────────────────────────────────────────────────────────────┤
│  data             ChatRepositoryImpl · RagRepository          │
│                   RoomMemoryStore · ModelCatalog · EmbedCatalog│
│                   Network (Ktor+okio) · PromptBuilder          │
│                   FactExtractor · WikiLoader · Chunkers        │
│                   EmbedMath · QueryRouter · DeepSeekClient     │
├──────────────────────────────────────────────────────────────┤
│  db               ChatDatabase (Room) · RagDatabase (Room)     │
├──────────────────────────────────────────────────────────────┤
│  llama (порты)    LlamaBridge · EmbedBridge · createXxx()      │
│                   expect/actual → LiteRT-LM / Fake            │
├──────────────────────────────────────────────────────────────┤
│  ui (Compose MP)  Root · ChatTab · DocsTab · SettingsTab       │
│                   LlmScreen · EmbedScreen                       │
├──────────────────────────────────────────────────────────────┤
│  di               AppContainer (task2) · Task3Container (task3)│
└──────────────────────────────────────────────────────────────┘
        ▲                                    ▲
        │ androidApp/MainActivity            │ desktopApp/main.kt
        └──────────── entry points ──────────┘
```

Пакеты верхних слоёв зависят от нижних, но не наоборот. Исключения оговорены явно:
`ChatDatabase.kt` лежит в `db` и импортируется из `data`/`di` — это инфраструктурная
зависимость, а не доменная.

## Два «продукта» в одном модуле

Исторически код разделён на две части, и это важно для понимания навигации по репозиторию.

### `task2.*` — фундамент

Базовый чат + всё, на чём стоит `task3`:

| Пакет | Содержимое | Используется продуктом? |
|---|---|---|
| `task2.domain` | `ChatMessage`, `ChatRepository`, `EngineStatus`, `MemoryStore`, `AiModel` | Да |
| `task2.data` | `ModelCatalog`, `Network`, `PromptBuilder`, `FactExtractor`, `RoomMemoryStore`, `ChatRepositoryImpl` | Да, кроме `ChatRepositoryImpl` |
| `task2.llama` | `LlamaBridge`, `GenParams`, `CHATML_TEMPLATE`, `createLlamaBridge()` | Да |
| `task2.db` | `ChatDatabase`, `ChatDao`, `MIGRATION_1_2` | Нет (в продукте не подключён) |
| `task2.presentation` | `ChatViewModel`, `ChatUiState`, `ChatIntent` | Нет |
| `task2.ui` | `ChatScreen`, `App` | Нет |
| `task2.di` | `AppContainer` | Нет |

`AppContainer` **не создаётся ни одной точкой входа**: обе они собирают `Task3Container`.
Это наследие переноса — базовый чат-экран оставлен как рабочий, но не подключённый код
(см. [11. Ограничения](11-ogranicheniya.md)).

### `task3.*` — собственно продукт

RAG-приложение: настройки моделей, документы, индекс, чат с ретривом, DeepSeek.

| Пакет | Содержимое |
|---|---|
| `task3.domain` | `LlmChoice`, `GenPreset`, `DocInfo`, `ScoredChunk`, `AppVersion` |
| `task3.data` | `RagRepository` (SSOT), `EmbedCatalog`, `Chunkers`, `EmbedMath`, `QueryRouter`, `WikiLoader`, `DeepSeekClient`, `Files`, `Http` |
| `task3.db` | `RagDatabase`, `RagDao`, `ChunkEntity`, `DocumentEntity`, `RagMessageEntity`, `SettingEntity` |
| `task3.llama` | `EmbedBridge`, `createEmbedBridge()` |
| `task3.presentation` | `Task3ViewModel`, `Task3UiState`, `Task3Intent`, `Tab` |
| `task3.ui` | `App`, `Root`, `ChatTab`, `DocsTab`, `SettingsTab`, `LlmScreen`, `EmbedScreen` |
| `task3.di` | `Task3Container` |

## Потоки данных

### Загрузка и генерация (интерактив)

```
Compose-экран
   │  sendIntent(Task3Intent.Send)
   ▼
Task3ViewModel                     scope = CoroutineScope(SupervisorJob + Main.immediate)
   │  job = scope.launch { repo.send(text) }
   ▼
RagRepository.send(prompt)
   ├─ QueryRouter.isSmallTalk(prompt) ?  → context = emptyList()
   │  иначе retrieve(prompt)
   │      ├─ EmbedBridge.embed([query])          ← EmbeddingGemma, CPU
   │      ├─ cosineTopK(q, allChunks, topK = 5)   ← brute force
   │      ├─ гейт 1: score >= MIN_SCORE (0.55)
   │      └─ гейт 2: hasTokenOverlap(query, hits)
   │
   ├─ answerLocal(AiModel, ...)                  ← LiteRT-LM, Flow<String>
   │  или answerCloud(...)                       ← DeepSeekClient.complete
   ├─ PromptBuilder.cleanReply(text)
   └─ + refsBlock(context)  →  «Источники:\n[1] …»
   ▼
_messages: MutableStateFlow<List<ChatMessage>>    ← SSOT
   │  write-through в rag.db (insertMessage)
   ▼
Task3ViewModel: combine(13 потоков) → Task3UiState
   │  collectAsState()
   ▼
ChatTab (Compose) — перерисовка списка и статуса
```

Нативная генерация стримит дельты; в `answerLocal` они накапливаются в `acc`,
а в UI попадают одним куском после завершения (для RAG-режима это осознанное упрощение:
нужен полный ответ для блока источников). В базовом чате `ChatRepositoryImpl`
(_`shared/src/task2/data/ChatRepositoryImpl.kt`_) дельты попадают в UI по мере поступления.

### Индексация документа

```
DocsTab → Task3Intent.WikiConfirmed
   ▼
RagRepository.addWikiDoc(input)
   ├─ EmbedCatalog.resolve(_embedId) → проверка «модель выбрана и скачана»
   ├─ WikiLoader.fetch(input)                 ← Wikipedia API
   ├─ DocumentEntity(status = "indexing")     ← UI показывает прогресс
   ├─ StructureChunker(maxChars = 1000).chunk(doc)
   ├─ повторное использование векторов: dao.findVectorByText(text) при dim == embBridge.dim
   ├─ EmbedBridge.embed(batch of 16)          ← _indexProgress: 0.0 … 1.0
   ├─ dao.deleteChunksOf(source) + dao.upsertChunks(...)
   └─ DocumentEntity(status = "ready" | "error")
```

### MVVM-контракт

* **UiState** — один `data class` на экран (`Task3UiState`, 31 поле; `ChatUiState`, 5 полей).
  В нём же — «производные» поля `canChat`, `chatHint`, `docsHint`, `llmLabel`,
  вычисленные в `Task3ViewModel.collect(...)` из потоков репозитория. UI эти поля
  только читает, логики нет.
* **Intent** — `sealed interface`, исчерпывающий `when` в `sendIntent`. Ни один экран
  не вызывает репозиторий напрямую.
* **StateFlow везде** — от репозитория до `collectAsState()`; `collectAsStateWithLifecycle`
  не используется.

## SSOT

Каждый экран имеет **один** источник истины, наружу торчат только `StateFlow`:

| Репозиторий | SSOT-потоки |
|---|---|
| `RagRepository` | `llmChoice`, `embedId`, `deepSeekKey`, `preset`, `llmStatus`, `embedStatus`, `downloading`, `downloadedLlm`, `downloadedEmbed`, `docs`, `indexProgress`, `messages`, `lastMaxScore` |
| `ChatRepositoryImpl` | `messages`, `status` |

Инварианты, которые SSOT поддерживает:

* История читается из БД **один раз** при старте (`historyLoaded`), дальше только
  write-through в `_messages` и в Room.
* Каталог скачанных файлов — результат `platformFileExists` по каталогу, а не флаг в БД.
* Удаление выбранной модели **снимает выбор** (`deleteLlm` / `deleteEmbed`), иначе UI
  зависает на несуществующем файле.
* Смена embedding-модели **очищает индекс**: векторы разной размерности несравнимы
  (`dao.clearChunks()` + сброс `status` документов в `ready`).
* Удаление embedding-файла выбор **не** сбрасывает полностью в БД-инварианты: выбор
  снимается, но индекс сохраняется — после повторного скачивания той же модели
  чанки снова валидны и переиндексация не нужна.

## Асинхронность и отмена

* Один `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` на контейнер;
  ViewModel получает его снаружи — сам scope не создаёт и не отменяет (владелец —
  точка входа, которая живёт весь процесс).
* `ChatRepositoryImpl` / `RagRepository` ловят `CancellationException` **до** `Exception`
  и перебрасывают его, иначе отмена превратилась бы в «Ошибка».
* `rethrowIfFatal(e)` в `RagRepository` дополнительно пробрасывает `OutOfMemoryError`:
  старый `.so` без новых символов кидает `UnsatisfiedLinkError` (это `Error`, а не
  `Exception`) — раньше он проскакивал мимо `catch (Exception)` и ронял приложение.
  Это зафиксировано тестом `RagStaleNativeTest`.
* Отмена генерации: `bridge.cancel()` закрывает активную нативную `Conversation`,
  что прерывает декод; состояние возвращается в `Ready`.

## Многопоточность

* Нативные вызовы LiteRT-LM обёрнуты в `flowOn(Dispatchers.IO)` / `withContext(Dispatchers.IO)`.
* `generateChat` возвращает `Flow<String>`; каждый элемент проверяет
  `currentCoroutineContext().ensureActive()` — отмена видна между дельтами.
* Активная `Conversation` хранится в `@Volatile`-поле: `cancel()` вызывается из другого
  потока (UI), декод идёт на IO.
* `DeepSeekClient` кэширует `LLMClient` в `@Volatile`-полях вместе с ключом: создавать
  Ktor-клиент на каждый запрос — лишние потоки и сокеты.

## Границы платформ и `expect`/`actual`

Платформенный код сведён к минимуму — только там, где в common-коде нет API.
Всего в `src@android` и `src@jvm` **10 файлов** против 38 общих:

| Объявление (common) | Android | JVM Desktop |
|---|---|---|
| `createLlamaBridge()` | `LiteRtLlamaBridge` (LiteRT-LM) | `LiteRtLlamaBridge` (LiteRT-LM) |
| `createEmbedBridge()` | `LiteRtEmbedBridge` (`EmbeddingEngine`) | `FakeEmbedBridge` (хеш-векторы) |
| `platformFileExists(path)` | `java.io.File` | `java.io.File` |
| `platformCpuThreads()` | `availableProcessors() - 2` (мин. 4) | то же |
| `nowMillis()` | `System.currentTimeMillis()` | то же |
| `fileSizeBytes(path)` | `java.io.File` | `java.io.File` |
| `deleteFile(path)` | `java.io.File` | `java.io.File` |

На Android и Desktop генеративные мосты **одинаковы** (раньше на JVM был `Fake`,
теперь десктоп считает по-настоящему). Различается только эмбеддинговый мост:
нативного `EmbeddingEngine` для JVM нет, поэтому на десктопе — `FakeEmbedBridge`
с хеш-векторами (детерминированно, но качество поиска нерелевантно —
это осознанный компромисс ради показа UI индексации).

Сетевой слой `expect`/`actual` **не использует**: Ktor CIO + okio работают в common,
результат — один файл `Network.kt` вместо двух копий `HttpURLConnection`.

## Слои тестируемости

Порты `LlamaBridge` и `EmbedBridge` — главный рычаг: подменяя их, тесты гоняют
репозиторий целиком без нативных библиотек и без сети.

| Подмена | Где | Кто |
|---|---|---|
| `FakeLlamaBridge` | `shared/test@jvm/task2/llama/` | `DeleteDeselectTest` |
| `FakeEmbedBridge` | `shared/src@jvm/task3/llama/` (основной код) | `DeleteDeselectTest`, ручной запуск desktop |
| `StaleGenBridge` / `StaleEmbedBridge` | внутри `RagStaleNativeTest` | тест на `UnsatisfiedLinkError` |
| `ChatDatabaseV1` | внутри `DbTest` | тест миграции 1→2 |

Подробнее — [10. Тесты](10-testy.md).

## Диаграмма компонентов (упрощённо)

```mermaid
graph TD
    subgraph entry["Точки входа"]
        MA[MainActivity<br/>androidApp]
        DM[main.kt<br/>desktopApp]
    end

    subgraph di["DI"]
        TC[Task3Container]
    end

    subgraph pm["presentation"]
        VM[Task3ViewModel]
    end

    subgraph data["data"]
        RR[RagRepository]
        NB[Network<br/>Ktor + okio]
        WL[WikiLoader]
        CH[StructureChunker]
        PB[PromptBuilder]
        DS[DeepSeekClient]
    end

    subgraph ports["порты"]
        LB[LlamaBridge]
        EB[EmbedBridge]
    end

    subgraph impl["реализации"]
        LG[LiteRtLlamaBridge<br/>android | jvm]
        LE[LiteRtEmbedBridge<br/>android]
        FE[FakeEmbedBridge<br/>jvm]
        KS[Koog DeepSeekLLMClient]
    end

    subgraph store["хранение"]
        RDB[(rag.db<br/>Room)]
    end

    subgraph ui["ui"]
        UI[Root / ChatTab / DocsTab / SettingsTab]
    end

    MA --> TC
    DM --> TC
    TC --> RR
    TC --> VM
    VM --> UI
    UI -- Intent --> VM
    VM -- StateFlow --> UI
    RR --> NB
    RR --> WL
    RR --> CH
    RR --> PB
    RR --> DS
    RR --> LB
    RR --> EB
    RR --> RDB
    LB --> LG
    EB --> LE
    EB --> FE
    DS --> KS
```

## Управление состояниями ошибок

Две независимые шкалы состояний:

* `EngineStatus` (`task2.domain`) — `Idle | Downloading(progress) | LoadingModel | Ready | Generating | Error(message)`.
* `EmbedState` (`task3.data`) — `Unset | Downloading(progress) | Loading | Ready | Error(message)`.

Правила отображения:

* Глобальный статус (`_llmStatus`, `_embedStatus`) выставляется **только для выбранной
  модели**. Иначе прогресс чужой загрузки «загорался» бы на карточке выбранной
  (помечено комментарием в `RagRepository.downloadLlm` / `downloadEmbed`).
* На карточке модели чужое состояние `Downloading` не рисуется — выводится `null`.
* `EngineStatus.Error` в UI даёт карточку с текстом и кнопкой «Повторить»
  (`Task3Intent.Retry` для локальной модели).

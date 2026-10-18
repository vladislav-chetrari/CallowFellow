# 10. Тесты

## Сводка

**23 теста в 6 классах.** Фреймворк — `kotlin.test` поверх JUnit 5
(платформа 6.1.3). Мок-библиотеки не используются: вместо них вручную написанные
реализации портов.

| Файл | Тестов | Что проверяет |
|---|---|---|
| `shared/test/task2/LogicTest.kt` | 12 | чистая логика common-кода |
| `shared/test@jvm/task3/QueryRouterTest.kt` | 2 | маршрутизация small-talk |
| `shared/test@jvm/task3/ModelFileValidationTest.kt` | 3 | проверка размера скачанной модели |
| `shared/test@jvm/task3/DeleteDeselectTest.kt` | 2 | удаление выбранной модели снимает выбор |
| `shared/test@jvm/task3/RagStaleNativeTest.kt` | 2 | устаревшая `.so` не роняет приложение |
| `shared/test@jvm/task2/DbTest.kt` | 2 | Room CRUD и миграция v1→v2 |

## Запуск

```sh
./kotlin test                                  # все тесты проекта
./kotlin task :shared:testJvm                  # JVM-вариант
./kotlin task :androidApp:testAndroidDebug     # Android-вариант
./kotlin check                                 # проверки (сейчас только tests)
```

Android-тестов (instrumented, на устройстве) в проекте нет — каталог `androidAppTest`
пуст, есть только `compileAndroidTestDebug` как зависимость сборки.

## Разделение по source set

| Source set | Что можно тестировать | Файлы |
|---|---|---|
| `shared/test` (common) | Только чистые функции без платформенных API | `LogicTest.kt` |
| `shared/test@jvm` | Всё, что нужно `java.io` и настоящей SQLite | остальные 5 классов |

Причина разделения: тесты Room работают с временными файлами (`File.createTempFile`),
а `RagRepository` вызывает `platformFileExists` / `nowMillis` — это `actual`-функции,
доступные только на JVM.

## Тестовые doubles

| Double | Расположение | Поведение |
|---|---|---|
| `FakeLlamaBridge` | `shared/test@jvm/task2/llama/` | `load()` запоминает путь, `generateChat()` отдаёт один элемент `"fake-reply"` |
| `FakeEmbedBridge` | `shared/src@jvm/task3/llama/` (в основном коде!) | `hashingEmbed` с `delay`, dim 768 |
| `StaleGenBridge` | внутри `RagStaleNativeTest` | `load()` кидает `UnsatisfiedLinkError("fake missing symbol nativeInit")` |
| `StaleEmbedBridge` | внутри `RagStaleNativeTest` | `load()`/`embed()` кидают `UnsatisfiedLinkError` |
| `ChatDatabaseV1`, `FactV1`, `V1Dao` | внутри `DbTest` | схема БД **до** миграции |

`FakeEmbedBridge` лежит в `src@jvm` (production), а не в тестах: он же используется
при десктопном запуске для предпросмотра UI индексации. Это осознанный размен:
на JVM нет нативного `EmbeddingEngine`, а показывать интерфейс нужно.
Комментарий в коде фиксирует, что векторы при этом мусорные, но стабильные.

## Что именно покрыто

### `LogicTest` (12) — чистые функции

**Каталог моделей**
* `defaultModelIsQwen2515b` — по умолчанию `qwen25-1.5b-q8`, в каталоге 5 моделей.

**Промпты**
* `chatPromptHasNoChatMl` — ни system, ни user не содержат `<|im_start|>`: шаблон
  применяет сам LiteRT-LM. Плюс проверка, что в user есть и история, и текущая реплика.
* `promptDetectsLanguageFromDialogue` — в промпте есть формулировка «на том же языке»
  и нет жёсткого «Отвечай по-русски» / `/no_think`.
* `factsInjectedIntoSystem` — факты попадают в system-блок с маркером `- `.
* `slidingWindowKeepsLast10` — при 14 сообщениях в промпте есть `msg-13` и `msg-4`,
  но нет `msg-3`: окно из 10 работает.

**Постобработка ответа**
* `cleanReplyStripsThink` — три сценария: полный блок `<think>…</think>`,
  хвост `<|im_end|>`, незакрытый `<think>` в конце (обрыв модели).

**Извлечение фактов**
* `extractNameRu` — «Меня зовут Влад» → `Имя пользователя — Влад`.
* `extractNameEn` — «My name is Vlad, remember it» → `Имя пользователя — Vlad`.
* `extractLikes` — RU и EN варианты → `Нравится: …`.
* `extractRemember` — «запомни: я работаю врачом» → факт без префикса.
* `extractIgnoresChitchat` — пусто для «Привет, как дела?», `I'm fine today`
  (срабатывает `NAME_STOP`) и `remember it` (слишком короткий результат).

**Дедупликация фактов**
* `sameFactDedupesCaseInsensitive` — «Меня зовут Влад» == «меня зовут влад»,
  но «Любит кофе» ≠ «Любит чай».

### `QueryRouterTest` (2)

* `greetingsAreSmallTalk` — «привет», «Привет!», «как дела?», «спасибо»,
  «hello», «hi» → small-talk.
* `questionsAreNotSmallTalk` — «расскажи про воронов», «расскажи про дроздов»,
  пустая строка, «а вороны крупнее воронов?» → **не** small-talk.

Последний случай важен: проверяется именно `Regex.matches` (совпадение целиком),
поэтому «привет, расскажи про воронов» не считается приветствием и уходит в ретрив.

### `ModelFileValidationTest` (3)

* `fullSizePasses` — файл 160 МБ при ожидаемых 157 МБ проходит (`null`).
* `truncatedFileFails` — 5 МБ при ожидаемых 157 → ошибка со словом «оборван».
* `missingFileFails` — несуществующий путь → ошибка со словом «недоступен».

### `DeleteDeselectTest` (2)

Репозиторий собирается с фейковыми мостами и **настоящей** SQLite во временном каталоге.

* `deleteSelectedLlmClearsChoice` — выбираем модель, создаём файл, удаляем →
  `llmChoice == null`.
* `deleteSelectedEmbedClearsChoice` — то же для embedding-модели → `embedId == null`.

Тест закрывает реальный класс багов: если бы выбор не снимался, UI зависал бы
на ссылке на несуществующий файл.

### `RagStaleNativeTest` (2)

Имитирует ситуацию «в APK попала старая `.so` без новых нативных символов».
Ключевой момент: `UnsatisfiedLinkError` — это `Error`, а **не** `Exception`,
поэтому `catch (e: Exception)` его не ловит и приложение падает.

* `staleEmbedLibSurfacesErrorInsteadOfCrash` — `ensureEmbedLoaded()` не бросает наружу,
  а переводит состояние в `EmbedState.Error`.
* `staleGenLibSurfacesErrorInChatInsteadOfCrash` — `send("привет")` не бросает,
  статус становится `EngineStatus.Error`, а в ленте появляется сообщение,
  начинающееся с `"Ошибка:"`.

Тесты — исполняемый регрессионный след для механизма `rethrowIfFatal`
в `RagRepository`.

### `DbTest` (2)

Работает на реальной SQLite (`BundledSQLiteDriver`) в `File.createTempFile`.

* `daoCrud` — вставка сообщений и чтение по порядку; вставка 5 фактов и
  `trimFacts(3)` оставляет последние три; очистка сообщений.
* `migrate1to2KeepsDataAndDefaultsSource` — полноценный сценарий миграции:
  1. создаётся БД **схемы v1** (локальные entity `MessageV1` / `FactV1` без колонки `source`);
  2. записываются данные;
  3. та же БД открывается текущей схемой с `MIGRATION_1_2`;
  4. проверяется, что сообщения и факты на месте, а `facts.source` получила `'rules'`.

Отдельная схема v1 объявлена прямо в тесте — KSP генерирует для неё свой Impl,
и production-код о нём ничего не знает.

## Чего в тестах нет

Честный список пробелов — они же ориентир для будущей работы:

* **Нет тестов на `StructureChunker`** — самая «алгоритмичная» часть RAG
  (три уровня деления текста, тегирование секций) не покрыта.
* **Нет тестов на `EmbedMath`**: `l2normalize`, `hashingEmbed`, `cosineTopK`,
  конвертация `FloatArray ↔ BLOB`. Конвертация — место, где тихо портится
  little-endian на не-`x86` платформе (например, на ARM в старых версиях JVM).
* **Нет тестов на `PromptBuilder.ragSystem` и контекстный блок** — соответствие
  нумерации ссылок `[1..n]` между `contextBlock` и `refsBlock` не проверяется.
* **Нет тестов на `Network.downloadFile`** — ретраи, `.part`, атомарное переименование.
  Проверить можно было бы через локальный HTTP-сервер или `MockEngine` Ktor.
* **Нет тестов на `QueryRouter` для английских вариантов** «thank you», «bye».
* **Нет тестов на `Task3ViewModel`** — логика вычисления `canChat` / `chatHint`
  и синхронный guard против параллельного скачивания не проверены.
* **Нет тестов на `FactExtractor` для `NAME_STOP`/`PRONOUN_STOP` по отдельности**
  и на лимиты (`maxCount = 3`, `maxLen = 120`).
* **Нет инструментальных тестов на реальном устройстве** — LiteRT-LM
  (GPU-откат на CPU, потоковый вывод, отмена генерации) проверяется только вручную.

## Как добавить тест

* Чистая функция, нет платформенных API → `shared/test/…`.
* Нужны `java.io` или настоящая БД → `shared/test@jvm/…`.
* Нужны порты → подменить `LlamaBridge` / `EmbedBridge` в конструкторе `RagRepository`.
* Нужна схема БД «до миграции» → объявить entity и `@Database(version = 1)`
  прямо внутри теста, как это сделано в `DbTest`.

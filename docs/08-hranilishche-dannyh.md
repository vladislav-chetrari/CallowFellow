# 08. Хранение данных

## Раскладка на диске

### Android

```
filesDir/
└── models/                     # File(filesDir, "models").apply { mkdirs() }
    ├── rag.db                  # Room: документы, индекс, чат, настройки
    ├── llm/
    │   ├── Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm   (~1.5 ГБ)
    │   └── qwen3_0.6b_q4_block32_ekv1280.litertlm                          (~331 МБ)
    └── embed/
        └── embeddinggemma-2-text-270m.litertlm                             (~157 МБ)
```

### Desktop

```
./models/                       # File("models").apply { mkdirs() }, отн. рабочего каталога
├── rag.db
├── llm/
└── embed/
```

Пути вычисляются **точками входа** и передаются в контейнер:

```kotlin
// androidApp/src/MainActivity.kt
val modelsDir = File(filesDir, "models").apply { mkdirs() }.absolutePath
// desktopApp/src/main.kt
val modelsDir = File("models").apply { mkdirs() }.absolutePath
```

Репозиторий их только читает: `llmFile(model) = "$modelsDir/llm/${model.fileName}"`,
`embedFile(model) = "$modelsDir/embed/${model.fileName}"`.

Модель считается «скачанной», если `platformFileExists(path)` вернула `exists() && length() > 0`
— никакого флага в БД. Побочный эффект: файл, удалённый с диска вручную, корректно
исчезает из UI.

Веса и файлы моделей в `.gitignore` (`*.litertlm`, `*.gguf`, `*.tflite`, `models/`).

---

## База `rag.db` (основная, версия 2)

Объявлена в [`shared/src/task3/db/RagDatabase.kt`](../shared/src/task3/db/RagDatabase.kt),
схема генерируется KSP. Пять таблиц.

### `chunks` — индекс базы знаний

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `String` (PK) | `"{source}#{ord}#{embedId}"` |
| `source` | `String` | `wikipedia/{lang}/{title}` |
| `title` | `String` | заголовок документа |
| `section` | `String` | секция документа, к которой относится чанк |
| `ord` | `Int` | порядковый номер чанка в документе |
| `text` | `String` | текст чанка (с тегом секции, если она именованная) |
| `dim` | `Int` | размерность вектора |
| `embedding` | `ByteArray` (BLOB) | float32 little-endian, 4 байта на компоненту |

Составной `id` означает, что разные embedding-модели не конфликтуют: чанки одной
статьи, посчитанные разными моделями, занимают разные строки.

Следствия формата:

* `embedding` — сырой little-endian float32, а не JSON: `FloatArray.toBlob()`
  пишет `bits and 0xFF` по возрастанию байтов, обратное чтение — `ByteArray.toFloatArray()`
  с `Float.fromBits`. Это компактно и не требует парсера при поиске.
* `dim` хранится **вместе с вектором**, поэтому поиск может отфильтровать
  несовместимые размерности ещё до сканирования.
* Дубликаты текста допускаются — `findVectorByText` берёт `LIMIT 1`.

Запросы `RagDao`:

| Метод | SQL-смысл |
|---|---|
| `upsertChunks(items)` | `@Upsert` — массовая вставка/обновление |
| `chunksOf(source)` | все чанки документа по `ord ASC` |
| `allChunks()` | все чанки, `source ASC, ord ASC` |
| `findVectorByText(text)` | `SELECT text, embedding, dim … LIMIT 1` — переиспользование вектора |
| `deleteChunksOf(sources)` | удалить чанки документов |
| `clearChunks()` | очистить весь индекс (при смене embedding-модели) |
| `chunkCount()` | `COUNT(*)` |

### `documents` — метаданные документов

| Поле | Тип | Смысл |
|---|---|---|
| `source` | `String` (PK) | идентификатор документа |
| `title` | `String` | заголовок |
| `status` | `String` | `indexing` \| `ready` \| `error` |
| `error` | `String` | текст ошибки индексации (пусто при успехе) |
| `active` | `Boolean` | участвует ли в поиске (переключатель «В поиске») |
| `updatedAt` | `Long` | `nowMillis()` последнего изменения |

### `chats` — список диалогов

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `Long` (autogenerate) | — |
| `title` | `String` | заголовок; пустая строка = «Без названия», первое сообщение подставляется как авто-заголовок |
| `createdAt` | `Long` | `nowMillis()` создания |
| `updatedAt` | `Long` | `nowMillis()` последнего ответа — по нему список отсортирован |

Список для drawer'а берётся одним запросом `chatRows()`: `COUNT(*)` и текст
последнего сообщения считаются подзапросами, чтобы не делать запрос на чат.
Пустой список невозможен: при первом запуске и после удаления последнего чата
создаётся новый пустой чат.

### `messages` — история чата RAG

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `Long` (autogenerate) | ключ строки в ленте (`LazyColumn key`) |
| `chatId` | `Long` (FK → `chats.id`, `ON DELETE CASCADE`, индекс) | владелец сообщения |
| `role` | `String` | `"USER"` \| `"ASSISTANT"` |
| `text` | `String` | текст |
| `createdAt` | `Long` | `nowMillis()` |

Роли хранятся строками, при чтении мапятся в `ChatMessage.Role` функцией
`mapRole` (всё, что не `"ASSISTANT"`, трактуется как `USER`).
`id` сохраняется при чтении, поэтому ключи ленты стабильны между запусками
и не пересекаются у разных чатов.

### Миграция v1 → v2 (появление чатов)

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    // создаётся chats со строкой 1 «Чат», затем messages пересоздаётся
    // с колонкой chatId и копированием старых строк (chatId = 1),
    // создаётся индекс index_messages_chatId
}
```

* Таблицу `messages` приходится **пересоздавать**, а не править через `ALTER TABLE`:
  SQLite не умеет добавлять `NOT NULL`-колонку с внешним ключом. Колонка объявлена
  с `DEFAULT 1` (`@ColumnInfo(defaultValue = "1")`) — иначе Room не сойдётся со
  схемой после миграции.
* Старая переписка не теряется: она становится чатом №1 «Чат».
* Миграция покрыта тестом `ChatsTest.migrate1to2KeepsHistoryInFirstChat`: он создаёт
  БД **схемы v1** (локальные `RagDatabaseV1`, `ChunkV1`, `DocumentV1`,
  `RagMessageV1`, `SettingV1` внутри теста), наполняет её данными, затем открывает
  текущую схему с `MIGRATION_1_2`. Все таблицы v1 объявлены обязательно: Room
  сверяет схему целиком и падает на любой недостающей таблице.

### `settings` — настройки (ключ-значение)

| Поле | Тип |
|---|---|
| `key` | `String` (PK) |
| `value` | `String` |

Ключи: `llm`, `embed`, `deepseek_key`, `gen_preset` (см. [07. API](07-api.md#ключи-настроек-таблица-settings-в-ragdb)).

---

## База `chat.db` (базовый чат, версия 2)

Объявлена в [`shared/src/task2/db/ChatDatabase.kt`](../shared/src/task2/db/ChatDatabase.kt).
**В продукте не используется** — ни одна точка входа не создаёт `AppContainer`,
а значит, файл `chat.db` в текущем приложении не создаётся. Таблицы описаны,
потому что код и тесты миграции находятся в репозитории.

### `messages`

`id` (autogenerate), `role` (`"USER"` \| `"ASSISTANT"`), `text`, `createdAt`.

### `facts` — долговременная память

| Поле | Тип | Смысл |
|---|---|---|
| `id` | `Long` (autogenerate) | — |
| `text` | `String` | формулировка факта, например `Имя пользователя — Влад` |
| `createdAt` | `Long` | `nowMillis()` |
| `source` | `String` | `rules` — детерминированные правила; задел под LLM-извлекатель |

### Миграция v1 → v2

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `facts` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'rules'")
    }
}

fun buildChatDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase =
    builder.addMigrations(MIGRATION_1_2).build()
```

* Миграции объявлены **в коде**, а не скриптами Flyway: у Flyway нет JDBC-драйвера
  для Android (так записано в KDoc).
* Деструктивных миграций нет — `.fallbackToDestructiveMigration()` не используется,
  пользовательские данные не теряются при обновлении.
* Миграция покрыта тестом `DbTest.migrate1to2KeepsDataAndDefaultsSource`,
  который сначала создаёт БД **схемы v1** (локальные `ChatDatabaseV1`/`FactV1`
  внутри теста), наполняет её данными, затем открывает текущую схему с
  `MIGRATION_1_2` и проверяет, что данные на месте, а `source` получила дефолт.

---

## Стратегии записи

| Сценарий | Поведение |
|---|---|
| Отправка сообщения | write-through: запись в `RagMessageEntity` активного `chatId` сразу после успешной генерации; при ошибке запись не делается, а в сообщение попадает текст `Ошибка: …` |
| Чтение истории | один раз при `init()`, дальше строки не перечитываются; при смене чата — `messagesOf(chatId)` |
| Первое сообщение в чате | авто-заголовок: `renameChat(chatId, prompt.take(60))`, если заголовок пуст |
| Создание чата | новая строка в `chats` + переключение на неё, сообщений нет |
| Удаление чата | `deleteChat(id)` в транзакции (сообщения + строка); если чат был последним — создаётся новый пустой |
| Очистка чата | `_messages = emptyList()` + `dao.clearMessagesOf(activeChatId)` — остальные чаты не затрагиваются |
| Новый документ | `upsertDoc(status = "indexing")` → чанки → `upsertDoc(status = "ready" \| "error")` |
| Переиндексация | `deleteChunksOf(listOf(source))` + `upsertChunks(...)` — атомарно в пределах одной операции БД |
| Смена embedding-модели | `clearChunks()` + сброс `status` документов в `ready` (векторы несравнимы) |
| Удаление документа | `deleteChunksOf` + `deleteDocs` |
| Обновление фактов | `insertFacts` + `trimFacts(20)` |

## Политика памяти

* **Индекс.** Векторы всех чанков живут в памяти во время поиска
  (`dao.allChunks()` → `FloatArray` → `cosineTopK`). Для нескольких статей Википедии
  это десятки мегабайт; ANN-индекса нет сознательно (см. [06](06-rag-pipeline.md#этап-4-поиск)).
* **Факты.** Жёсткий предел **20** записей, реализован и в памяти
  (`takeLast(maxFacts)`), и в БД (`DELETE … ORDER BY id DESC LIMIT :keep`).
* **История чата.** Не обрезается — растёт бесконечно; в контекст попадает
  только скользящее окно (10 сообщений в базовом чате, 6 в RAG). Предела на
  число чатов тоже нет: удаление — только явное, из drawer'а.
* **Кэш документов в UI.** `docs` — вычисляемый список: `chunks.groupBy { source }`
  плюс записи из `documents`, отсортированный по `title.lowercase()`.
  Для источника, который есть только в чанках, заголовок берётся из первого чанка.

## Обработка ошибок доступа к БД

Операции чтения обёрнуты в `runCatching`:

```kotlin
runCatching { _messages.value = dao.messagesOf(chatId).map { … } }
```

Смысл: повреждённый или заблокированный файл БД не должен ронять запуск приложения —
приложение поднимется с пустым состоянием и даст пользователю перезагрузить данные.
Ошибки записи (например, `insertMessage`) также не прерывают генерацию: ответ
показывается в чате, но не сохраняется.

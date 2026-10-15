# Документация CallowFellow

Документация к репозиторию [CallowFellow](../README.md) — офлайн-чат с LLM и RAG,
которые работают прямо на устройстве.

Проект — Kotlin Multiplatform приложение (Android + Desktop), собираемое
**Kotlin Toolchain (Amper)**, а не Gradle. LLM и эмбеддинги выполняются локально
через **LiteRT-LM** от Google AI Edge.

## Карта документации

| Документ | О чём |
|---|---|
| [01. Обзор](01-obzor.md) | Что за продукт, сценарии использования, пользовательские сценарии |
| [02. Архитектура](02-arhitektura.md) | Слои, Clean Architecture, MVVM/MVI, SSOT, DI, потоки данных |
| [03. Технологии](03-tehnologii.md) | Стек, версии библиотек, почему именно они, что даёт каждая |
| [04. Структура проекта](04-struktura-proekta.md) | Модули, source sets, дерево пакетов, соглашения об именовании |
| [05. Сборка и запуск](05-sborka-i-zapusk.md) | Команды `./kotlin`, запуск на Android/Desktop, версионирование, релиз |
| [06. RAG-пайплайн](06-rag-pipeline.md) | Загрузка документов, чанкинг, эмбеддинги, поиск, гейты, генерация |
| [07. API](07-api.md) | Внешние HTTP API, HTTP-клиент, LiteRT-LM, DeepSeek, внутренние интерфейсы |
| [08. Хранение данных](08-hranilishche-dannyh.md) | Room-БД, схемы, миграции, файлы моделей, вектора |
| [09. UI и навигация](09-ui.md) | Экраны, табы, диалоги, состояния `EngineStatus`/`EmbedState` |
| [10. Тесты](10-testy.md) | Что покрыто, чем, какие фикстуры, как запустить |
| [11. Ограничения и заметки](11-ogranicheniya.md) | Технический долг, известные грабли, точки роста |

## Быстрый старт

```sh
./kotlin build                  # собрать все модули
./kotlin test                   # прогнать тесты
./kotlin run -m desktopApp      # десктопный превью (модель скачается сама)
./kotlin run -m androidApp      # сборка и запуск на устройстве/эмуляторе
```

Подробности — в [05. Сборка и запуск](05-sborka-i-zapusk.md).

## Ключевые ссылки в коде

| Что | Где |
|---|---|
| Точка входа Android | [`androidApp/src/MainActivity.kt`](../androidApp/src/MainActivity.kt) |
| Точка входа Desktop | [`desktopApp/src/main.kt`](../desktopApp/src/main.kt) |
| DI-контейнер продукта | [`shared/src/task3/di/Task3Container.kt`](../shared/src/task3/di/Task3Container.kt) |
| SSOT RAG (настройки, документы, чат) | [`shared/src/task3/data/RagRepository.kt`](../shared/src/task3/data/RagRepository.kt) |
| SSOT базового чата | [`shared/src/task2/data/ChatRepositoryImpl.kt`](../shared/src/task2/data/ChatRepositoryImpl.kt) |
| Мост к LiteRT-LM (интерфейс) | [`shared/src/task2/llama/LlamaBridge.kt`](../shared/src/task2/llama/LlamaBridge.kt) |
| Мост к эмбеддингам (интерфейс) | [`shared/src/task3/llama/EmbedBridge.kt`](../shared/src/task3/llama/EmbedBridge.kt) |
| Промпты | [`shared/src/task2/data/PromptBuilder.kt`](../shared/src/task2/data/PromptBuilder.kt) |
| Каталог моделей | [`shared/src/task2/data/ModelCatalog.kt`](../shared/src/task2/data/ModelCatalog.kt), [`shared/src/task3/data/EmbedCatalog.kt`](../shared/src/task3/data/EmbedCatalog.kt) |
| Сетевой слой | [`shared/src/task2/data/Network.kt`](../shared/src/task2/data/Network.kt) |
| Версии библиотек | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) |

# CallowFellow — on-device LLM + RAG (LiteRT-LM)

Product ported from `Week 5` (AI-Advent-Challenge) into a standalone repository.
Version: **0.1.0** (see `gradle/libs.versions.toml [versions].app` /
`app-code`, mirrored in `shared/src/task3/domain/AppVersion.kt` and
`androidApp/module.yaml versionName`/`versionCode`).

The app sends requests to a **built-in** LLM, renders the answers, and works
**without the cloud**.

Stack: **Compose Multiplatform (Kotlin) + Kotlin Toolchain 0.12.0 (Amper)**,
Clean Architecture + MVVM + SSOT + SOLID/KISS. Runtime — **LiteRT-LM
(Google AI Edge, Maven: `litertlm-android` / `litertlm-jvm`)**, generation —
**Qwen2.5-1.5B-Instruct-Q8 (~1.5 GB)**, RAG embeddings —
**EmbeddingGemma-270M (~165 MB)**. Cloud path — **DeepSeek via Koog**.

## Layout

```
project.yaml            # shared, androidApp, desktopApp
gradle/libs.versions.toml  # version catalog: app version + all library versions
shared/                 # shared code: domain / data / llama / presentation / ui
  src/                  # common: ChatRepository (SSOT), PromptBuilder, ModelCatalog, VM, ChatScreen
  src@android/          # actuals: LiteRT-LM Engine/EmbeddingEngine, HttpURLConnection downloader
  src@jvm/              # actuals: LiteRT-LM (generation), fake embeddings (desktop RAG preview)
androidApp/             # product android/app, MainActivity (com.callowfellow)
desktopApp/             # product jvm/app, desktop preview
```

The model catalog is baked in (`ModelCatalog`): default `qwen25-1.5b-q8`, light
`qwen3-0.6b-int4`. Embeddings — `embedgemma-270m`. Everything is fetched from
`litert-community` on HuggingFace on first launch into `filesDir/models/`
(or `./models/` on desktop), then works fully offline. `INTERNET` is only
needed for the download.

The `task2.*` / `task3.*` packages inside `shared/` are historical heritage of
the challenge weeks (chat → RAG → presets). They were deliberately left as-is
during the port to avoid breaking imports; only the application itself was
renamed: `applicationId`/`namespace com.callowfellow`,
`android:label CallowFellow`, desktop window title `CallowFellow`.

## Run

```sh
./kotlin build              # verify all modules
./kotlin run -m desktopApp  # desktop preview of the same chat
./kotlin run -m androidApp   # install on device/emulator
```

Models download on first launch into `filesDir/models/` (default ~1.5 GB + ~165 MB
embeddings, with % progress), then everything works offline.
`INTERNET` is only needed for the download.

## Native runtime

Nothing to build manually: LiteRT-LM arrives as Maven dependencies
(`litertlm-android` / `litertlm-jvm`), native libraries are inside the AAR/jar.

## Versioning

Single source of truth — `gradle/libs.versions.toml`:

```toml
[versions]
app = "0.1.0"
app-code = "1"
```

`androidApp/module.yaml` (`versionName`/`versionCode`) and
`shared/src/task3/domain/AppVersion.kt` mirror these values literally
(Amper settings cannot reference `$libs.versions` dynamically):

```kotlin
object AppVersion {
    const val NAME = "CallowFellow"
    const val VERSION = "0.1.0"
    const val VERSION_CODE = 1
}
```

All library versions also live in the same catalog (`coroutines`,
`serialization`, `room`, `sqlite`, `material-icons`, `activity-compose`,
`koog-*`, `ktor`, `okio`, `litertlm`). The only exceptions are the toolchain
catalogs (`$compose.*`, `$kotlin.*`) — their versions come from the Kotlin
Toolchain (see `kotlin` wrapper, `kotlin_cli_version`), not from us.

On release, update in 3 places:
1. `gradle/libs.versions.toml` → `[versions].app` / `app-code`
2. `androidApp/module.yaml` → `versionName` / `versionCode`
3. `shared/src/task3/domain/AppVersion.kt`

The version is shown in the Settings screen footer (`SettingsTab`).

### Changelog

- **unreleased** — several chats: chat list in a M3 drawer (hamburger in the chat
  header) with create / switch / rename / delete; `chats` table + `messages.chatId`
  in `rag.db` (schema v2, migration keeps the old history as chat #1).
- **0.1.0** — port from `AI-Advent-Challenge/Week 5`:
  renamed to CallowFellow (`com.callowfellow`, label, window title),
  removed Task artifacts (`Task 1/`, `Task 5/`, `*.mp4`, `Task *.md`),
  added centralized versioning.

## Architecture

*   `presentation/ChatViewModel` — MVVM, only `UiState + Intent`.
*   `data/ChatRepositoryImpl` — SSOT (`StateFlow<List<ChatMessage>>`).
*   `llama/LlamaBridge` — DIP interface, `expect`/`actual` (JNI vs fake).
*   `di/AppContainer` — manual DI without Hilt (KISS, single screen).

# 05. Сборка и запуск

## Требования

* JDK — автоматически подтягивается toolchain'ом (настройка по умолчанию `25`),
  отдельно ставить не нужно.
* Android SDK, build-tools и образ системы — **ставит сам Amper**
  (задачи `installPlatformAndroid`, `installBuildToolsAndroid`, `installSystemImageAndroid`).
  Для эмулятора нужна лицензия SDK: `./kotlin task :androidApp:checkAndroidSdkLicenseAndroid`.
* Ничего глобального ставить не нужно: весь тулчейн качается в
  `~/Library/Caches/JetBrains/Kotlin` (macOS) или `~/.cache/JetBrains/Kotlin/cli` (Linux).
  При первом запуске будет сообщение о загрузке Kotlin Toolchain.

## Wrapper

Все команды — через [`./kotlin`](../kotlin) (Windows: `kotlin.bat`). Скрипт
скачивает дистрибутив `kotlin-cli-0.12.0.tgz`, **сверяет SHA-256** с зашитым
значением и передаёт аргументы в `bin/launcher.sh`.

Переменные окружения, которые wrapper понимает:

| Переменная | Назначение |
|---|---|
| `KOTLIN_CLI_DOWNLOAD_ROOT` | Maven-репозиторий для дистрибутива (по умолчанию `packages.jetbrains.team/maven/p/amper/amper`) |
| `KOTLIN_CLI_BOOTSTRAP_CACHE_DIR` | каталог кэша |
| `KOTLIN_CLI_JAVA_HOME` | JRE для запуска самого CLI (не влияет на компиляцию) |
| `KOTLIN_CLI_JAVA_OPTIONS` | JVM-опции для CLI |
| `KOTLIN_CLI_NO_WELCOME_BANNER` | отключить приветствие при первом запуске |

Проверить версию: `./kotlin -v` → `Kotlin Toolchain version 0.12.0`.

## Основные команды

```sh
./kotlin build                  # скомпилировать и слинковать все модули
./kotlin test                   # прогнать тесты
./kotlin check                  # выполнить доступные проверки (сейчас только tests)
./kotlin clean                  # удалить выходные каталоги и кэши проекта

./kotlin run -m desktopApp      # запуск десктопного приложения
./kotlin run -m androidApp      # сборка debug APK и запуск на эмуляторе/устройстве

./kotlin package                # собрать дистрибутивы модулей
./kotlin show modules           # список модулей
./kotlin show settings --all-modules   # эффективные настройки каждого модуля
./kotlin show dependencies --module shared   # разрешённый граф зависимостей
./kotlin show tasks             # все задачи и их зависимости
./kotlin show checks            # доступные проверки (lint, тесты)
./kotlin update                 # обновить Kotlin Toolchain
```

Осторожно: подкоманда — `show tasks`, а не `tasks`; `./kotlin tasks` вернёт ошибку.

## Сборка конкретных артефактов

```sh
./kotlin task :shared:compileJvm              # только common+jvm
./kotlin task :shared:jarJvm
./kotlin task :shared:aarAndroidDebug
./kotlin task :androidApp:buildAndroidDebug    # APK debug
./kotlin task :androidApp:compileAndroidRelease
./kotlin task :androidApp:testAndroidDebug     # тесты Android-варианта
./kotlin task :shared:testJvm                  # тесты JVM-варианта
./kotlin task :desktopApp:runJvm
./kotlin task :desktopApp:runExecutableJarJvm  # запуск из self-contained jar
./kotlin task :androidApp:bundleAndroid        # AAB
```

## Первый запуск: модели

Ни при первом запуске, ни потом приложение **не скачивает модель автоматически**
(для RAG-режима). Пользователь сам выбирает модель в «Настройках»:

| Что | Размер | Куда |
|---|---|---|
| `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | ~1.5 ГБ | `modelsDir/llm/` |
| `qwen3_0.6b_q4_block32_ekv1280.litertlm` | ~331 МБ | `modelsDir/llm/` |
| `embeddinggemma-2-text-270m.litertlm` | ~157 МБ | `modelsDir/embed/` |

`modelsDir` = `filesDir/models` на Android, `./models` относительно рабочего каталога на Desktop.
Интернет нужен только на время скачивания; после этого приложение работает офлайн.

Если нужно предварительно разложить веса вручную, достаточно положить файлы
в `modelsDir/llm` и `modelsDir/embed` под именами из `ModelCatalog` / `EmbedCatalog` —
приложение определит их по наличию файла на диске.

## Версионирование

Единый источник истины — `gradle/libs.versions.toml`:

```toml
[versions]
app = "0.1.0"
app-code = "1"
```

Значения **зеркалятся вручную** в двух местах, потому что Amper не умеет подставлять
`$libs.versions` в `settings`:

1. `androidApp/module.yaml` → `versionName` / `versionCode`;
2. `shared/src/task3/domain/AppVersion.kt`:

```kotlin
object AppVersion {
    const val NAME = "CallowFellow"
    const val VERSION = "0.1.0"
    const val VERSION_CODE = 1
    fun label(): String = "$NAME v$VERSION ($VERSION_CODE)"
}
```

`AppVersion.label()` выводится в подвале вкладки «Настройки».

### Порядок релиза

1. `gradle/libs.versions.toml` → `[versions].app` и `app-code`;
2. `androidApp/module.yaml` → `versionName` / `versionCode`;
3. `shared/src/task3/domain/AppVersion.kt` → `VERSION` / `VERSION_CODE`;
4. раздел «Changelog» в `README.md`.

Версии всех библиотек — в том же файле, в `[versions]`/`[libraries]`
(`coroutines`, `serialization`, `room`, `sqlite`, `material-icons`, `activity-compose`,
`koog-deepseek`, `koog-http-ktor`, `ktor`, `okio`, `litertlm`).
Исключения — каталоги `$compose.*` и `$kotlin.*`: их версии приходят из Kotlin Toolchain.

### Changelog

* **0.1.0** — перенос из `AI-Advent-Challenge/Week 5`: переименование в CallowFellow
  (`com.callowfellow`, label, заголовок окна), удаление артефактов задач
  (`Task 1/`, `Task 5/`, `*.mp4`, `Task *.md`), централизованное версионирование.

## Подпись релизов

Выключена (`android.signing.enabled: false` по умолчанию). Для релиза нужно
задать `android.signing.enabled: true` и положить `keystore.properties`
(в `.gitignore`) с путями к ключу. Имена keep-правил в
[`androidApp/proguard-rules.pro`](../androidApp/proguard-rules.pro) пока устарели —
см. [11. Ограничения](11-ogranicheniya.md).

## Полезные команды обслуживания

```sh
./kotlin clean                      # очистить сборку проекта
./kotlin clean-shared-caches        # очистить общий кэш JetBrains/Kotlin (перекачает тулчейн)
./kotlin show dependencies --module shared   # что реально подключено
./kotlin --log-level=debug build    # подробный лог
./kotlin --profile build            # профилирование через Async Profiler
./kotlin --coroutines-debug build   # дампы корутин при дедлоке
./kotlin compose-hot-reload-mcp-server       # MCP-сервер горячей перезагрузки Compose
```

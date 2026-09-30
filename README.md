# Arch AI — ИИ-ассистент для Archi

Плагин для [Archi](https://www.archimatetool.com/) 5.x. Добавляет в Archi чат с ИИ. Ассистент видит открытую модель ArchiMate и может её читать и изменять: создавать элементы, связи и представления, раскладывать схемы, сохранять модель.

С моделью ассистент работает через MCP-сервер [archi-mcp-server](https://github.com/slipeer/archi-mcp-server) (77 инструментов). Ассистентом может быть Claude Code по подписке или любая модель, доступная через API: Anthropic, OpenAI и OpenAI-совместимые сервисы, а также локальные модели.

*English: an AI chat panel for the Archi modelling tool. It reads and edits the open ArchiMate model through archi-mcp-server. Backends: Claude Code (subscription) or any Anthropic / OpenAI-compatible API, including local models (Ollama, LM Studio). The UI is in Russian.*

## Возможности

- **Панель «Arch AI»** (Ctrl+Alt+L) с тремя вкладками: «Чат», «Правила моделирования», «Подключение».
- **Контекст выделения.** Плагин передаёт ассистенту, что выделено в Archi: элементы, связи, представление. Отключается флажком «Добавлять выделение».
- **Синхронизация модели.** MCP-сервер работает с одной моделью. Плагин переключает его на ту, с которой вы работаете: при открытии панели, при смене выделения и перед каждым запросом.
- **Вложения.** К запросу можно приложить файлы (xlsx, docx, pdf, изображения и т. д.). Разбирает их сама модель.
- **Правила моделирования.** Этот текст отправляется перед каждым запросом. Встроенный шаблон содержит общие правила ArchiMate 3.1, его можно заменить своим.
- **Claude Code в терминале** (меню «Arch AI»). Открывает терминал в папке модели, где Claude Code уже подключён к MCP-серверу Archi.
- **Jira и Confluence.** Если запущен отдельный плагин `local.archi.atlassian` (MCP на `http://127.0.0.1:18091/mcp`), ассистент получает и его инструменты.

## Режимы подключения

### Claude Code (подписка)

Плагин запускает `claude -p` в режиме stream-json со своим MCP-конфигом (`--strict-mcp-config`). Ассистенту без подтверждения разрешены инструменты `mcp__archi__*`, а также `Read`, `Glob`, `Grep` и `Bash`. Диалог продолжается через `--resume`. Вложения передаются путями к файлам, модель читает их сама.

Путь к `claude` плагин ищет по порядку: переменная `CLAUDE_CODE_PATH`, затем `~/.local/bin/claude(.exe)`, затем `PATH`.

### API

Цикл работы с инструментами реализован в самом плагине, без внешних зависимостей. Готовые настройки есть для этих сервисов:

| Сервис | Протокол | Переменная для ключа |
|---|---|---|
| Anthropic (Claude API) | Anthropic Messages | `ANTHROPIC_API_KEY` |
| OpenAI | OpenAI Chat Completions | `OPENAI_API_KEY` |
| OpenRouter | OpenAI | `OPENROUTER_API_KEY` |
| DeepSeek | OpenAI | `DEEPSEEK_API_KEY` |
| Mistral | OpenAI | `MISTRAL_API_KEY` |
| Z.ai (GLM), в т. ч. GLM Coding Plan | OpenAI | `ZAI_API_KEY` |
| YandexGPT | OpenAI | `YANDEX_API_KEY` |
| Ollama, LM Studio (локально) | OpenAI | — |
| Любой OpenAI-совместимый | OpenAI | — |

Особенности:
- **Инструменты загружаются по требованию.** В системном промпте передаётся только каталог инструментов: имя и первая фраза описания, около 2,5 тыс. токенов. Полные схемы модель запрашивает сама инструментом `load_tools`. Первый запрос получается около 10 тыс. символов, а не 160 тыс.
- **Файлы в Anthropic** загружаются через Files API. Таблицы и документы разбираются в песочнице code execution, она оплачивается отдельно.
- **Ключ API** хранится в `settings.properties` в зашифрованном виде (AES-GCM). Ключ шифрования лежит в отдельном файле `.secret`. Если ключ не задан в плагине, берётся из переменной окружения сервиса.
- **TLS.** Плагин доверяет сертификатам JRE и хранилищу Windows (Windows-ROOT). Поэтому он работает за антивирусами и прокси, которые подменяют HTTPS-сертификат своим корневым.

## Требования

- Archi 5.x (проверено на 5.6), Windows.
- Плагин [archi-mcp-server](https://github.com/slipeer/archi-mcp-server/releases) с включённым автозапуском. По умолчанию он слушает `http://127.0.0.1:18090/mcp`.
- Для режима Claude Code — установленный [Claude Code](https://claude.com/claude-code) с активной подпиской.
- Для режима API — ключ выбранного сервиса или локальная модель.

## Установка

1. Установите archi-mcp-server и включите в его настройках автозапуск. Без автозапуска сервер запускается вручную: *MCP Server → Start MCP Server*.
2. Скачайте `ArchAI_<версия>.archiplugin` из [Releases](../../releases) или соберите сами (см. ниже).
3. В Archi: *Help → Manage Plugins → Install*, выберите файл и перезапустите Archi.
4. Откройте панель: меню *Arch AI → Панель Arch AI* или Ctrl+Alt+L.
5. На вкладке «Подключение» выберите режим и нажмите «Сохранить и проверить подключение».

> Если вы обновляетесь с версии, где плагин назывался `local.archi.claude`, удалите старый jar из `%APPDATA%\Archi\dropins`. Вкладку «Could not create the view: local.archi.claude.view» просто закройте.

## Файлы настроек

Все настройки лежат в `~/.archi-claude/`:

| Файл | Назначение |
|---|---|
| `settings.properties` | режим, сервис, адрес API, модель, адреса MCP-серверов, зашифрованные ключи |
| `.secret` | ключ шифрования для API-ключей |
| `modeling-rules.md` | правила моделирования (вкладка «Правила моделирования») |
| `mcp-archi.json` | MCP-конфиг для Claude Code, создаётся автоматически |

Основные параметры `settings.properties`:

```properties
mode=claude-code                 # или api
api.provider=anthropic           # id сервиса из таблицы выше
mcp.url=http://127.0.0.1:18090/mcp
mcp.atlassian.url=http://127.0.0.1:18091/mcp
```

## Сборка

Нужны JDK 21+ и установленный Archi, из которого берутся зависимости.

```powershell
.\build.ps1                                   # Archi в C:\Program Files\Archi, javac в PATH
.\build.ps1 -ArchiHome D:\Archi -JavaHome 'C:\Program Files\Java\jdk-21'
```

Результат появляется в корне проекта:
- `local.archi.ai_<версия>.jar` — бандл. Для быстрой проверки его можно положить в `%APPDATA%\Archi\dropins`.
- `ArchAI_<версия>.archiplugin` — дистрибутив для *Manage Plugins → Install*.

Версия берётся из `Bundle-Version` в `META-INF/MANIFEST.MF`.

## Устройство

```
src/local/archi/ai/
  ClaudeView.java          панель: вкладки «Чат», «Правила моделирования», «Подключение»
  ChatBackend.java         общий интерфейс режимов
  ClaudeProcess.java       режим Claude Code: процесс claude -p, stream-json
  ApiAgent.java            режим API: цикл tool use, Anthropic / OpenAI, load_tools, вложения
  McpClient.java           клиент MCP (streamable HTTP, JSON-RPC)
  McpTools.java            интерфейс набора инструментов MCP
  ModelSync.java           переключение активной модели MCP-сервера
  ArchiContext.java        описание выделения в Archi для промпта
  Attachment.java          вложенные файлы
  AiSettings.java          настройки и шифрование ключей
  ClaudeEnv.java           пути, системный промпт, правила по умолчанию, MCP-конфиг
  Tls.java                 доверенные сертификаты: JRE + Windows-ROOT
  Json.java                минимальный JSON без зависимостей
  OpenViewHandler.java     команда «Панель Arch AI»
  OpenTerminalHandler.java команда «Claude Code в терминале»
test/local/archi/ai/       ручные проверки (main-классы), см. ниже
```

Внешних библиотек нет: только JDK, Eclipse/SWT и API Archi.

### Проверки

Это не JUnit, а классы с методом `main`. Запускаются вручную при работающем Archi с MCP-сервером.

- `McpTest` — подключается к MCP-серверу, выводит число инструментов и вызывает `get-model-info`.
- `McpSize` — считает размер описаний и схем инструментов.
- `AgentLoopTest` — прогоняет цикл ApiAgent на фальшивом LLM-сервере с реальным MCP. Для Anthropic нужна переменная `ANTHROPIC_API_KEY` с любым значением. Чтобы не затронуть свои настройки, запускайте с `-Duser.home=<временная папка>`.
- `SmokeTest` — один запрос через Claude Code.

## Лицензия

[MIT](LICENSE)

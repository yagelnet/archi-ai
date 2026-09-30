# Arch AI — AI assistant for Archi

A plugin for [Archi](https://www.archimatetool.com/) 5.x that adds an AI chat panel. The assistant sees the open ArchiMate model and can read and change it: create elements, relationships and views, lay out diagrams, and save the model.

The assistant works with the model through the MCP server [archi-mcp-server](https://github.com/slipeer/archi-mcp-server) (77 tools). The assistant can be Claude Code on a subscription, or any model available through an API: Anthropic, OpenAI and OpenAI-compatible services, and local models.

> The plugin's user interface is in Russian. UI labels below are given in English with the original Russian in quotes.

## Features

- **Arch AI panel** (Ctrl+Alt+L) with three tabs: Chat («Чат»), Modeling rules («Правила моделирования»), Connection («Подключение»).
- **Selection context.** The plugin tells the assistant what is selected in Archi: elements, relationships, the view. Turn it off with the *Add selection* checkbox («Добавлять выделение»).
- **Model sync.** The MCP server works with one model at a time. The plugin switches it to the model you are working with: when the panel opens, when the selection changes, and before every request.
- **Attachments.** Files (xlsx, docx, pdf, images, etc.) can be attached to a request. The model reads them itself.
- **Modeling rules.** This text is sent before every request. The built-in template holds general ArchiMate 3.1 rules; replace it with your own.
- **Claude Code in a terminal** (menu *Arch AI*). Opens a terminal in the model's folder with Claude Code already connected to the Archi MCP server.
- **Jira and Confluence.** If the separate plugin `local.archi.atlassian` is running (MCP at `http://127.0.0.1:18091/mcp`), its tools are given to the assistant as well.

## Connection modes

### Claude Code (subscription)

The plugin runs `claude -p` in stream-json mode with its own MCP config (`--strict-mcp-config`). The assistant may use `mcp__archi__*`, `Read`, `Glob`, `Grep` and `Bash` without asking. The conversation continues via `--resume`. Attachments are passed as file paths; the model reads them itself.

The `claude` executable is looked up in this order: the `CLAUDE_CODE_PATH` variable, then `~/.local/bin/claude(.exe)`, then `PATH`.

### API

The tool-use loop is implemented in the plugin itself, with no external dependencies. Presets are included for these services:

| Service | Protocol | Key variable |
|---|---|---|
| Anthropic (Claude API) | Anthropic Messages | `ANTHROPIC_API_KEY` |
| OpenAI | OpenAI Chat Completions | `OPENAI_API_KEY` |
| OpenRouter | OpenAI | `OPENROUTER_API_KEY` |
| DeepSeek | OpenAI | `DEEPSEEK_API_KEY` |
| Mistral | OpenAI | `MISTRAL_API_KEY` |
| Z.ai (GLM), incl. GLM Coding Plan | OpenAI | `ZAI_API_KEY` |
| YandexGPT | OpenAI | `YANDEX_API_KEY` |
| Ollama, LM Studio (local) | OpenAI | — |
| Any OpenAI-compatible service | OpenAI | — |

Details:
- **Tools are loaded on demand.** The system prompt carries only a tool catalogue: each tool's name and the first sentence of its description, about 2.5k tokens. The model requests full schemas itself with the `load_tools` tool. The first request is about 10k characters instead of 160k.
- **Files with Anthropic** are uploaded via the Files API. Spreadsheets and documents are processed in the code execution sandbox, which is billed separately.
- **The API key** is stored in `settings.properties`, encrypted with AES-GCM. The encryption key is kept in a separate `.secret` file. If no key is set in the plugin, the service's environment variable is used.
- **TLS.** The plugin trusts both the JRE certificates and the Windows store (Windows-ROOT), so it works behind antivirus software and proxies that re-sign HTTPS with their own root certificate.

## Requirements

- Archi 5.x (tested on 5.6), Windows.
- The [archi-mcp-server](https://github.com/slipeer/archi-mcp-server/releases) plugin with auto-start enabled. By default it listens on `http://127.0.0.1:18090/mcp`.
- For Claude Code mode: [Claude Code](https://claude.com/claude-code) installed, with an active subscription.
- For API mode: a key for the chosen service, or a local model.

## Installation

1. Install archi-mcp-server and enable auto-start in its settings. Without auto-start, start it manually: *MCP Server → Start MCP Server*.
2. Download `ArchAI_<version>.archiplugin` from [Releases](../../releases) or build it yourself (see below).
3. In Archi: *Help → Manage Plugins → Install*, choose the file, restart Archi.
4. Open the panel: menu *Arch AI → Arch AI panel* («Панель Arch AI») or Ctrl+Alt+L.
5. On the *Connection* tab, pick a mode and click *Save and test connection* («Сохранить и проверить подключение»).

> Upgrading from a version where the plugin was called `local.archi.claude`: delete the old jar from `%APPDATA%\Archi\dropins`. If a tab says "Could not create the view: local.archi.claude.view", just close it.

## Settings files

All settings live in `~/.archi-claude/`:

| File | Purpose |
|---|---|
| `settings.properties` | mode, service, API endpoint, model, MCP server URLs, encrypted keys |
| `.secret` | encryption key for API keys |
| `modeling-rules.md` | modeling rules (the *Modeling rules* tab) |
| `mcp-archi.json` | MCP config for Claude Code, generated automatically |

Main `settings.properties` keys:

```properties
mode=claude-code                 # or api
api.provider=anthropic           # service id from the table above
mcp.url=http://127.0.0.1:18090/mcp
mcp.atlassian.url=http://127.0.0.1:18091/mcp
```

## Building

Requires JDK 21+ and an installed Archi, which provides the dependencies.

```powershell
.\build.ps1                                   # Archi in C:\Program Files\Archi, javac on PATH
.\build.ps1 -ArchiHome D:\Archi -JavaHome 'C:\Program Files\Java\jdk-21'
```

Output in the project root:
- `local.archi.ai_<version>.jar` — the bundle. For a quick try, drop it into `%APPDATA%\Archi\dropins`.
- `ArchAI_<version>.archiplugin` — the package for *Manage Plugins → Install*.

The version comes from `Bundle-Version` in `META-INF/MANIFEST.MF`.

## Code layout

```
src/local/archi/ai/
  ClaudeView.java          panel: Chat, Modeling rules, Connection tabs
  ChatBackend.java         common interface of the two modes
  ClaudeProcess.java       Claude Code mode: claude -p process, stream-json
  ApiAgent.java            API mode: tool-use loop, Anthropic / OpenAI, load_tools, attachments
  McpClient.java           MCP client (streamable HTTP, JSON-RPC)
  McpTools.java            interface of an MCP tool set
  ModelSync.java           switches the MCP server's active model
  ArchiContext.java        describes the Archi selection for the prompt
  Attachment.java          attached files
  AiSettings.java          settings and key encryption
  ClaudeEnv.java           paths, system prompt, default rules, MCP config
  Tls.java                 trusted certificates: JRE + Windows-ROOT
  Json.java                minimal dependency-free JSON
  OpenViewHandler.java     "Arch AI panel" command
  OpenTerminalHandler.java "Claude Code in terminal" command
test/local/archi/ai/       manual checks (main classes), see below
```

No external libraries: only the JDK, Eclipse/SWT and the Archi API.

### Checks

These are not JUnit tests but classes with a `main` method. Run them by hand while Archi and its MCP server are running.

- `McpTest` — connects to the MCP server, prints the tool count and calls `get-model-info`.
- `McpSize` — measures the size of tool descriptions and schemas.
- `AgentLoopTest` — runs the ApiAgent loop against a fake LLM server with the real MCP server. For Anthropic, set `ANTHROPIC_API_KEY` to any value. Run with `-Duser.home=<temp folder>` so your own settings are not touched.
- `SmokeTest` — one request through Claude Code.

## License

[MIT](LICENSE)

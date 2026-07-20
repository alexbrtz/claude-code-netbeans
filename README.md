# Claude Code NetBeans Plugin

A NetBeans IDE plugin that integrates Claude Code through the Model Context Protocol (MCP) over WebSocket.

## Features

- **Automatic detection** — creates a lock file at `~/.claude/ide/{port}.lock` that Claude Code CLI discovers automatically
- **10 MCP tools** — file operations, editor access, diagnostics, interactive diff viewer
- **Real-time selection events** — notifies Claude Code when the editor selection changes
- **Auto-start** — starts the WebSocket server when NetBeans launches; no manual setup required
- **Multi-client** — supports more than one simultaneously connected Claude Code session
- **Status panel** — a dockable window (Window → Claude Code Status Panel) showing server state, port, PID, lock file validity, and connected client count, with a Restart Server button

## Requirements

- Apache NetBeans 23.0 (RELEASE190) or later
- Java 17 or later

## Installation

### Option A — Download pre-built NBM

Download the latest `.nbm` from the [releases page](https://github.com/alexbrtz/claude-code-netbeans/releases) and install via **Tools → Plugins → Downloaded → Add Plugins**.

### Option B — Build from source

```bash
git clone https://github.com/alexbrtz/claude-code-netbeans.git
cd claude-code-netbeans
mvn clean package -DskipTests
```

The plugin is built at `target/nbm/claude-code-netbeans-1.4.0.nbm`.

> **Note:** The first build requires internet access to download `nbm-maven-plugin` from the NetBeans OSU repository.

> **Note:** Build with **JDK 17** (`JAVA_HOME` pointed at a JDK 17 install). NetBeans's own
> annotation processors (`@ServiceProvider`, `@ActionID`/`@ActionReference`,
> `@TopComponent.Registration`) silently produce no output — no `layer.xml`, no
> `META-INF/services/*` — when compiled with much newer JDKs (observed with JDK 26), which means
> the Tools/Window menu entries and the status bar element won't be registered even though the
> build reports `BUILD SUCCESS`. If menu items or the status panel don't appear after installing
> the `.nbm`, rebuild with `JAVA_HOME` set to a JDK 17 install and check again.

Install the `.nbm` in NetBeans: **Tools → Plugins → Downloaded → Add Plugins**, then restart.

## Usage

1. Open NetBeans with your project
2. In any terminal, run `claude`
3. Run `/ide` to verify the connection

Claude Code will automatically detect the running NetBeans instance via the lock file.

## Available Tools

| Tool | Description |
|------|-------------|
| `openFile` | Open a file in the NetBeans editor |
| `getWorkspaceFolders` | List open projects (name + path) |
| `getOpenEditors` | List all open editor tabs |
| `getCurrentSelection` | Get selected text and cursor position |
| `getDiagnostics` | Get errors and warnings from a file (or all open files) — javac errors and IDE inspections |
| `saveDocument` | Save a file to disk |
| `checkDocumentDirty` | Check whether a file has unsaved changes |
| `close_tab` | Close an editor tab by name |
| `closeAllDiffTabs` | Close all open diff tabs |
| `openDiff` | Open an interactive diff viewer; resolves when user approves or rejects |

## Architecture

```
Claude CLI
    ↓ WebSocket (port 8990–9100)
WebSocketMCPServer (Jetty 11)
    ↓
NetBeansMCPHandler  ←→  NetBeans IDE APIs
    ↓
tools/ (one class per tool)
```

**Discovery:** on startup the plugin writes `~/.claude/ide/{port}.lock` with connection info (pid, port, authToken, workspaceFolders). Claude CLI scans that directory to find running IDE instances.

**Lock file format:**
```json
{
  "pid": 12345,
  "ideName": "NetBeans",
  "transport": "ws",
  "authToken": "<uuid>",
  "port": 8991,
  "workspaceFolders": ["/path/to/project"]
}
```

## Troubleshooting

**Plugin not loading** — check **View → IDE Log** for errors on startup.

**Claude Code not connecting** — verify the lock file exists: `~/.claude/ide/`. Check that the port range 8990–9100 is not blocked by a firewall.

**`getDiagnostics` returns empty** — IDE inspections are only available for files currently open in the editor that NetBeans has already analyzed (squiggles visible). javac diagnostics work for any file in an open project.

## Development

See [CLAUDE.md](CLAUDE.md) for architecture details, known bugs, and patterns for adding new tools.

```bash
# Run plugin in a development NetBeans instance
mvn clean install nbm:run-ide
```

## Contributing

Contributions welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) if it exists, otherwise open a PR directly.

## License

ISC License — see [LICENSE](LICENSE).

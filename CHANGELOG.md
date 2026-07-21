# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [1.5.0] - 2026-07-21

### Added
- **Per-client context scoping via MCP `roots/list`** — after `initialize`, the server now sends
  a server-initiated `roots/list` request to any client that declared `capabilities.roots`
  (Claude Code CLI does), resolves the returned workspace root folder(s), and stores them per
  session. `selection_changed` notifications are now only broadcast to clients whose declared
  roots include the changed file — a client running in project X no longer gets notified about
  selection changes in an unrelated project Y. Clients that don't support/respond to `roots/list`
  keep receiving everything as before (safe default, no regression).
- `MCPResponseBuilder.createRequest(...)` — first server-initiated JSON-RPC request builder;
  previously the server only ever answered requests or sent notifications.
- `NbUtils.isPathWithinRoots(...)` — root-membership check reused from the existing
  `isPathWithinOpenProjects` pattern.
- Documented `roots/list` (request + response shape) in `mcp-protocol-schema.json` and
  `mcp-protocol-schema-detailed.json`, which previously only stubbed the `capabilities.roots` flag.
- Rewrote the tool descriptions returned by `tools/list` to be more specific about scope and
  behavior for the calling LLM — notably `getDiagnostics` (clarifies that omitting `uri` returns
  diagnostics for every open file across *all* open projects, not just the caller's) and `openDiff`
  (no longer describes itself as a "git diff", since it isn't git-based).

### Changed
- `prompts/list` no longer advertises the `code_review` prompt — it had no backing `prompts/get`
  implementation, so invoking it would have failed with "Method not found". Left commented out
  in `NetBeansMCPHandler.handlePromptsList()` in case it's implemented for real later.

### Fixed
- `NetBeansMCPHandler.handleMessage` crashed with an NPE (silently turned into a bogus JSON-RPC
  error sent back to the client) whenever it received a message with no `"method"` field — which
  is exactly what a response to a server-initiated request looks like. Responses without a
  `method` are now routed to the matching pending request's callback instead.

### Known Issues (remaining)
- `resources/read` implementation marked as incorrect internally (XXX comment)
- `getWorkspaceFolders`/`resources/list` are not yet scoped per client (only `selection_changed`
  is, for now) — those tools don't currently receive the requesting `Session` at all.
- No `notifications/roots/list_changed` support — roots are requested once after `initialize`
  and never refreshed if the client's workspace changes later.

## [1.4.0] - 2026-07-20

### Added
- **Claude Code Status panel** (`ui/ClaudeCodeStatusTopComponent`) — a dockable window showing
  server running state, port, PID, lock file validity, and number of connected clients, with a
  **Restart Server** button. Open it from **Window → Claude Code Status Panel** or by clicking
  the "Claude ✓/~/✗" label in the status bar.
- `ClaudeCodeStatusService`: new `restartServer()`, `getServerPid()`, and
  `getConnectedClientCount()` methods backing the new panel.
- **Multi-client support** — the WebSocket server now correctly handles more than one
  simultaneously connected Claude Code session. Previously a second connection silently replaced
  the first internally (`NetBeansMCPHandler` held a single shared `Session`), which orphaned the
  first client's socket, misrouted async tool responses (e.g. `openDiff`) and selection-change
  notifications to whichever session connected most recently, and leaked duplicate editor
  listeners on every reconnect. Sessions are now tracked in a registry; async responses are routed
  back to the session that made the original request, and selection-change notifications are
  broadcast to all connected clients. Editor/diff-tab tracking now starts once on the first
  connection and stops once on the last disconnection, instead of on every individual connect/close.

### Known Issues
- `resources/read` implementation marked as incorrect internally (XXX comment)
- All connected clients currently see the same workspace/selection context regardless of which
  project folder they were started in — planned next step is to send a `roots/list` request to
  each client after `initialize` (already advertised via `capabilities.roots` in the Claude Code
  CLI handshake) and use the returned workspace root(s) to scope per-client notifications and
  responses instead of broadcasting/returning everything to everyone.

## [1.3.0] - 2026-07-17

### Fixed
- **`getDiagnostics` complete rewrite** — now returns results from two sources:
  - **IDE inspections** (`source: netbeans-hints`): reads `AnnotationHolder.layer2Errors`
    via reflection, keyed by `DataObject` in the static `file2Holder` map. Works for
    any open file that NetBeans has already analyzed (squiggles visible in editor).
  - **javac compiler diagnostics** (`source: java-compiler`): uses `JavaSource.runUserActionTask`
    with `CompilationController.getDiagnostics()`. Methods are loaded from the controller's
    classloader to avoid the classloader mismatch between NetBeans' bundled `javax.tools`
    and the JDK's `java.compiler` module.
- **`notifications/cancelled`** — cancelled async requests (e.g. `openDiff`) are now silently
  discarded instead of logging WARNING and leaving the request in an indeterminate state.
- **`notifications/initialized` and `ide_connected`** — unknown-method WARNINGs silenced.
- **EDT threading violations** — `Window System API must be called from AWT thread` errors
  fixed in `CloseAllDiffTabs`, `CloseTab`, `CheckDocumentDirty`, `GetOpenEditors`, and
  `startSelectionTracking` by wrapping calls in `SwingUtilities.invokeAndWait`.
- **`MCPResponseBuilder`** — `createToolResponse(Object)` was returning a bare `TextNode`
  for String values instead of the MCP `{content:[{type:"text",text:"..."}]}` wrapper,
  causing Claude Code to hang waiting for a valid tool response.
- **WebSocket logging** — `WS recv` / `WS send` promoted to `INFO` so tool call traffic is
  visible in the IDE log without changing the log level.

### Added
- `pom.xml`: `org-netbeans-modules-java-source`, `org-netbeans-modules-java-source-base`,
  and `org-netbeans-spi-editor-hints` direct dependencies for `getDiagnostics`.
- `CLAUDE.md`: architecture reference for Claude Code sessions.

## [1.2.0] - 2025

### Added
- `getDiagnostics` tool: returns errors and warnings from NetBeans annotations
- `checkDocumentDirty` tool: checks for unsaved changes in a document
- `saveDocument` tool: saves a file to disk
- `closeAllDiffTabs` tool: closes all open diff tabs
- `openDiff` tool (async): opens NetBeans diff viewer with approve/reject flow
- `close_tab` tool: closes an editor tab by name
- JSON schema-driven parameter/result POJOs via `jsonschema2pojo`
- `authToken` field in lock file for Claude CLI authentication

### Changed
- Upgraded to NetBeans RELEASE190 (23.0)
- Java target updated to 17
- Jetty upgraded to 11.0.20
- Jackson upgraded to 2.16.2

## [1.0.0] - Initial release

### Added
- MCP server over WebSocket (ports 8990–9100)
- Lock file discovery at `~/.claude/ide/{port}.lock`
- `initialize`, `tools/list`, `tools/call`, `resources/list`, `prompts/list` MCP methods
- `openFile` tool
- `getWorkspaceFolders` tool
- `getOpenEditors` tool
- `getCurrentSelection` tool
- Auto-start on NetBeans launch via `ClaudeCodeInstaller`
- Lock file refresh when projects are opened/closed

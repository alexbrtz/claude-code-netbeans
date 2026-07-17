# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Known Issues
- `resources/read` implementation marked as incorrect internally (XXX comment)

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

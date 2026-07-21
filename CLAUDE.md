# claude-code-netbeans — Instrucciones para Claude Code

## Qué es este proyecto

Plugin NBM para Apache NetBeans que implementa un servidor MCP (Model Context Protocol) sobre WebSocket. Permite que Claude Code CLI se conecte al IDE y acceda al contexto del editor: archivos abiertos, selección actual, diagnósticos, diff interactivo, etc.

## Stack técnico

- **Java 17** · **NBM packaging** (módulo NetBeans)
- **Jetty 11** — servidor WebSocket embebido (puertos 8990–9100)
- **Jackson 2.16** — serialización JSON
- **jsonschema2pojo** — genera POJOs desde schemas JSON en `tools/schemas/`
- **nbm-maven-plugin 4.8** — build del módulo (requiere `pluginRepositories` apuntando a `netbeans.osuosl.org`)

## Arquitectura en una línea

```
Claude CLI → WebSocket → MCPWebSocketHandler → NetBeansMCPHandler → Tool → NetBeans APIs
```

## Archivos clave

| Archivo | Rol |
|---------|-----|
| `ClaudeCodeInstaller.java` | Lifecycle del plugin: inicia/reinicia servidor, crea lockfile, escucha proyectos. Implementa `ClaudeCodeStatusService` |
| `ClaudeCodeStatusService.java` | Interfaz en Lookup global: estado del servidor, `restartServer()`, `getConnectedClientCount()` |
| `ClaudeCodeStatusLineElement.java` | Label "Claude ✓/~/✗" en la barra de estado; click abre el panel de status |
| `ClaudeCodeAction.java` | Ítem "Claude Code Status" en menú Tools (diálogo modal de solo lectura) |
| `ui/ClaudeCodeStatusTopComponent.java` | Panel acoplable (Window → Claude Code Status Panel): server/port/PID/lockfile/clientes conectados + botón Restart |
| `LockFileManager.java` | Escribe `~/.claude/ide/{port}.lock` — así Claude CLI descubre el IDE |
| `WebSocketMCPServer.java` | Servidor Jetty; busca puerto libre en 8990–9100 |
| `NetBeansMCPHandler.java` | Switch principal de métodos MCP; registra y despacha las 10 tools; mantiene el registro de sesiones (`Map<Session, ClientInfo>`) para soporte multi-cliente |
| `MCPWebSocketHandler.java` | Adapter WebSocket (una instancia por conexión) → llama `NetBeansMCPHandler.handleMessage(mensaje, session)` |
| `MCPResponseBuilder.java` | Construye respuestas JSON-RPC 2.0 |
| `tools/` | Una clase por tool; implementan interfaz `Tool<Params, Result>` |
| `tools/schemas/*.json` | Schemas de parámetros y resultados; generan POJOs en `target/` |

## Estructura del proyecto

```
claude-code-netbeans/
├── pom.xml                          # build nbm; requiere pluginRepositories (ver abajo)
├── nb-configuration.xml
├── CLAUDE.md · CHANGELOG.md · README.md · LICENSE
├── mcp-protocol-schema.json / mcp-protocol-schema-detailed.json   # referencia del protocolo MCP
├── .github/
└── src/
    ├── main/
    │   ├── java/org/openbeans/claude/netbeans/
    │   │   ├── ClaudeCodeAction.java              # menú Tools → diálogo de status
    │   │   ├── ClaudeCodeInstaller.java           # ModuleInstall: start/stop/restart, lockfile
    │   │   ├── ClaudeCodeStatusLineElement.java   # label en la barra de estado
    │   │   ├── ClaudeCodeStatusService.java       # interfaz de status en Lookup global
    │   │   ├── EditorUtils.java
    │   │   ├── LockFileManager.java                # ~/.claude/ide/{port}.lock
    │   │   ├── MCPResponseBuilder.java             # respuestas JSON-RPC 2.0
    │   │   ├── MCPWebSocketHandler.java            # un WebSocketAdapter por conexión
    │   │   ├── NbUtils.java
    │   │   ├── NetBeansMCPHandler.java             # switch de métodos MCP + registro de sesiones
    │   │   ├── WebSocketMCPServer.java              # servidor Jetty, escaneo de puerto 8990–9100
    │   │   ├── ui/
    │   │   │   └── ClaudeCodeStatusTopComponent.java   # panel Window → Claude Code Status Panel
    │   │   └── tools/                               # una clase por tool MCP
    │   │       ├── AsyncHandler.java · AsyncResponse.java · Tool.java   # contrato tools sync/async
    │   │       ├── CheckDocumentDirty.java · CloseAllDiffTabs.java · CloseTab.java
    │   │       ├── DiffTabTracker.java              # registro global tabName → AsyncHandler (openDiff)
    │   │       ├── GetCurrentSelection.java · GetDiagnostics.java · GetOpenEditors.java · GetWorkspaceFolders.java
    │   │       ├── OpenDiff.java · OpenFile.java · SaveDocument.java
    │   │       └── params/                          # POJOs de parámetros (jsonschema2pojo genera el resto en target/)
    │   ├── resources/
    │   │   ├── META-INF/services/org.openide.modules.ModuleInstall   # registra ClaudeCodeInstaller (a mano, no por anotación)
    │   │   ├── nbm/manifest.mf
    │   │   └── org/openbeans/claude/netbeans/
    │   │       ├── Bundle.properties               # claves i18n del paquete raíz (ClaudeCodeAction, etc.)
    │   │       ├── ui/Bundle.properties             # claves i18n del paquete ui/ (ClaudeCodeStatusTopComponent) — ¡bundle distinto por paquete!
    │   │       └── tools/schemas/*.json             # JSON Schema de cada tool → POJOs generados
    │   └── (no hay layer.xml ni .form: todo el registro de UI es por anotación — @ServiceProvider, @ActionID/@ActionReference, @TopComponent.Registration)
    └── test/java/org/openbeans/claude/netbeans/tools/
        ├── CloseAllDiffTabsTest.java
        └── CloseTabTest.java
```

## Métodos MCP implementados

| Método | Estado |
|--------|--------|
| `initialize` | ✅ |
| `tools/list` | ✅ |
| `tools/call` | ✅ (sync y async) |
| `resources/list` | ✅ |
| `resources/read` | ⚠️ Implementación dudosa (comentario XXX, ver `handleResourcesRead` en `NetBeansMCPHandler.java`) |
| `prompts/list` | ✅ básico (hardcodeado) |
| `notifications/cancelled` | ✅ Implementado (`handleCancelled` marca el requestId en `cancelledRequests`; la respuesta async se descarta si llega después) |

## Las 10 tools registradas

| Tool | Tipo | Descripción |
|------|------|-------------|
| `openFile` | sync | Abre archivo en editor; valida que esté dentro de proyectos abiertos |
| `getWorkspaceFolders` | sync | Lista proyectos abiertos (name + uri) |
| `getOpenEditors` | sync | Lista tabs abiertos con metadata |
| `getCurrentSelection` | sync | Texto seleccionado + posición en editor activo |
| `close_tab` | sync | Cierra tab por nombre |
| `getDiagnostics` | sync | Errores/warnings, dos fuentes: inspecciones IDE (Annotations API) + javac (`JavaSource.runUserActionTask`) |
| `checkDocumentDirty` | sync | Verifica cambios sin guardar en un archivo |
| `saveDocument` | sync | Guarda archivo a disco |
| `closeAllDiffTabs` | sync | Cierra todos los tabs de diff |
| `openDiff` | **async** | Abre visor de diff con botón "Approve"; responde cuando usuario acepta/rechaza |

## Agregar una tool nueva — patrón obligatorio

1. Crear schema JSON en `tools/schemas/{NombreTool}Params.json` y `{NombreTool}Result.json`
2. Crear clase en `tools/{NombreTool}.java` implementando `Tool<{NombreTool}Params, {NombreTool}Result>`
3. Registrar en `NetBeansMCPHandler` en el método `handleToolsList()` y en el switch de `handleToolCall()`
4. Para tools asíncronas: implementar `Tool<Params, AsyncResponse<Result>>` y usar `DiffTabTracker` como referencia

## Bugs conocidos y pendientes

> Nota: esta sección tenía entradas desactualizadas (arregladas en el release 1.3.0, commit `cbf09c1`, pero nunca quitadas de acá). Se corrigieron el 2026-07-20 — ver CHANGELOG.md para el detalle de qué se arregló y cuándo.

### `resources/read` incompleto
Comentario `XXX: This is probably doing the wrong thing` en `handleResourcesRead` de `NetBeansMCPHandler.java`. `getProjectInfo` es un stub — devuelve solo path/nombre, no enumera ni lee archivos reales. Sigue pendiente.

### Contexto parcialmente aislado por cliente (falta scoping en las tools "pull")
Desde 1.5.0, `selection_changed` ya se filtra por cliente: al `initialize`, el servidor le pide `roots/list` a cada cliente que declara `capabilities.roots` (Claude Code CLI lo hace) y solo le hace broadcast del evento si el archivo cae dentro de sus roots (`NetBeansMCPHandler.broadcastToClientsWithRoot`). Lo que sigue sin aislar: las tools que el cliente pide explícitamente sin filtro — `getWorkspaceFolders`, `getOpenEditors`, y `getDiagnostics` sin `uri` — devuelven todo el estado del IDE (todos los proyectos/tabs abiertos), sin importar la raíz de quien pregunta, porque esas tools ni siquiera reciben la `Session` hoy. Ver CHANGELOG "Known Issues" de 1.5.0.

### Build con JDK muy nuevo rompe el registro de anotaciones
Si se compila con un JDK considerablemente más nuevo que el que usa NetBeans en runtime (confirmado con JDK 26), el procesador de anotaciones de NetBeans (`@ServiceProvider`, `@ActionID`/`@ActionReference`, `@TopComponent.Registration`) no genera nada — ni `layer.xml` ni `META-INF/services/*` — sin ningún error ni warning visible; el build igual reporta `BUILD SUCCESS`, pero los ítems de menú y el panel de status simplemente no aparecen al instalar el `.nbm`. Mitigación: compilar con **JDK 17** (ver README, sección de build). No se investigó la causa raíz exacta (compatibilidad del procesador viejo con el nuevo compilador), solo se confirmó el workaround.

### Lockfile sin `authToken` — no reproducido en el código actual
`LockFileManager.createLockFile()` escribe `authToken` incondicionalmente (`UUID.randomUUID()`) en el código tal como está hoy — no se encontró ningún camino donde se omita. Si reaparece, revisar si `updateLockFile()` corrió sobre un lockfile parcialmente escrito por una versión vieja del plugin (esa función solo actualiza `workspaceFolders`, preserva el resto del JSON tal cual estaba).

## Build

```bash
# Requiere internet para descargar nbm-maven-plugin la primera vez
# Requiere JAVA_HOME apuntando a JDK 17 (ver bug "Build con JDK muy nuevo" más abajo)
mvn clean package -DskipTests

# Output
target/nbm/claude-code-netbeans-1.5.0.nbm
```

### Requisito de build — JDK 17
Compilar con `JAVA_HOME` en un JDK 17. Con JDKs bastante más nuevos (confirmado con JDK 26) el procesador de anotaciones de NetBeans deja de generar `layer.xml`/`META-INF/services/*` en silencio — ver detalle en "Bugs conocidos y pendientes".

### Requisito de build — pluginRepositories
El `pom.xml` debe tener `<pluginRepositories>` apuntando a `https://netbeans.osuosl.org/content/repositories/netbeans/` para que Maven encuentre el `nbm-maven-plugin`. Si falta, Maven busca solo en Central y falla con "Unknown packaging: nbm".

## Instalar en NetBeans para pruebas

1. Build: `mvn clean package -DskipTests`
2. NetBeans → Tools → Plugins → Downloaded → Add Plugins → seleccionar el `.nbm`
3. Reiniciar NetBeans
4. Verificar en View → IDE Log que aparezca `MCP server started on port XXXX`
5. Conectar Claude Code con `/ide`

## Proyecto de referencia

`openDiff` en `tools/OpenDiff.java` es el ejemplo más completo del patrón async. Usarlo como referencia para cualquier tool que requiera interacción del usuario.

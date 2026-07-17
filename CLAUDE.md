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
| `ClaudeCodeInstaller.java` | Lifecycle del plugin: inicia servidor, crea lockfile, escucha proyectos |
| `LockFileManager.java` | Escribe `~/.claude/ide/{port}.lock` — así Claude CLI descubre el IDE |
| `WebSocketMCPServer.java` | Servidor Jetty; busca puerto libre en 8990–9100 |
| `NetBeansMCPHandler.java` | Switch principal de métodos MCP; registra y despacha las 10 tools |
| `MCPWebSocketHandler.java` | Adapter WebSocket → llama `NetBeansMCPHandler.handleMessage()` |
| `MCPResponseBuilder.java` | Construye respuestas JSON-RPC 2.0 |
| `tools/` | Una clase por tool; implementan interfaz `Tool<Params, Result>` |
| `tools/schemas/*.json` | Schemas de parámetros y resultados; generan POJOs en `target/` |

## Métodos MCP implementados

| Método | Estado |
|--------|--------|
| `initialize` | ✅ |
| `tools/list` | ✅ |
| `tools/call` | ✅ (sync y async) |
| `resources/list` | ✅ |
| `resources/read` | ⚠️ Implementación dudosa (comentario XXX en línea 406) |
| `prompts/list` | ✅ básico (hardcodeado) |
| `notifications/cancelled` | ❌ No implementado — causa que el IDE loguee WARNING y los requests queden colgados al cancelar |

## Las 10 tools registradas

| Tool | Tipo | Descripción |
|------|------|-------------|
| `openFile` | sync | Abre archivo en editor; valida que esté dentro de proyectos abiertos |
| `getWorkspaceFolders` | sync | Lista proyectos abiertos (name + uri) |
| `getOpenEditors` | sync | Lista tabs abiertos con metadata |
| `getCurrentSelection` | sync | Texto seleccionado + posición en editor activo |
| `close_tab` | sync | Cierra tab por nombre |
| `getDiagnostics` | sync | Errores/warnings via Annotations API — **puede colgarse** (ver bugs) |
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

### `getDiagnostics` se cuelga
`GetDiagnostics.java` accede a la Annotations API de NetBeans desde el thread WebSocket. Si NetBeans está indexando o compilando en background puede deadlock. La fix es envolver la llamada en `SwingUtilities.invokeAndWait()` con timeout.

### `notifications/cancelled` no implementado
Cuando Claude Code cancela una request en vuelo, envía `notifications/cancelled`. El plugin no maneja este método → loguea `WARNING: Unknown MCP method: notifications/cancelled` y el request queda en estado indeterminado. Fix: agregar el case en el switch de `NetBeansMCPHandler.handleMessage()` que limpie el request pendiente.

### `resources/read` incompleto
Comentario `XXX: This is probably doing the wrong thing` en línea 406 de `NetBeansMCPHandler.java`.

### Lockfile sin `authToken`
El lock file generado a veces omite el campo `authToken` dependiendo de la versión. Claude CLI lo requiere para conectar.

## Build

```bash
# Requiere internet para descargar nbm-maven-plugin la primera vez
mvn clean package -DskipTests

# Output
target/claude-code-netbeans-1.2.0.nbm
```

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

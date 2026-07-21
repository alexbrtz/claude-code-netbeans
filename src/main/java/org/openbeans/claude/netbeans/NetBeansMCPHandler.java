package org.openbeans.claude.netbeans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.websocket.api.Session;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.windows.TopComponent;

import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import org.openide.text.NbDocument;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import org.netbeans.api.project.ui.OpenProjects;
import java.io.IOException;
import org.openbeans.claude.netbeans.tools.AsyncHandler;
import org.openbeans.claude.netbeans.tools.AsyncResponse;
import org.openbeans.claude.netbeans.tools.CheckDocumentDirty;
import org.openbeans.claude.netbeans.tools.CloseAllDiffTabs;
import org.openbeans.claude.netbeans.tools.CloseTab;
import org.openbeans.claude.netbeans.tools.DiffTabTracker;
import org.openbeans.claude.netbeans.tools.GetCurrentSelection;
import org.openbeans.claude.netbeans.tools.GetDiagnostics;
import org.openbeans.claude.netbeans.tools.GetOpenEditors;
import org.openbeans.claude.netbeans.tools.GetWorkspaceFolders;
import org.openbeans.claude.netbeans.tools.OpenDiff;
import org.openbeans.claude.netbeans.tools.OpenFile;
import org.openbeans.claude.netbeans.tools.SaveDocument;
import org.openbeans.claude.netbeans.tools.params.Content;
import org.openbeans.claude.netbeans.tools.params.OpenDiffResult;

/**
 * Handles Model Context Protocol messages and provides NetBeans IDE capabilities
 * to Claude Code through MCP primitives (Tools, Resources, Prompts).
 */
public class NetBeansMCPHandler {

    
    private static final Logger LOGGER = Logger.getLogger(NetBeansMCPHandler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MCPResponseBuilder responseBuilder;
    private final Map<Session, ClientInfo> sessions = new ConcurrentHashMap<>();

    private static final class ClientInfo {
        final String remoteAddress;
        final Instant connectedAt;
        volatile List<String> roots = Collections.emptyList();

        ClientInfo(String remoteAddress, Instant connectedAt) {
            this.remoteAddress = remoteAddress;
            this.connectedAt = connectedAt;
        }
    }

    // Server-initiated requests (e.g. roots/list) awaiting a matching client response
    private final AtomicInteger nextServerRequestId = new AtomicInteger(1);
    private final Map<Integer, Consumer<JsonNode>> pendingServerRequests = new ConcurrentHashMap<>();

    // Tracks request IDs cancelled by the client while an async tool is pending
    private final ConcurrentHashMap<Integer, Boolean> cancelledRequests = new ConcurrentHashMap<>();

    // Selection tracking
    private final Map<JTextComponent, CaretListener> selectionListeners = new WeakHashMap<>();
    private PropertyChangeListener topComponentListener;
    private PropertyChangeListener diffTabListener;
    private JTextComponent currentTextComponent;
    
    private final CheckDocumentDirty checkDocumentDirtyTool;
    private final CloseAllDiffTabs closeAllDiffTabsTool;
    private final CloseTab closeTabTool;
    private final GetCurrentSelection getCurrentSelectionTool;
    private final GetDiagnostics getDiagnosticsTool;
    private final GetOpenEditors getOpenEditorsTool;
    private final GetWorkspaceFolders getWorkspaceFoldersTool;
    private final OpenDiff openDiffTool;
    private final OpenFile openFileTool;
    private final SaveDocument saveDocument;

    public NetBeansMCPHandler() {
        this.responseBuilder = new MCPResponseBuilder(objectMapper);
        this.checkDocumentDirtyTool = new CheckDocumentDirty();
        this.closeAllDiffTabsTool = new CloseAllDiffTabs();
        this.closeTabTool = new CloseTab();
        this.getCurrentSelectionTool = new GetCurrentSelection();
        this.getDiagnosticsTool = new GetDiagnostics();
        this.getOpenEditorsTool = new GetOpenEditors();
        this.getWorkspaceFoldersTool = new GetWorkspaceFolders();
        this.openDiffTool = new OpenDiff();
        this.openFileTool = new OpenFile();
        this.saveDocument = new SaveDocument();
    }
    
    /**
     * Handles incoming MCP messages and routes them to appropriate handlers.
     * 
     * @param message the JSON-RPC message
     * @param session the WebSocket session this message arrived on
     * @return response JSON string, or null if no response needed
     */
    public String handleMessage(JsonNode message, Session session) {
        try {
            if (!message.has("method")) {
                // A response to a server-initiated request (e.g. roots/list) - no "method" field
                Integer responseId = message.has("id") ? message.get("id").asInt() : null;
                Consumer<JsonNode> callback = responseId != null ? pendingServerRequests.remove(responseId) : null;
                if (callback != null) {
                    callback.accept(message.get("result"));
                } else {
                    LOGGER.log(Level.FINE, "Received response for unknown/expired request id: {0}", responseId);
                }
                return null;
            }

            String method = message.get("method").asText();
            JsonNode params = message.get("params");
            Integer id = message.has("id") ? message.get("id").asInt() : null;
            
            LOGGER.log(Level.FINE, "Processing MCP method: {0}", method);
            
            ObjectNode response = responseBuilder.objectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.put("id", id);
            }
            
            switch (method) {
                case "initialize":
                    response.set("result", handleInitialize(params));
                    // Send the response first
                    String initResponse = objectMapper.writeValueAsString(response);
                    // Then send notifications/initialized notification
                    sendInitializedNotification(session);
                    // Ask the client for its workspace roots, if it declared support
                    requestRoots(session, params);
                    return initResponse;

                case "tools/list":
                    response.set("result", handleToolsList());
                    break;

                case "tools/call":
                    JsonNode toolResult = handleToolsCall(params, id, session);
                    if (toolResult == null) {
                        // Async tool - no immediate response
                        return null;
                    }
                    response.set("result", toolResult);
                    break;
                    
                case "resources/list":
                    response.set("result", handleResourcesList());
                    break;
                    
                case "resources/read":
                    response.set("result", handleResourcesRead(params));
                    break;
                    
                case "prompts/list":
                    response.set("result", handlePromptsList());
                    break;

                case "notifications/initialized":
                case "ide_connected":
                case "notifications/cancelled":
                    handleCancelled(params);
                    return null; // notifications never send a response

                default:
                    LOGGER.log(Level.WARNING, "Unknown MCP method: {0}", method);
                    return responseBuilder.createErrorResponse(id, -32601, "Method not found", method);
            }
            
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error handling MCP message", e);
            return responseBuilder.createErrorResponse(null, -32603, "Internal error", e.getMessage());
        }
    }
    
    /**
     * Handles MCP initialize request.
     */
    private JsonNode handleInitialize(JsonNode params) {
        ObjectNode result = responseBuilder.objectNode();
        result.put("protocolVersion", "2024-11-05");
        
        ObjectNode capabilities = responseBuilder.objectNode();
        
        ObjectNode toolsCapability = responseBuilder.objectNode();
        toolsCapability.put("listChanged", true);
        capabilities.set("tools", toolsCapability);
        
        ObjectNode resourcesCapability = responseBuilder.objectNode();
        resourcesCapability.put("subscribe", true);
        resourcesCapability.put("listChanged", true);
        capabilities.set("resources", resourcesCapability);
        
        ObjectNode promptsCapability = responseBuilder.objectNode();
        promptsCapability.put("listChanged", true);
        capabilities.set("prompts", promptsCapability);
        
        result.set("capabilities", capabilities);
        
        ObjectNode serverInfo = responseBuilder.objectNode();
        serverInfo.put("name", "netbeans-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        
        return result;
    }
    
    /**
     * Sends the notifications/initialized notification after successful initialization.
     */
    private void sendInitializedNotification(Session session) {
        try {
            if (session != null && session.isOpen()) {
                ObjectNode notification = responseBuilder.createNotification(
                    "notifications/initialized", null
                );
                String message = objectMapper.writeValueAsString(notification);
                session.getRemote().sendString(message);
                LOGGER.log(Level.FINE, "Sent notifications/initialized notification");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to send initialized notification", e);
        }
    }

    /**
     * Asks the client for its workspace root folders via a server-initiated
     * roots/list request, if the client declared roots capability support in
     * its initialize params. The result (if any) is stored on that session's
     * ClientInfo once the client responds.
     */
    private void requestRoots(Session session, JsonNode initializeParams) {
        boolean supportsRoots = initializeParams != null
            && initializeParams.has("capabilities")
            && initializeParams.get("capabilities").has("roots");
        if (!supportsRoots || session == null || !session.isOpen()) {
            return;
        }
        try {
            int requestId = nextServerRequestId.getAndIncrement();
            pendingServerRequests.put(requestId, result -> handleRootsListResult(session, result));
            ObjectNode request = responseBuilder.createRequest(requestId, "roots/list", null);
            session.getRemote().sendString(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error requesting roots/list", e);
        }
    }

    /**
     * Handles the client's response to a roots/list request: converts each
     * returned file:// URI to a local path and stores the list on that
     * session's ClientInfo for use when filtering broadcasts.
     */
    private void handleRootsListResult(Session session, JsonNode result) {
        if (result == null || !result.has("roots")) {
            return;
        }
        List<String> rootPaths = new ArrayList<>();
        for (JsonNode root : result.get("roots")) {
            if (root.has("uri")) {
                String uri = root.get("uri").asText();
                try {
                    rootPaths.add(new File(new URI(uri)).getAbsolutePath());
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Invalid root URI: {0}", uri);
                }
            }
        }
        ClientInfo info = sessions.get(session);
        if (info != null) {
            info.roots = rootPaths;
            LOGGER.log(Level.INFO, "Resolved client roots: {0}", rootPaths);
        }
    }

    /**
     * Lists available tools (executable functions).
     */
    private JsonNode handleToolsList() {
        ArrayNode tools = responseBuilder.arrayNode();
        
        // Core Claude Code tools - names/descriptions in code, schemas from JSON
        tools.add(createToolDefinition("openFile", "Opens a file in the NetBeans editor. The file must be inside one of the currently open projects.", "OpenFileParams"));
        tools.add(createToolDefinition("getWorkspaceFolders", "Lists the NetBeans projects currently open in the IDE, with each project's display name and root path.", "getWorkspaceFolders"));
        tools.add(createToolDefinition("getOpenEditors", "Lists every editor tab currently open in the IDE, across all open projects.", "getOpenEditors"));
        tools.add(createToolDefinition("getCurrentSelection", "Gets the selected text and cursor position in the currently active editor tab.", "getCurrentSelection"));
        tools.add(createToolDefinition("close_tab", "Closes an open editor tab by its display name (see getOpenEditors for tab names).", "CloseTabParams"));
        tools.add(createToolDefinition("getDiagnostics", "Gets compiler errors/warnings and IDE inspection results. Pass uri to scope to one file; if uri is omitted, returns diagnostics for every currently open file across all open projects.", "GetDiagnosticsParams"));
        tools.add(createToolDefinition("checkDocumentDirty", "Checks whether a file has unsaved changes in the editor.", "CheckDocumentDirtyParams"));
        tools.add(createToolDefinition("saveDocument", "Saves an open file's current editor content to disk.", "SaveDocumentParams"));
        tools.add(createToolDefinition("closeAllDiffTabs", "Closes every open diff viewer tab.", "CloseAllDiffTabsParams"));
        tools.add(createToolDefinition("openDiff", "Opens an interactive side-by-side diff viewer comparing file contents (not tied to git); the user approves or rejects the change from the NetBeans UI.", "OpenDiffParams"));
        
        ObjectNode result = responseBuilder.objectNode();
        result.set("tools", tools);
        return result;
    }
    
    /**
     * Handles tool call requests.
     * @param params Tool call parameters
     * @param requestId Request ID for async response handling
     * @return JsonNode result for sync tools, null for async tools
     */
    private JsonNode handleToolsCall(JsonNode params, Integer requestId, Session session) {
        String toolName = params.get("name").asText();
        JsonNode arguments = params.get("arguments");

        LOGGER.log(Level.INFO, "Tool call [{0}] id={1} args={2}", new Object[]{toolName, requestId, arguments});
        long startMs = System.currentTimeMillis();

        try {
            Object result;

            switch (toolName) {
                // Core Claude Code tools
                case "openFile":
                    result = this.openFileTool.run(this.openFileTool.parseArguments(arguments));
                    break;

                case "getWorkspaceFolders":
                    result = this.getWorkspaceFoldersTool.run(this.getWorkspaceFoldersTool.parseArguments(arguments));
                    break;

                case "getOpenEditors":
                    result = this.getOpenEditorsTool.run(this.getOpenEditorsTool.parseArguments(arguments));
                    break;

                case "getCurrentSelection":
                    result = this.getCurrentSelectionTool.run(this.getCurrentSelectionTool.parseArguments(arguments));
                    break;

                case "close_tab":
                    result = this.closeTabTool.run(this.closeTabTool.parseArguments(arguments));
                    break;

                case "getDiagnostics":
                    result = this.getDiagnosticsTool.run(this.getDiagnosticsTool.parseArguments(arguments));
                    break;

                case "checkDocumentDirty":
                    result = this.checkDocumentDirtyTool.run(this.checkDocumentDirtyTool.parseArguments(arguments));
                    break;

                case "saveDocument":
                    result = this.saveDocument.run(this.saveDocument.parseArguments(arguments));
                    break;

                case "closeAllDiffTabs":
                    result = this.closeAllDiffTabsTool.run(this.closeAllDiffTabsTool.parseArguments(arguments));
                    break;

                case "openDiff":
                    result = this.openDiffTool.run(this.openDiffTool.parseArguments(arguments));
                    break;

                default:
                    throw new IllegalArgumentException("Unknown tool: " + toolName);
            }

            // Check if result is async
            if (result instanceof AsyncResponse) {
                LOGGER.log(Level.INFO, "Tool call [{0}] id={1} → async, waiting for user interaction", new Object[]{toolName, requestId});
                AsyncResponse asyncResponse = (AsyncResponse) result;
                asyncResponse.setHandler(new AsyncHandler() {
                    @Override
                    public void sendResponse(Object finalResult) {
                        if (cancelledRequests.remove(requestId) != null) {
                            LOGGER.log(Level.INFO, "Tool call [{0}] id={1} → cancelled, response discarded", new Object[]{toolName, requestId});
                            return;
                        }
                        LOGGER.log(Level.INFO, "Tool call [{0}] id={1} → async response sent", new Object[]{toolName, requestId});
                        sendAsyncToolResponse(session, requestId, finalResult);
                    }
                });
                return null; // No immediate response
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            LOGGER.log(Level.INFO, "Tool call [{0}] id={1} → OK ({2}ms)", new Object[]{toolName, requestId, elapsedMs});
            return responseBuilder.createToolResponse(result);

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            LOGGER.log(Level.SEVERE, "Tool call [{0}] id={1} → ERROR ({2}ms): {3}", new Object[]{toolName, requestId, elapsedMs, e.getMessage()});
            return responseBuilder.createToolResponse("Error: " + e.getMessage());
        }
    }

    /**
     * Records a client-side cancellation so the pending async response is discarded when it arrives.
     */
    private void handleCancelled(JsonNode params) {
        if (params != null && params.has("requestId")) {
            int cancelledId = params.get("requestId").asInt();
            cancelledRequests.put(cancelledId, Boolean.TRUE);
            LOGGER.log(Level.FINE, "Request cancelled by client: {0}", cancelledId);
        }
    }

    /**
     * Sends an async tool response via WebSocket.
     * @param session The session that originated the request
     * @param requestId The original request ID
     * @param result The tool result to send
     */
    private void sendAsyncToolResponse(Session session, Integer requestId, Object result) {
        try {
            if (session == null || !session.isOpen()) {
                LOGGER.warning("Cannot send async response - WebSocket not open");
                return;
            }

            ObjectNode response = responseBuilder.objectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", requestId);
            response.set("result", responseBuilder.createToolResponse(result));

            String message = objectMapper.writeValueAsString(response);
            session.getRemote().sendString(message);

            LOGGER.log(Level.INFO, "Sent async tool response for request ID: {0}", requestId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error sending async tool response", e);
        }
    }
    
    /**
     * Data class to hold project information.
     */
    private static class ProjectData {
        final String path;
        final String displayName;
        
        ProjectData(String path, String displayName) {
            this.path = path;
            this.displayName = displayName;
        }
    }
    
    /**
     * Retrieves project data from NetBeans Platform.
     */
    private List<ProjectData> getOpenProjectsData() {
        List<ProjectData> projectDataList = new ArrayList<>();
        Project[] openProjects = OpenProjects.getDefault().getOpenProjects();
        
        for (Project project : openProjects) {
            String path = project.getProjectDirectory().getPath();
            String displayName = ProjectUtils.getInformation(project).getDisplayName();
            projectDataList.add(new ProjectData(path, displayName));
        }
        
        return projectDataList;
    }
    
    /**
     * Lists available resources.
     */
    private JsonNode handleResourcesList() {
        ArrayNode resources = responseBuilder.arrayNode();
        
        // Get project data from NetBeans
        List<ProjectData> projectDataList = getOpenProjectsData();
        
        // Build MCP response from the data
        for (ProjectData projectData : projectDataList) {
            ObjectNode resource = responseBuilder.objectNode();
            resource.put("uri", "project://" + projectData.path);
            resource.put("name", projectData.displayName);
            resource.put("description", "NetBeans project: " + projectData.displayName);
            resource.put("mimeType", "application/json");
            resources.add(resource);
        }
        
        ObjectNode result = responseBuilder.objectNode();
        result.set("resources", resources);
        return result;
    }
    
    /**
     * Reads a resource.
     */
    private JsonNode handleResourcesRead(JsonNode params) {
        String uri = params.get("uri").asText();
        
        if (uri.startsWith("project://")) {
            String projectPath = uri.substring("project://".length());
            //XXX: This is probably doing the wrong thing
            return getProjectInfo(projectPath);
        }
        
        throw new IllegalArgumentException("Unknown resource URI: " + uri);
    }
    
    /**
     * Lists available prompts.
     */
    private JsonNode handlePromptsList() {
        ArrayNode prompts = responseBuilder.arrayNode();

        // Disabled: advertised a "code_review" prompt with no backing prompts/get
        // implementation (calling it would hit "Method not found"). Left here commented
        // out in case it's worth implementing for real later.
        // ObjectNode codeReviewPrompt = responseBuilder.objectNode();
        // codeReviewPrompt.put("name", "code_review");
        // codeReviewPrompt.put("description", "Review code in NetBeans project");
        // prompts.add(codeReviewPrompt);

        ObjectNode result = responseBuilder.objectNode();
        result.set("prompts", prompts);
        return result;
    }
    
    // Helper methods
    
    private JsonNode getProjectInfo(String projectPath) {
        FileObject projectDir = FileUtil.toFileObject(new File(projectPath));
        if (projectDir == null) {
            throw new IllegalArgumentException("Project not found: " + projectPath);
        }
        
        ObjectNode projectInfo = responseBuilder.objectNode();
        projectInfo.put("path", projectPath);
        projectInfo.put("name", projectDir.getName());
        
        ArrayNode files = responseBuilder.arrayNode();
        // projectInfo.set("files", files);
        
        return projectInfo;
    }
    
    private ObjectNode createToolDefinition(String toolName, String description, String schemaFileName) {
        ObjectNode tool = responseBuilder.objectNode();
        tool.put("name", toolName);
        tool.put("description", description);
        
        try {
            // Load parameter schema from JSON file
            String schemaPath = "/org/openbeans/claude/netbeans/tools/schemas/" + schemaFileName + ".json";
            InputStream inputStream = getClass().getResourceAsStream(schemaPath);
            
            if (inputStream == null) {
                // Fall back to empty schema if file not found
                LOGGER.warning("Schema file not found: " + schemaPath);
                ObjectNode inputSchema = responseBuilder.objectNode();
                inputSchema.put("type", "object");
                inputSchema.set("properties", responseBuilder.objectNode());
                inputSchema.set("required", responseBuilder.arrayNode());
                tool.set("inputSchema", inputSchema);
            } else {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode schema = mapper.readTree(inputStream);
                inputStream.close();
                
                // Set the loaded schema as inputSchema
                tool.set("inputSchema", schema);
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error loading parameter schema for: " + toolName, e);
            // Return minimal schema as fallback
            ObjectNode inputSchema = responseBuilder.objectNode();
            inputSchema.put("type", "object");
            inputSchema.set("properties", responseBuilder.objectNode());
            inputSchema.set("required", responseBuilder.arrayNode());
            tool.set("inputSchema", inputSchema);
        }
        
        return tool;
    }
    
    public void addSession(Session session) {
        boolean wasEmpty = sessions.isEmpty();
        sessions.put(session, new ClientInfo(String.valueOf(session.getRemoteAddress()), Instant.now()));

        if (wasEmpty) {
            // Start tracking selection changes and diff tabs once the first client connects
            startSelectionTracking();
            startDiffTabTracking();
        }
    }

    public void removeSession(Session session) {
        sessions.remove(session);

        if (sessions.isEmpty()) {
            // Stop tracking once the last client disconnects
            stopSelectionTracking();
            stopDiffTabTracking();
        }
    }

    public boolean isConnected() {
        return !sessions.isEmpty();
    }

    public int getConnectedClientCount() {
        return sessions.size();
    }
    
    /**
     * Starts tracking selection changes in editors.
     */
    private void startSelectionTracking() {
        // Listen for TopComponent activation changes
        topComponentListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (TopComponent.Registry.PROP_ACTIVATED.equals(evt.getPropertyName())) {
                    TopComponent activated = TopComponent.getRegistry().getActivated();
                    if (activated != null) {
                        trackEditorSelection(activated);
                    }
                }
            }
        };
        
        TopComponent.getRegistry().addPropertyChangeListener(topComponentListener);
        
        // Track the currently active editor if any — must run on EDT
        TopComponent activated = TopComponent.getRegistry().getActivated();
        if (activated != null) {
            final TopComponent activatedFinal = activated;
            SwingUtilities.invokeLater(() -> trackEditorSelection(activatedFinal));
        }
        
        LOGGER.log(Level.FINE, "Started selection tracking");
    }
    
    /**
     * Stops tracking selection changes.
     */
    private void stopSelectionTracking() {
        // Remove TopComponent listener
        if (topComponentListener != null) {
            TopComponent.getRegistry().removePropertyChangeListener(topComponentListener);
            topComponentListener = null;
        }
        
        // Remove all selection listeners
        for (Map.Entry<JTextComponent, CaretListener> entry : selectionListeners.entrySet()) {
            entry.getKey().removeCaretListener(entry.getValue());
        }
        selectionListeners.clear();
        currentTextComponent = null;
        
        LOGGER.log(Level.FINE, "Stopped selection tracking");
    }

    /**
     * Starts tracking diff tab closures for async response handling.
     */
    private void startDiffTabTracking() {
        diffTabListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (TopComponent.Registry.PROP_TC_CLOSED.equals(evt.getPropertyName())) {
                    TopComponent closed = (TopComponent) evt.getNewValue();
                    if (closed != null) {
                        String tabName = closed.getDisplayName();
                        if (tabName != null && DiffTabTracker.isTracked(tabName)) {
                            handleDiffTabClosed(tabName);
                        }
                    }
                }
            }
        };

        TopComponent.getRegistry().addPropertyChangeListener(diffTabListener);
        LOGGER.log(Level.FINE, "Started diff tab tracking");
    }

    /**
     * Stops tracking diff tab closures.
     */
    private void stopDiffTabTracking() {
        if (diffTabListener != null) {
            TopComponent.getRegistry().removePropertyChangeListener(diffTabListener);
            diffTabListener = null;
        }
        LOGGER.log(Level.FINE, "Stopped diff tab tracking");
    }

    /**
     * Handles a diff tab being closed, sending the async response.
     */
    private void handleDiffTabClosed(String tabName) {
        AsyncHandler handler = DiffTabTracker.remove(tabName);
        if (handler != null) {
            LOGGER.log(Level.INFO, "Diff tab closed: {0}", tabName);

            // Create response with DIFF_REJECTED status
            List<Content> contentList = new ArrayList<>();
            contentList.add(new Content("text", "DIFF_REJECTED"));
            contentList.add(new Content("text", tabName));
            OpenDiffResult result = new OpenDiffResult(contentList);

            handler.sendResponse(result);
        }
    }

    /**
     * Tracks selection changes in the given TopComponent if it's an editor.
     */
    private void trackEditorSelection(TopComponent tc) {
        try {
            Node[] nodes = tc.getActivatedNodes();
            if (nodes != null && nodes.length > 0) {
                EditorCookie editorCookie = nodes[0].getLookup().lookup(EditorCookie.class);
                if (editorCookie != null) {
                    JTextComponent[] panes = editorCookie.getOpenedPanes();
                    if (panes != null && panes.length > 0) {
                        JTextComponent textComponent = panes[0];
                        
                        // Only track if it's a different component
                        if (textComponent != currentTextComponent) {
                            // Remove listener from previous component
                            if (currentTextComponent != null) {
                                CaretListener listener = selectionListeners.remove(currentTextComponent);
                                if (listener != null) {
                                    currentTextComponent.removeCaretListener(listener);
                                }
                            }
                            
                            // Add listener to new component
                            currentTextComponent = textComponent;
                            CaretListener listener = new CaretListener() {
                                @Override
                                public void caretUpdate(CaretEvent e) {
                                    sendSelectionChangeEvent(textComponent, nodes[0]);
                                }
                            };
                            
                            textComponent.addCaretListener(listener);
                            selectionListeners.put(textComponent, listener);
                            
                            // Send initial selection event
                            sendSelectionChangeEvent(textComponent, nodes[0]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error tracking editor selection", e);
        }
    }
    
    /**
     * Sends a selection_changed event to Claude Code via WebSocket.
     */
    private void sendSelectionChangeEvent(JTextComponent textComponent, Node node) {
        try {
            if (sessions.isEmpty()) {
                return;
            }

            // Get selection details
            String selectedText = textComponent.getSelectedText();
            int selectionStart = textComponent.getSelectionStart();
            int selectionEnd = textComponent.getSelectionEnd();
            
            // Get document and file info
            Document doc = textComponent.getDocument();
            DataObject dataObject = node.getLookup().lookup(DataObject.class);
            
            if (doc instanceof StyledDocument && dataObject != null) {
                StyledDocument styledDoc = (StyledDocument) doc;
                FileObject fileObject = dataObject.getPrimaryFile();
                
                if (fileObject != null) {
                    // Get file path
                    File file = FileUtil.toFile(fileObject);
                    String absolutePath = file.getAbsolutePath();
                    String fileUrl = "file://" + absolutePath;
                    
                    // Calculate line and column positions (0-based for protocol)
                    int startLine = NbDocument.findLineNumber(styledDoc, selectionStart);
                    int startColumn = NbDocument.findLineColumn(styledDoc, selectionStart);
                    int endLine = NbDocument.findLineNumber(styledDoc, selectionEnd);
                    int endColumn = NbDocument.findLineColumn(styledDoc, selectionEnd);
                    
                    // Create selection_changed notification
                    ObjectNode params = responseBuilder.objectNode();
                    
                    // Add text (selected text or empty string)
                    params.put("text", selectedText != null ? selectedText : "");
                    
                    // Add file paths
                    params.put("filePath", absolutePath);
                    params.put("fileUrl", fileUrl);
                    
                    // Add selection object
                    ObjectNode selection = responseBuilder.objectNode();
                    
                    ObjectNode start = responseBuilder.objectNode();
                    start.put("line", startLine);
                    start.put("character", startColumn);
                    selection.set("start", start);
                    
                    ObjectNode end = responseBuilder.objectNode();
                    end.put("line", endLine);
                    end.put("character", endColumn);
                    selection.set("end", end);
                    
                    // Set isEmpty based on whether there's selected text
                    selection.put("isEmpty", selectedText == null || selectedText.isEmpty());
                    
                    params.set("selection", selection);
                    
                    // Create and broadcast the notification to clients whose roots include this file
                    ObjectNode notification = responseBuilder.createNotification("selection_changed", params);
                    String message = objectMapper.writeValueAsString(notification);
                    broadcastToClientsWithRoot(message, absolutePath);

                    LOGGER.log(Level.FINE, "Sent selection_changed event: {0}", message);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error sending selection change event", e);
        }
    }

    /**
     * Sends a raw JSON message to every connected client whose declared roots include
     * filePath. Clients with no resolved roots yet (not requested, not supported, or no
     * response received) are always sent the message - filtering only ever narrows down
     * clients that positively reported roots not covering this file.
     */
    private void broadcastToClientsWithRoot(String message, String filePath) {
        for (Map.Entry<Session, ClientInfo> entry : sessions.entrySet()) {
            Session s = entry.getKey();
            List<String> roots = entry.getValue().roots;
            boolean shouldSend = roots.isEmpty() || NbUtils.isPathWithinRoots(filePath, roots);
            if (!shouldSend) {
                continue;
            }
            try {
                if (s.isOpen()) {
                    s.getRemote().sendString(message);
                } else {
                    sessions.remove(s);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error broadcasting to session", e);
            }
        }
    }
}
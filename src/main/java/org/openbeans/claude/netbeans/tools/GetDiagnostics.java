package org.openbeans.claude.netbeans.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.text.StyledDocument;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.Task;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Severity;
import org.openbeans.claude.netbeans.NbUtils;
import org.openbeans.claude.netbeans.tools.params.Diagnostic;
import org.openbeans.claude.netbeans.tools.params.DiagnosticsResponse;
import org.openbeans.claude.netbeans.tools.params.End;
import org.openbeans.claude.netbeans.tools.params.GetDiagnosticsParams;
import org.openbeans.claude.netbeans.tools.params.Range;
import org.openbeans.claude.netbeans.tools.params.Start;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.text.NbDocument;
import org.openide.util.Lookup;
import org.openide.windows.TopComponent;




/**
 * Tool to get diagnostic information (errors, warnings) for files.
 *
 * Sources:
 *   1. JavaSource API  — compiler diagnostics from javac (errors/warnings)
 *   2. "nb-errors" doc property — IDE inspections already computed for open files
 */
public class GetDiagnostics implements Tool<GetDiagnosticsParams, String> {

    private static final Logger LOGGER = Logger.getLogger(GetDiagnostics.class.getName());


    @Override public String getName()        { return "getDiagnostics"; }
    @Override public String getDescription() { return "Get diagnostic information (errors, warnings) for files"; }
    @Override public Class<GetDiagnosticsParams> getParameterClass() { return GetDiagnosticsParams.class; }

    @Override
    public String run(GetDiagnosticsParams params) throws Exception {
        try {
            return new ObjectMapper().writeValueAsString(_run(params));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get diagnostics", e);
            return "[]";
        }
    }

    // ─── Main flow ────────────────────────────────────────────────────────────

    private List<DiagnosticsResponse> _run(GetDiagnosticsParams params) throws Exception {
        String uri = params.getUri();
        LOGGER.log(Level.INFO, "getDiagnostics start — uri={0}", uri != null ? uri : "<all open files>");

        List<FileObject> targets = resolveTargets(uri);
        LOGGER.log(Level.INFO, "getDiagnostics targets: {0} file(s)", targets.size());

        // Per-file diagnostic accumulator (preserves insertion order)
        Map<FileObject, List<Diagnostic>> acc = new LinkedHashMap<>();
        for (FileObject fo : targets) acc.put(fo, new ArrayList<>());

        // ── Step 1: IDE inspections (EDT, open files only) ──────────────────
        SwingUtilities.invokeAndWait(() -> {
            for (FileObject fo : targets) {
                collectHints(fo, acc.get(fo));
            }
        });

        // ── Step 2: javac compiler diagnostics (JavaSource, NOT on EDT) ──────
        for (FileObject fo : targets) {
            collectJavacDiagnostics(fo, acc.get(fo));
        }

        // ── Step 3: build response ─────────────────────────────────────────
        List<DiagnosticsResponse> result = new ArrayList<>();
        for (Map.Entry<FileObject, List<Diagnostic>> entry : acc.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            File file = FileUtil.toFile(entry.getKey());
            DiagnosticsResponse response = new DiagnosticsResponse();
            response.setUri(URI.create("file:///" + file.getAbsolutePath().replace('\\', '/')));
            response.setDiagnostics(entry.getValue());
            result.add(response);
        }

        int total = result.stream().mapToInt(r -> r.getDiagnostics().size()).sum();
        LOGGER.log(Level.INFO, "getDiagnostics done — {0} file(s), {1} diagnostic(s)",
                new Object[]{result.size(), total});
        return result;
    }

    // ─── Target resolution ────────────────────────────────────────────────────

    private List<FileObject> resolveTargets(String uri) throws Exception {
        List<FileObject> targets = new ArrayList<>();
        if (uri != null) {
            String filePath = parseFilePath(uri);
            LOGGER.log(Level.INFO, "getDiagnostics resolved path: {0}", filePath);
            if (!NbUtils.isPathWithinOpenProjects(filePath)) {
                LOGGER.warning("getDiagnostics: path not within open projects: " + filePath);
                return targets;
            }
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(filePath)));
            if (fo != null) targets.add(fo);
            else LOGGER.warning("getDiagnostics: FileObject not found for: " + filePath);
        } else {
            // Collect open Java files — requires EDT
            final List<FileObject> collected = new ArrayList<>();
            Runnable collector = () -> {
                for (TopComponent tc : TopComponent.getRegistry().getOpened()) {
                    DataObject dao = tc.getLookup().lookup(DataObject.class);
                    if (dao != null) {
                        FileObject fo = dao.getPrimaryFile();
                        if (fo != null && "java".equalsIgnoreCase(fo.getExt())) {
                            collected.add(fo);
                        }
                    }
                }
            };
            if (SwingUtilities.isEventDispatchThread()) collector.run();
            else SwingUtilities.invokeAndWait(collector);
            targets.addAll(collected);
        }
        return targets;
    }

    // ─── Source 1: IDE inspections ────────────────────────────────────────────

    /**
     * Reads IDE inspections already computed by NetBeans and stored on the document.
     * In NetBeans 19+, hints are stored via AnnotationHolder (internal class) using
     * the AnnotationHolder.class object as the document property key — not "nb-errors".
     * We access it via reflection since it's a package-private implementation class.
     */
    private void collectHints(FileObject fo, List<Diagnostic> result) {
        try {
            DataObject dao = DataObject.find(fo);
            EditorCookie ec = dao.getLookup().lookup(EditorCookie.class);
            if (ec == null) return;

            javax.swing.text.Document doc = ec.getDocument();
            if (!(doc instanceof StyledDocument)) return;
            StyledDocument styledDoc = (StyledDocument) doc;

            Map<String, List<ErrorDescription>> hintsMap = resolveHintsMap(fo);
            if (hintsMap == null || hintsMap.isEmpty()) return;

            int count = 0;
            for (List<ErrorDescription> hints : hintsMap.values()) {
                for (ErrorDescription hint : hints) {
                    if (hint.getSeverity() == Severity.HINT) continue;
                    Diagnostic d = convertHint(hint, styledDoc);
                    if (d != null) { result.add(d); count++; }
                }
            }
            LOGGER.log(Level.INFO, "IDE hints for {0}: {1} (layers: {2})",
                    new Object[]{fo.getNameExt(), count, hintsMap.keySet()});

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error reading IDE hints for: " + fo.getPath(), e);
        }
    }

    /**
     * Finds the IDE hints map for a file via AnnotationHolder's static file2Holder map.
     *
     * AnnotationHolder maintains a static Map<FileObject, AnnotationHolder> called file2Holder.
     * Each AnnotationHolder has a field layer2Errors: Map<String layerId, List<ErrorDescription>>.
     * The holder is NOT stored as a document property — it is keyed by FileObject.
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<ErrorDescription>> resolveHintsMap(FileObject fo) {
        try {
            ClassLoader sysCl = Lookup.getDefault().lookup(ClassLoader.class);
            if (sysCl == null) sysCl = Thread.currentThread().getContextClassLoader();

            Class<?> ahClass = sysCl.loadClass(
                    "org.netbeans.modules.editor.hints.AnnotationHolder");

            // file2Holder is a static Map<FileObject, AnnotationHolder>
            Field f2hField = ahClass.getDeclaredField("file2Holder");
            f2hField.setAccessible(true);
            Map<?, ?> file2Holder = (Map<?, ?>) f2hField.get(null);

            if (file2Holder == null) {
                LOGGER.log(Level.INFO, "file2Holder is null for {0}", fo.getNameExt());
                return null;
            }

            // Map key is DataObject (JavaDataObject), not FileObject
            DataObject dao = DataObject.find(fo);
            Object holder = file2Holder.get(dao);
            if (holder == null) {
                LOGGER.log(Level.INFO, "No AnnotationHolder entry for {0}", fo.getNameExt());
                return null;
            }

            Field l2eField = ahClass.getDeclaredField("layer2Errors");
            l2eField.setAccessible(true);
            Map<String, List<ErrorDescription>> errMap =
                    (Map<String, List<ErrorDescription>>) l2eField.get(holder);

            LOGGER.log(Level.INFO, "IDE hints via AnnotationHolder.layer2Errors for {0}: {1} layer(s)",
                    new Object[]{fo.getNameExt(), errMap != null ? errMap.size() : 0});
            return errMap;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "AnnotationHolder reflection error for " + fo.getNameExt(), e);
            return null;
        }
    }

    private Diagnostic convertHint(ErrorDescription hint, StyledDocument doc) {
        try {
            Diagnostic d = new Diagnostic();
            d.setMessage(hint.getDescription());
            d.setSource("netbeans-hints");

            Severity sev = hint.getSeverity();
            if (sev == Severity.ERROR) {
                d.setSeverity(Diagnostic.Severity.ERROR);
            } else if (sev == Severity.WARNING || sev == Severity.VERIFIER) {
                d.setSeverity(Diagnostic.Severity.WARNING);
            } else {
                d.setSeverity(Diagnostic.Severity.INFO);
            }

            int startOffset = hint.getRange().getBegin().getOffset();
            int endOffset   = hint.getRange().getEnd().getOffset();
            int startLine = NbDocument.findLineNumber(doc, startOffset);
            int startCol  = NbDocument.findLineColumn(doc, startOffset);
            int endLine   = NbDocument.findLineNumber(doc, endOffset);
            int endCol    = NbDocument.findLineColumn(doc, endOffset);

            Start start = new Start(); start.setLine(startLine); start.setCharacter(startCol);
            End   end   = new End();   end.setLine(endLine);     end.setCharacter(endCol);
            Range range = new Range(); range.setStart(start);    range.setEnd(end);
            d.setRange(range);

            return d;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error converting hint", e);
            return null;
        }
    }

    // ─── Source 2: javac compiler diagnostics ─────────────────────────────────

    /**
     * Collects javac compiler diagnostics via JavaSource API.
     * Uses reflection to avoid ClassCastException from NetBeans module classloader
     * isolation (RichDiagnostic cannot be cast to javax.tools.Diagnostic directly).
     * Must NOT be called from EDT to avoid deadlock with JavaSource task queue.
     */
    private void collectJavacDiagnostics(FileObject fo, List<Diagnostic> result) {
        JavaSource javaSource = JavaSource.forFileObject(fo);
        if (javaSource == null) {
            LOGGER.log(Level.FINE, "No JavaSource for: {0}", fo.getPath());
            return;
        }

        try {
            javaSource.runUserActionTask(new Task<CompilationController>() {
                @Override
                public void run(CompilationController controller) throws Exception {
                    controller.toPhase(JavaSource.Phase.RESOLVED);
                    List<?> all = controller.getDiagnostics();
                    LOGGER.log(Level.INFO, "javac diagnostics for {0}: {1} total",
                            new Object[]{fo.getNameExt(), all.size()});
                    // Load javax.tools.Diagnostic from the SAME classloader as the
                    // diagnostics themselves. NetBeans bundles its own copy of javax.tools
                    // which is different from the JDK's java.compiler version, so we must
                    // use the controller's classloader to get the matching interface.
                    ClassLoader diagCl = controller.getClass().getClassLoader();
                    if (diagCl == null) diagCl = ClassLoader.getPlatformClassLoader();
                    Class<?> diagIface;
                    Method mKind, mLine, mCol, mMsg, mCode;
                    try {
                        diagIface = diagCl.loadClass("javax.tools.Diagnostic");
                        mKind = diagIface.getMethod("getKind");
                        mLine = diagIface.getMethod("getLineNumber");
                        mCol  = diagIface.getMethod("getColumnNumber");
                        mMsg  = diagIface.getMethod("getMessage", Locale.class);
                        mCode = diagIface.getMethod("getCode");
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Cannot load Diagnostic methods from NB classloader", e);
                        return;
                    }
                    for (Object d : all) {
                        if (!diagIface.isInstance(d)) {
                            LOGGER.log(Level.WARNING, "Unexpected diagnostic type: {0}", d.getClass());
                            continue;
                        }
                        try {
                            String kind = mKind.invoke(d).toString();
                            if (!"ERROR".equals(kind) && !"WARNING".equals(kind)
                                    && !"MANDATORY_WARNING".equals(kind)) continue;
                            long line = (Long) mLine.invoke(d);
                            long col  = (Long) mCol.invoke(d);
                            String msg = (String) mMsg.invoke(d, Locale.getDefault());
                            Object codeObj = mCode.invoke(d);
                            String code = codeObj != null ? codeObj.toString() : null;
                            LOGGER.log(Level.INFO, "  [{0}] line={1} code={2} msg={3}",
                                    new Object[]{kind, line, code, msg});
                            result.add(buildJavacDiagnostic(kind, line, col, msg, code));
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Diagnostic invoke failed on " + d.getClass(), e);
                        }
                    }
                }
            }, true);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error running JavaSource task for: " + fo.getPath(), e);
        }
    }

    private Diagnostic buildJavacDiagnostic(String kind, long line, long col, String msg, String code) {
        Diagnostic diag = new Diagnostic();
        diag.setMessage(msg);
        diag.setSource("java-compiler");
        if (code != null) diag.setCode(code);

        diag.setSeverity("ERROR".equals(kind) ? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING);

        long l = Math.max(1, line);
        long c = Math.max(1, col);
        Start start = new Start(); start.setLine((int)(l - 1)); start.setCharacter((int)(c - 1));
        End   end   = new End();   end.setLine((int)(l - 1));   end.setCharacter((int)c);
        Range range = new Range(); range.setStart(start);       range.setEnd(end);
        diag.setRange(range);
        return diag;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String parseFilePath(String uri) {
        if (uri.startsWith("file:///")) return uri.substring(8);
        if (uri.startsWith("file://"))  return uri.substring(7);
        return uri;
    }
}

package com.webshare.service;

import com.webshare.util.AppLogger;
import com.webshare.util.Utility;
import io.undertow.Undertow;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.*;
import io.undertow.util.*;
import org.xnio.Options;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class WebShareServer {

    private final FileService     fileService;
    private final TemplateService templateService;
    private final SessionManager  session;
    private final RateLimiter     rateLimiter = new RateLimiter();

    private File tempDir;
    private final Set<String>  mergeInProgress = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicLong   tmpDirBytes     = new AtomicLong(0);

    private static final long  MAX_TMP_BYTES      = 2L  * 1024 * 1024 * 1024;
    private static final int   MAX_ACTIVE_UPLOADS = 20;
    private final Set<String>  activeUploads      = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final long  MAX_UPLOAD_BYTES   = 1024L * 1024 * 1024;
    private static final long  MAX_CHUNK_BYTES    = 50L   * 1024 * 1024;
    private static final long  MAX_CHECKSUM_BYTES = 500L  * 1024 * 1024;
    private static final int   IO_THREADS         = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int   WORKER_THREADS     = IO_THREADS * 8;

    private Undertow server;

    // FIX (#9): No static cspNonce field — nonce is generated fresh per response in freshNonce()

    // ══════════════════════════════════════════════════════════════════════════════
    // Constructor
    // ══════════════════════════════════════════════════════════════════════════════

    public WebShareServer(File folder, SessionManager session) {
        try {
            this.fileService = new FileService(folder);
        } catch (IOException e) {
            throw new RuntimeException("Invalid share folder: " + folder.getAbsolutePath(), e);
        }
        this.templateService = new TemplateService();
        this.session         = session;

        this.tempDir = new File(folder, ".tmp");
        if (!this.tempDir.exists()) this.tempDir.mkdirs();

        // FIX (#10): only wipe stale .merged files on startup, NOT .part files —
        // those are needed for resumable uploads from the previous session.
        cleanStaleMerges();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Server lifecycle
    // ══════════════════════════════════════════════════════════════════════════════

    public String startServer() {
        try {
            SSLContext sslContext = buildSslContext();
            String ip   = getLanIp();
            int    port = Utility.selectPort();

            server = Undertow.builder()
                    .addHttpsListener(port, "0.0.0.0", sslContext)
                    .setIoThreads(IO_THREADS)
                    .setWorkerThreads(WORKER_THREADS)
                    .setSocketOption(Options.TCP_NODELAY,     true)
                    .setSocketOption(Options.REUSE_ADDRESSES, true)
                    .setServerOption(io.undertow.UndertowOptions.ENABLE_HTTP2,    true)
                    .setServerOption(io.undertow.UndertowOptions.MAX_ENTITY_SIZE, MAX_UPLOAD_BYTES)
                    .setHandler(this::route)
                    .build();

            server.start();
            String url = "https://" + ip + ":" + port;
            AppLogger.info("Server started — " + url);
            return url;
        } catch (Exception e) {
            AppLogger.error("Failed to start server", e);
            return "Failed: " + e.getMessage();
        }
    }

    public void stopServer() {
        if (server != null) server.stop();
        session.clearSessions();
        cleanTempDir();
        AppLogger.info("Server stopped.");
    }

    /** Full wipe — called only on explicit stop. */
    private void cleanTempDir() {
        if (tempDir == null || !tempDir.exists()) return;
        File[] files = tempDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        tmpDirBytes.set(0);
    }

    /** FIX (#10): only removes leftover .merged files; .part files are preserved for resume. */
    private void cleanStaleMerges() {
        if (tempDir == null || !tempDir.exists()) return;
        File[] stale = tempDir.listFiles((_, n) -> n.endsWith(".merged"));
        if (stale != null) {
            for (File f : stale) {
                long sz = f.length();
                if (f.delete()) tmpDirBytes.addAndGet(-sz);
            }
        }
        // Recount tmpDirBytes from surviving .part files
        File[] parts = tempDir.listFiles((_, n) -> n.contains(".part"));
        long total = 0;
        if (parts != null) for (File f : parts) total += f.length();
        tmpDirBytes.set(total);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Routing
    // ══════════════════════════════════════════════════════════════════════════════

    private void route(HttpServerExchange ex) {
        String path   = ex.getRequestPath();
        String method = ex.getRequestMethod().toString();

        try {
            if (path.equals("/logo"))           { serveLogo(ex);     return; }
            if (path.equals("/js/webshare.js")) { serveStaticJs(ex); return; }
            if (path.equals("/cert"))           { serveCert(ex);     return; }

            if ("POST".equals(method) && path.equals("/auth"))   { handleAuth(ex);   return; }
            if ("POST".equals(method) && path.equals("/logout")) { handleLogout(ex); return; }

            if (path.equals("/") || path.equals("/index")) {
                if (!isAuthorized(ex)) { serveKeyPage(ex); return; }
                serveIndex(ex);
                return;
            }

            if (!isAuthorized(ex)) { forbidden(ex); return; }

            if (path.equals("/events"))    { handleSettings(ex);    return; }
            if (path.equals("/api/files")) { serveFileListJson(ex); return; }

            if (path.startsWith("/files/")) { serveFile(ex, path); return; }

            if ("POST".equals(method)) {
                if (path.equals("/upload"))         { handleUpload(ex);         return; }
                if (path.equals("/upload-chunk"))    { handleUploadChunk(ex);    return; }
                if (path.equals("/upload-complete")) { handleUploadComplete(ex); return; }
            }

            if ("GET".equals(method)) {
                if (path.equals("/file-checksum"))     { handleFileChecksum(ex); return; }
                if (path.equals("/api/upload-status")) { handleUploadStatus(ex); return; }
            }

            notFound(ex);
        } catch (SecurityException se) {
            forbidden(ex);
        } catch (Exception e) {
            AppLogger.error("Error routing " + path, e);
            send(ex, 500, "text/plain", "Internal error");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Auth & Security
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleAuth(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(this::handleAuth); return; }
        if (!isSameOrigin(ex)) { forbidden(ex); return; }

        String ip = ex.getSourceAddress().getAddress().getHostAddress();
        if (rateLimiter.isBlocked(ip)) { tooManyRequests(ex); return; }

        FormData form = parseForm(ex);
        String key = (form != null && form.contains("key")) ? form.getFirst("key").getValue() : "";

        if (!session.checkKey(key)) {
            rateLimiter.recordFail(ip);
            redirect(ex, "/?invalid=1");
            return;
        }
        rateLimiter.reset(ip);
        String token = session.createSession();
        ex.getResponseHeaders().put(Headers.SET_COOKIE, sessionCookie(token));
        redirect(ex, "/");
    }

    private void handleLogout(HttpServerExchange ex) {
        if (!isSameOrigin(ex)) { forbidden(ex); return; }
        String sid = extractSid(ex);
        if (sid != null) session.revokeSession(sid);
        ex.getResponseHeaders().put(Headers.SET_COOKIE,
                "sid=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0");
        redirect(ex, "/");
    }

    private enum ClientOS { WINDOWS, ANDROID, IOS, MAC }

    private boolean isSameOrigin(HttpServerExchange ex) {
        String hostHeader = ex.getRequestHeaders().getFirst(Headers.HOST);
        if (hostHeader == null) return false;
        String serverAuthority = hostHeader.toLowerCase().trim();

        String origin  = ex.getRequestHeaders().getFirst("Origin");
        String referer = ex.getRequestHeaders().getFirst(Headers.REFERER);

        if (origin != null && !origin.equals("null")) {
            String h = origin.replaceFirst("^https?://", "").replaceAll("/.*$", "").toLowerCase().trim();
            return h.equals(serverAuthority);
        }
        if (referer != null) {
            try {
                URI uri = new URI(referer);
                String h = uri.getHost().toLowerCase();
                if (uri.getPort() != -1) h += ":" + uri.getPort();
                return h.equals(serverAuthority);
            } catch (Exception e) { return false; }
        }
        return !ex.getRequestMethod().equals(Methods.POST);
    }

    private boolean isAuthorized(HttpServerExchange ex) {
        String sid = extractSid(ex);
        return sid != null && session.checkSession(sid);
    }

    private String extractSid(HttpServerExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals("sid")) return kv[1].trim();
        }
        return null;
    }

    private String sessionCookie(String token) {
        return "sid=" + token + "; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=1800";
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // File Serving (Range / Resume)
    // ══════════════════════════════════════════════════════════════════════════════

    private void serveFile(HttpServerExchange ex, String uri) {
    if (ex.isInIoThread()) { ex.dispatch(() -> serveFile(ex, uri)); return; }

    try {
        File file = fileService.getFile(uri, session.isExecAllowed());
        if (file == null || !file.exists()) { notFound(ex); return; }

        long   fileSize = file.length();
        String range    = ex.getRequestHeaders().getFirst(Headers.RANGE);
        String mime     = URLConnection.guessContentTypeFromName(file.getName());
        if (mime == null) mime = "application/octet-stream";

        ex.getResponseHeaders().put(Headers.CONTENT_TYPE,  mime);
        ex.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");

        String encodedName = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
        ex.getResponseHeaders().put(Headers.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedName);

        // Open WITHOUT try-with-resources — callbacks own the lifecycle
        FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.READ);

        try {
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                long start = Long.parseLong(parts[0].trim());
                long end   = (parts.length > 1 && !parts[1].trim().isEmpty())
                             ? Long.parseLong(parts[1].trim())
                             : fileSize - 1;

                if (start >= fileSize || end < start || end >= fileSize) {
                    ex.setStatusCode(416);
                    ex.getResponseHeaders().put(Headers.CONTENT_RANGE, "bytes */" + fileSize);
                    fc.close(); // safe — no async transfer started yet
                    ex.endExchange();
                    return;
                }

                ex.setStatusCode(206);
                ex.getResponseHeaders().put(Headers.CONTENT_RANGE,
                        "bytes " + start + "-" + end + "/" + fileSize);
                ex.setResponseContentLength(end - start + 1);
                fc.position(start);
            } else {
                ex.setStatusCode(200);
                ex.setResponseContentLength(fileSize);
            }

            ex.getResponseSender().transferFrom(fc, new IoCallback() {
                @Override public void onComplete(HttpServerExchange e, Sender s) {
                    try { fc.close(); } catch (IOException ignore) {}
                    e.endExchange();
                }
                @Override public void onException(HttpServerExchange e, Sender s, IOException ex2) {
                    try { fc.close(); } catch (IOException ignore) {}
                    e.endExchange();
                }
            });
        } catch (Exception e) {
            try { fc.close(); } catch (IOException ignore) {}
            throw e;
        }

    } catch (Exception e) {
        AppLogger.error("serveFile error", e);
        send(ex, 500, "text/plain", "Error accessing file");
    }
}
    // ══════════════════════════════════════════════════════════════════════════════
    // Upload — chunked
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleUploadChunk(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> handleUploadChunk(ex)); return; }
        if (!session.isUploadAllowed()) { forbidden(ex); return; }

        ex.startBlocking();

        String name   = getQueryParam(ex, "name");
        String idxStr = getQueryParam(ex, "index");
        if (name == null || idxStr == null) { send(ex, 400, "text/plain", "Params missing"); return; }

        String safeName;
        int    index;
        try {
            safeName = new File(name).getName();
            index    = Integer.parseInt(idxStr);
        } catch (NumberFormatException e) {
            send(ex, 400, "text/plain", "Invalid index");
            return;
        }
        if (safeName.isEmpty()) { send(ex, 400, "text/plain", "Invalid filename"); return; }

        if (activeUploads.size() >= MAX_ACTIVE_UPLOADS && !activeUploads.contains(safeName)) {
            send(ex, 429, "text/plain", "Too many concurrent uploads");
            return;
        }
        if (tmpDirBytes.get() >= MAX_TMP_BYTES) {
            send(ex, 507, "text/plain", "Storage budget exceeded");
            return;
        }

        // FIX (#1): always release activeUploads slot in finally
        activeUploads.add(safeName);
        try {
            File chunkFile = new File(tempDir, safeName + ".part" + index);

            // FIX (#3): subtract old chunk size before overwrite to keep tmpDirBytes accurate
            if (chunkFile.exists()) tmpDirBytes.addAndGet(-chunkFile.length());

            long    bytesWritten = 0;
            boolean tooLarge     = false;

            try (InputStream  in  = ex.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(chunkFile))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bytesWritten += n;
                    if (bytesWritten > MAX_CHUNK_BYTES) {
                        tooLarge = true;
                        // drain remaining input to keep the connection healthy
                        while (in.read(buf) != -1) {}
                        break;
                    }
                    out.write(buf, 0, n);
                }
            }

            if (tooLarge) {
                if (chunkFile.exists()) chunkFile.delete();
                send(ex, 413, "text/plain", "Chunk too large");
                return;
            }

            tmpDirBytes.addAndGet(bytesWritten);
            send(ex, 200, "text/plain", "OK");

        } catch (Exception e) {
            AppLogger.error("Chunk upload error", e);
            send(ex, 500, "text/plain", "Upload failed");
        } finally {
            // FIX (#1): unconditional release so server never hits the 20-slot wall
            activeUploads.remove(safeName);
        }
    }

    private void handleUploadComplete(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> handleUploadComplete(ex)); return; }
        ex.startBlocking();

        // FIX (#4): extract safeName before try/finally — finally never NPEs
        String name = getQueryParam(ex, "name");
        if (name == null) { send(ex, 400, "text/plain", "Missing name"); return; }

        String safeName;
        int    totalChunks;
        try {
            safeName    = new File(name).getName();
            totalChunks = Integer.parseInt(Objects.requireNonNull(getQueryParam(ex, "totalChunks")));
        } catch (Exception e) {
            send(ex, 400, "text/plain", "Invalid params");
            return;
        }
        if (safeName.isEmpty()) { send(ex, 400, "text/plain", "Invalid filename"); return; }
        if (!mergeInProgress.add(safeName)) { send(ex, 409, "text/plain", "Busy"); return; }

        File merged = new File(tempDir, safeName + ".merged");
        try {
            File dest = new File(fileService.getFolder(), safeName);
            if (!dest.getCanonicalPath().startsWith(fileService.getFolder().getCanonicalPath())) {
                send(ex, 400, "text/plain", "Invalid filename");
                return;
            }

            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(merged))) {
                for (int i = 0; i < totalChunks; i++) {
                    File part = new File(tempDir, safeName + ".part" + i);
                    if (!part.exists()) throw new IOException("Missing part " + i);

                    long partSize = part.length();
                    Files.copy(part.toPath(), out);
                    if (part.delete()) tmpDirBytes.addAndGet(-partSize);

                    if (merged.length() + tmpDirBytes.get() > MAX_TMP_BYTES) {
                        throw new IOException("Merge exceeds disk budget");
                    }
                }
            }

            Files.move(merged.toPath(), dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            session.broadcastFilesChanged();
            send(ex, 200, "text/plain", "DONE");

        } catch (Exception e) {
            AppLogger.error("Merge error for " + safeName, e);
            // FIX (#2): clean up partial .merged file so it doesn't leak disk space
            if (merged.exists()) {
                long sz = merged.length();
                if (merged.delete()) tmpDirBytes.addAndGet(-sz);
            }
            send(ex, 500, "text/plain", "Merge failed");
        } finally {
            // FIX (#4): safeName is always valid here — no risk of NPE
            mergeInProgress.remove(safeName);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Upload — simple multipart (legacy path)
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleUpload(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(this::handleUpload); return; }
        if (!session.isUploadAllowed()) { jsonResp(ex, false, "Upload not permitted by host"); return; }

        String cl = ex.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
        if (cl != null) {
            try {
                if (Long.parseLong(cl.trim()) > MAX_UPLOAD_BYTES) {
                    jsonResp(ex, false, "File too large — maximum is 1 GB");
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        try {
            FormData form = parseForm(ex);
            if (form == null) { jsonResp(ex, false, "No file received"); return; }

            int saved = 0;
            for (String fieldName : form) {
                for (FormData.FormValue fv : form.get(fieldName)) {
                    if (!fv.isFileItem()) continue;
                    String safeName = new File(fv.getFileName()).getName();
                    File   dest     = new File(fileService.getFolder(), safeName);
                    if (!dest.getCanonicalPath()
                            .startsWith(fileService.getFolder().getCanonicalPath())) {
                        AppLogger.info("Path traversal blocked: " + fv.getFileName());
                        jsonResp(ex, false, "Invalid filename");
                        return;
                    }
                    Files.copy(fv.getFileItem().getFile(),
                            dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    AppLogger.info("Uploaded: " + dest.getName());
                    saved++;
                }
            }
            if (saved > 0) session.broadcastFilesChanged();
            jsonResp(ex, saved > 0, saved + " file(s) uploaded");
        } catch (Exception e) {
            AppLogger.error("Upload error", e);
            jsonResp(ex, false, "Upload failed");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Upload status
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleUploadStatus(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> handleUploadStatus(ex)); return; }

        String nameParam = getQueryParam(ex, "name");
        if (nameParam == null) { send(ex, 400, "text/plain", "Missing name"); return; }

        String safeName = new File(nameParam).getName();
        if (safeName.isEmpty()) { send(ex, 400, "text/plain", "Invalid filename"); return; }

        List<Integer> completed = new ArrayList<>();
        File[] parts = tempDir.listFiles((_, n) -> n.startsWith(safeName + ".part"));
        if (parts != null) {
            for (File p : parts) {
                try {
                    if (!p.getCanonicalPath().startsWith(tempDir.getCanonicalPath())) continue;
                } catch (IOException e) { continue; }

                String pName  = p.getName();
                String suffix = pName.substring(safeName.length() + ".part".length());
                if (!suffix.matches("\\d+")) continue;
                try { completed.add(Integer.parseInt(suffix)); }
                catch (NumberFormatException ignored) {}
            }
        }
        Collections.sort(completed);

        // FIX (#11): build integer array JSON properly, not via ArrayList.toString()
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < completed.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(completed.get(i));
        }
        sb.append(']');
        send(ex, 200, "application/json", "{\"completed\":" + sb + "}");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // File checksum
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleFileChecksum(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> handleFileChecksum(ex)); return; }
        try {
            String nameParam = getQueryParam(ex, "name");
            if (nameParam == null) { send(ex, 400, "text/plain", "Missing filename"); return; }

            String name = new File(nameParam).getName();
            if (name.isEmpty()) { send(ex, 400, "text/plain", "Invalid filename"); return; }

            File base = fileService.getFolder();
            File file = new File(base, name);

            if (!file.getCanonicalPath().startsWith(base.getCanonicalPath())) {
                send(ex, 403, "text/plain", "Invalid path"); return;
            }
            if (!file.exists()) { send(ex, 404, "text/plain", "Not found"); return; }
            if (file.length() > MAX_CHECKSUM_BYTES) {
                send(ex, 413, "application/json", "{\"error\":\"File too large for checksum\"}");
                return;
            }

            send(ex, 200, "application/json",
                    "{\"checksum\":" + jsonString(calculateSHA256(file)) + "}");
        } catch (Exception e) {
            AppLogger.error("Checksum failed", e);
            send(ex, 500, "application/json", "{}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Events / settings
    // ══════════════════════════════════════════════════════════════════════════════

    private void handleSettings(HttpServerExchange ex) {
        String json = "{\"v\":"      + session.getVersion()
                    + ",\"upload\":" + session.isUploadAllowed()
                    + ",\"exec\":"   + session.isExecAllowed() + "}";
        send(ex, 200, "application/json", json);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Page rendering
    // ══════════════════════════════════════════════════════════════════════════════

    private void serveIndex(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> serveIndex(ex)); return; }
        try {
            List<Map<String, Object>> files = fileService.listFiles(session.isExecAllowed());
            String json  = buildFileListJson(files);
            String nonce = freshNonce();
            String html  = templateService.render("index", new HashMap<>());
            html = html.replace("</body>",
                    "<script id=\"ws-data\" type=\"application/json\">"
                    + "{\"uploadAllowed\":" + session.isUploadAllowed()
                    + ",\"files\":"         + json + "}"
                    + "</script></body>");
            addSecurityHeaders(ex, nonce);
            ex.setStatusCode(200);
            ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
            ex.getResponseSender().send(html);
        } catch (Exception e) {
            AppLogger.error("serveIndex error", e);
            send(ex, 500, "text/plain", "Internal error");
        }
    }

    // FIX (#13): duplicate method removed — only one serveKeyPage exists here.
    private void serveKeyPage(HttpServerExchange ex) {
        ClientOS os                 = detectOS(ex);
        String   certInstallSection = buildCertInstallSection(os);
        // FIX (#9): fresh nonce per response, not a static field
        String   nonce              = freshNonce();

        String html =
            "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>WebShare</title>"
            + "<style>"
            + "*{box-sizing:border-box;margin:0;padding:0}"
            + "body{background:#0c0d10;color:#e8eaf0;font-family:system-ui,-apple-system,sans-serif;"
            + "min-height:100vh;display:flex;align-items:flex-start;"
            + "justify-content:center;padding:28px 16px;}"
            + ".card{background:#13141a;border:1px solid #2a2d3a;border-radius:16px;"
            + "padding:32px 28px;width:100%;max-width:400px;}"
            + "@media(min-width:800px){"
            + "body{align-items:center;}"
            + ".card{display:flex;flex-direction:row;align-items:stretch;"
            + "max-width:860px;padding:0;overflow:hidden;}"
            + ".col-left{flex:0 0 360px;padding:48px 40px;"
            + "border-right:1px solid #2a2d3a;"
            + "display:flex;flex-direction:column;justify-content:center;}"
            + ".col-right{flex:1 1 auto;padding:40px 36px;"
            + "overflow-y:auto;max-height:90vh;}"
            + ".divider{display:none;}"
            + ".collapsible{display:none;}"
            + ".panel{max-height:none !important;overflow:visible !important;}"
            + "}"
            + ".logo-wrap{display:flex;align-items:center;justify-content:center;"
            + "gap:10px;margin-bottom:4px;}"
            + ".logo-img{width:40px;height:40px;border-radius:10px;object-fit:contain;}"
            + ".logo-text{font-family:monospace;font-size:18px;font-weight:700;}"
            + ".logo-text .w{color:#e8eaf0;}.logo-text .s{color:#4fffb0;}"
            + ".tagline{color:#6b7280;font-family:monospace;font-size:9px;"
            + "letter-spacing:1.5px;margin-bottom:24px;text-align:center;}"
            + ".key-hint{color:#6b7280;font-size:12px;margin-bottom:10px;text-align:center;}"
            + "input[type=password]{width:100%;background:#1a1c24;border:1px solid #2a2d3a;"
            + "border-radius:10px;padding:14px;color:#e8eaf0;"
            + "font-family:monospace;font-size:22px;text-align:center;"
            + "letter-spacing:10px;outline:none;margin-bottom:12px;}"
            + "input[type=password]:focus{border-color:#4fffb0;}"
            + "input[type=password]::placeholder{color:#2a2d3a;letter-spacing:6px;}"
            + ".submit-btn{width:100%;background:rgba(78,255,176,0.1);"
            + "border:1px solid rgba(78,255,176,0.4);color:#4fffb0;"
            + "border-radius:10px;padding:12px;font-family:monospace;"
            + "font-size:12px;font-weight:700;cursor:pointer;letter-spacing:1px;}"
            + ".submit-btn:hover{background:rgba(78,255,176,0.18);}"
            + ".err{color:#ff6b6b;font-size:11px;font-family:monospace;"
            + "margin-top:8px;text-align:center;display:none;}"
            + ".divider{border:none;border-top:1px solid #2a2d3a;margin:22px 0;}"
            + ".collapsible{width:100%;background:transparent;border:1px solid #2a2d3a;"
            + "border-radius:10px;padding:12px 16px;cursor:pointer;"
            + "display:flex;align-items:center;justify-content:space-between;gap:8px;"
            + "transition:border-color .2s,background .2s;}"
            + ".collapsible:hover{border-color:#4d9fff;background:rgba(77,159,255,0.04);}"
            + ".collapsible-left{display:flex;align-items:center;gap:10px;}"
            + ".collapsible-icon{font-size:14px;}"
            + ".collapsible-label{font-family:monospace;font-size:11px;"
            + "color:#6b7280;font-weight:700;letter-spacing:.5px;text-align:left;}"
            + ".collapsible-sublabel{font-size:10px;color:#4a5060;margin-top:2px;text-align:left;}"
            + ".chevron{color:#4a5060;font-size:12px;transition:transform .25s;flex-shrink:0;}"
            + ".chevron.open{transform:rotate(180deg);}"
            + ".panel{overflow:hidden;max-height:0;transition:max-height .35s ease;}"
            + ".panel.open{max-height:600px;}"
            + ".panel-inner{padding:16px 0 4px 0;}"
            + ".dl-btn{display:flex;align-items:center;justify-content:center;gap:8px;"
            + "background:rgba(78,255,176,0.08);border:1px solid rgba(78,255,176,0.28);"
            + "color:#4fffb0;border-radius:8px;padding:11px 14px;"
            + "font-family:monospace;font-size:11px;font-weight:700;"
            + "text-decoration:none;margin-bottom:16px;transition:background .2s;}"
            + ".dl-btn:hover{background:rgba(78,255,176,0.16);}"
            + ".dl-icon{font-size:14px;}"
            + ".steps-label{font-family:monospace;font-size:10px;"
            + "color:#4a5060;letter-spacing:1px;text-transform:uppercase;margin-bottom:10px;}"
            + ".steps{list-style:none;margin:0;padding:0;}"
            + ".steps li{display:flex;gap:9px;align-items:flex-start;"
            + "padding:4px 0;border-bottom:1px solid #1e2130;}"
            + ".steps li:last-child{border-bottom:none;}"
            + ".step-num{font-family:monospace;font-size:10px;"
            + "color:#4fffb0;flex-shrink:0;min-width:16px;margin-top:2px;}"
            + ".step-text{font-size:11px;color:#8892a0;line-height:1.75;}"
            + ".step-text b{color:#c0c8d8;}"
            + ".step-warn .step-num{color:#f59e0b;}"
            + ".step-warn .step-text{color:#806040;}"
            + ".step-warn .step-text b{color:#a07848;}"
            + ".trust-note{font-size:10px;color:#3a4255;margin-top:12px;line-height:1.6;"
            + "padding:10px 12px;background:#0f1018;border-radius:6px;}"
            + ".trust-note b{color:#4a5568;}"
            + "</style></head><body>"
            + "<div class='card'>"
            + "<div class='col-left'>"
            + "<div class='logo-wrap'>"
            + "<img src='/logo' class='logo-img' alt='' onerror=\"this.style.display='none'\">"
            + "<div class='logo-text'><span class='w'>Web</span><span class='s'>Share</span></div>"
            + "</div>"
            + "<div class='tagline'>LOCAL FILE SERVER</div>"
            + "<div class='key-hint'>Enter the access key shown on the host device</div>"
            + "<form method='POST' action='/auth' onsubmit=\""
            + "var b=this.querySelector('.submit-btn');"
            + "b.disabled=true;b.textContent='VERIFYING\u2026';\">"
            + "<input type='password' name='key' maxlength='6' placeholder='······'"
            + " oninput=\"this.value=this.value.replace(/\\D/g,'')\" autofocus>"
            + "<button type='submit' class='submit-btn'>ACCESS \u2192</button>"
            + "</form>"
            + "<div class='err' id='err'></div>"
            + "</div>"
            + "<hr class='divider'>"
            + "<div class='col-right'>"
            + certInstallSection
            + "</div>"
            + "</div>"
            // FIX (#9): nonce is fresh per-response
            + "<script nonce='" + nonce + "'>"
            + "if(window.location.search.includes('invalid')){"
            + "var e=document.getElementById('err');"
            + "if(e){e.style.display='block';e.textContent='Invalid key \u2014 try again';}"
            + "history.replaceState(null,'','/');"
            + "}"
            + "function togglePanel(){"
            + "var p=document.getElementById('certPanel');"
            + "var c=document.getElementById('chevron');"
            + "var open=p.classList.toggle('open');"
            + "c.classList.toggle('open',open);"
            + "localStorage.setItem('wsCertOpen',open?'1':'0');}"
            + "(function(){"
            + "var stored=localStorage.getItem('wsCertOpen');"
            + "if(stored===null||stored==='1'){"
            + "document.getElementById('certPanel').classList.add('open');"
            + "document.getElementById('chevron').classList.add('open');}"
            + "})();"
            + "(function(){"
            + "var btn=document.querySelector('.collapsible');"
            + "if(!btn)return;"  // guard: btn may not exist on desktop layout
            + "function sync(){btn.disabled=window.innerWidth>=800;}"
            + "sync();"
            + "window.addEventListener('resize',sync);"
            + "})();"
            + "</script>"
            + "</body></html>";

        // FIX (#8): addSecurityHeaders called exactly once here; send() is NOT used
        // for HTML pages that need a nonce in the CSP — we write the response directly.
        addSecurityHeaders(ex, nonce);
        ex.setStatusCode(200);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        ex.getResponseSender().send(html);
    }

    private void serveFileListJson(HttpServerExchange ex) {
        List<Map<String, Object>> files = fileService.listFiles(session.isExecAllowed());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> f = files.get(i);
            sb.append("{\"name\":").append(jsonString(f.get("name").toString()))
              .append(",\"size\":").append(f.get("size")).append("}");
            if (i < files.size() - 1) sb.append(",");
        }
        sb.append("]");
        send(ex, 200, "application/json", sb.toString());
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Certificate
    // ══════════════════════════════════════════════════════════════════════════════

    private void serveCert(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> serveCert(ex)); return; }
        try {
            File jks = new File(Utility.getJksPath());
            if (!jks.exists()) { notFound(ex); return; }

            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(jks)) {
                ks.load(fis, Utility.JKS_PASSWORD.toCharArray());
            }

            java.security.cert.Certificate cert = ks.getCertificate("webshare");
            if (cert == null) { notFound(ex); return; }

            byte[] der = cert.getEncoded();
            String b64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
            String pem = "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";

            String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
            if (ua == null) ua = "";
            ua = ua.toLowerCase();

            HeaderMap h = ex.getResponseHeaders();
            h.put(Headers.CACHE_CONTROL, "no-store");

            if (ua.contains("iphone") || ua.contains("ipad")) {
                h.put(Headers.CONTENT_DISPOSITION,
                        "attachment; filename=\"WebShare-Trust.mobileconfig\"");
                // FIX (#6): XML-escape the IP before embedding in XML
                send(ex, 200, "application/x-apple-aspen-config",
                        buildMobileconfig(der, xmlEscape(Utility.getLocalIp())));
            } else if (ua.contains("android")) {
                h.put(Headers.CONTENT_DISPOSITION, "attachment; filename=\"WebShare-Trust.pem\"");
                send(ex, 200, "application/x-pem-file", pem);
            } else if (ua.contains("windows")) {
                h.put(Headers.CONTENT_DISPOSITION, "attachment; filename=\"WebShare-Trust.crt\"");
                send(ex, 200, "application/x-x509-ca-cert", pem);
            } else {
                h.put(Headers.CONTENT_DISPOSITION, "attachment; filename=\"WebShare-Trust.pem\"");
                send(ex, 200, "application/x-pem-file", pem);
            }
        } catch (Exception e) {
            AppLogger.error("Could not export certificate", e);
            notFound(ex);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Static assets
    // ══════════════════════════════════════════════════════════════════════════════

    private void serveLogo(HttpServerExchange ex) {
        if (ex.isInIoThread()) { ex.dispatch(() -> serveLogo(ex)); return; }
        try {
            InputStream is = getClass().getResourceAsStream("/logo.png");
            if (is == null) { notFound(ex); return; }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders()
                    .put(Headers.CONTENT_TYPE,   "image/png")
                    .put(Headers.CONTENT_LENGTH, (long) bytes.length)
                    .put(Headers.CACHE_CONTROL,  "max-age=86400");
            ex.setStatusCode(200);
            ex.getResponseSender().send(ByteBuffer.wrap(bytes));
        } catch (Exception e) { notFound(ex); }
    }

    private void serveStaticJs(HttpServerExchange ex) {
        try {
            InputStream is = getClass().getResourceAsStream("/static/js/webshare.js");
            if (is == null) { notFound(ex); return; }
            String js = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
            send(ex, 200, "application/javascript", js);
        } catch (Exception e) { notFound(ex); }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // SSL
    // ══════════════════════════════════════════════════════════════════════════════

    private SSLContext buildSslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(Utility.getJksPath())) {
            ks.load(fis, Utility.JKS_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, Utility.JKS_PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return new TlsRestrictedSSLContext(ctx);
    }

    private static class TlsRestrictedSSLContext extends SSLContext {
        TlsRestrictedSSLContext(SSLContext delegate) throws Exception {
            super(new SSLContextSpi() {
                @Override protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sr) {}
                @Override protected SSLSocketFactory       engineGetSocketFactory()       { return delegate.getSocketFactory(); }
                @Override protected SSLServerSocketFactory engineGetServerSocketFactory() { return delegate.getServerSocketFactory(); }
                @Override protected SSLEngine engineCreateSSLEngine() {
                    SSLEngine e = delegate.createSSLEngine();
                    e.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
                    return e;
                }
                @Override protected SSLEngine engineCreateSSLEngine(String h, int p) {
                    SSLEngine e = delegate.createSSLEngine(h, p);
                    e.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
                    return e;
                }
                @Override protected SSLSessionContext engineGetServerSessionContext() { return delegate.getServerSessionContext(); }
                @Override protected SSLSessionContext engineGetClientSessionContext() { return delegate.getClientSessionContext(); }
            }, delegate.getProvider(), "TLS");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // HTML / XML builders
    // ══════════════════════════════════════════════════════════════════════════════

    private String buildFileListJson(List<Map<String, Object>> files) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> f : files) {
            String name = String.valueOf(f.get("name"));
            if (name.startsWith(".") || name.contains(".part") || name.endsWith(".merged")) continue;
            Object sizeObj = f.get("size");
            long   size    = (sizeObj instanceof Number) ? ((Number) sizeObj).longValue() : 0L;
            if (!first) sb.append(",");
            sb.append("{\"name\":").append(jsonString(name))
              .append(",\"size\":").append(size).append("}");
            first = false;
        }
        return sb.append("]").toString();
    }

    private String buildMobileconfig(byte[] der, String safeIp) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
            + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>\n"
            + "  <key>PayloadContent</key><array><dict>\n"
            + "    <key>PayloadCertificateFileName</key><string>WebShare.cer</string>\n"
            + "    <key>PayloadContent</key><data>"
            + java.util.Base64.getEncoder().encodeToString(der) + "</data>\n"
            + "    <key>PayloadDescription</key><string>WebShare Trust Certificate</string>\n"
            + "    <key>PayloadDisplayName</key><string>WebShare (" + safeIp + ")</string>\n"
            + "    <key>PayloadIdentifier</key><string>com.webshare.cert</string>\n"
            + "    <key>PayloadType</key><string>com.apple.security.root</string>\n"
            + "    <key>PayloadUUID</key><string>" + UUID.randomUUID() + "</string>\n"
            + "    <key>PayloadVersion</key><integer>1</integer>\n"
            + "  </dict></array>\n"
            + "  <key>PayloadDescription</key><string>Trusts WebShare local server</string>\n"
            + "  <key>PayloadDisplayName</key><string>WebShare Certificate</string>\n"
            + "  <key>PayloadIdentifier</key><string>com.webshare.profile</string>\n"
            + "  <key>PayloadRemovalDisallowed</key><false/>\n"
            + "  <key>PayloadType</key><string>Configuration</string>\n"
            + "  <key>PayloadUUID</key><string>" + UUID.randomUUID() + "</string>\n"
            + "  <key>PayloadVersion</key><integer>1</integer>\n"
            + "</dict></plist>";
    }

    private String buildCertInstallSection(ClientOS os) {
        String osLabel, fileExt, steps;
        switch (os) {
        case ANDROID:
            osLabel = "Android"; fileExt = ".pem";
            steps = "<li class='step-warn'><span class='step-num'>!</span>"
                  + "<span class='step-text'><b>Screen lock required.</b> Android will not install "
                  + "CA certificates without a PIN, pattern, or password set.</span></li>"
                  + "<li><span class='step-num'>1</span><span class='step-text'>Tap <b>Download Certificate</b> below</span></li>"
                  + "<li><span class='step-num'>2</span><span class='step-text'>Open <b>Settings &rarr; Security</b><br>"
                  + "&rsaquo; Pixel: <b>More security settings &rarr; Install from storage</b><br>"
                  + "&rsaquo; Samsung: <b>More security settings &rarr; Install from device storage</b><br>"
                  + "&rsaquo; Others: <b>Encryption &amp; credentials &rarr; Install a certificate &rarr; CA certificate</b></span></li>"
                  + "<li><span class='step-num'>3</span><span class='step-text'>Select the downloaded <b>.pem</b> file</span></li>"
                  + "<li><span class='step-num'>4</span><span class='step-text'>Name it <b>WebShare</b> &rarr; tap <b>CA Certificate &rarr; Install anyway</b></span></li>"
                  + "<li><span class='step-num'>5</span><span class='step-text'>Restart Chrome</span></li>";
            break;
        case IOS:
            osLabel = "iPhone / iPad"; fileExt = ".mobileconfig";
            steps = "<li><span class='step-num'>1</span><span class='step-text'>Tap <b>Download Certificate</b> below &rarr; tap <b>Allow</b></span></li>"
                  + "<li><span class='step-num'>2</span><span class='step-text'>Open <b>Settings</b> &rarr; tap <b>Profile Downloaded</b> &rarr; Install &rarr; enter passcode</span></li>"
                  + "<li><span class='step-num'>3</span><span class='step-text'>Settings &rarr; General &rarr; About &rarr; <b>Certificate Trust Settings</b></span></li>"
                  + "<li><span class='step-num'>4</span><span class='step-text'>Toggle <b>WebShare</b> on &rarr; Done</span></li>";
            break;
        case MAC:
            osLabel = "Mac"; fileExt = ".pem";
            steps = "<li><span class='step-num'>1</span><span class='step-text'>Click <b>Download Certificate</b> below</span></li>"
                  + "<li><span class='step-num'>2</span><span class='step-text'>Double-click the <b>.pem</b> file &mdash; Keychain Access opens</span></li>"
                  + "<li><span class='step-num'>3</span><span class='step-text'>Add to the <b>System</b> keychain</span></li>"
                  + "<li><span class='step-num'>4</span><span class='step-text'>Find <b>WebShare</b> &rarr; double-click &rarr; expand <b>Trust</b> &rarr; set <b>Always Trust</b></span></li>"
                  + "<li><span class='step-num'>5</span><span class='step-text'>Enter your password &rarr; Restart browser</span></li>";
            break;
        default:
            osLabel = "Windows"; fileExt = ".crt";
            steps = "<li><span class='step-num'>1</span><span class='step-text'>Click <b>Download Certificate</b> below</span></li>"
                  + "<li><span class='step-num'>2</span><span class='step-text'>Double-click the downloaded <b>.crt</b> file</span></li>"
                  + "<li><span class='step-num'>3</span><span class='step-text'>Click <b>Install Certificate &rarr; Local Machine &rarr; Next</b></span></li>"
                  + "<li><span class='step-num'>4</span><span class='step-text'>Select <b>Trusted Root Certification Authorities &rarr; Next &rarr; Finish</b></span></li>"
                  + "<li><span class='step-num'>5</span><span class='step-text'>Restart your browser</span></li>";
            break;
        }
        return "<button class='collapsible' onclick='togglePanel()' type='button'>"
             + "<div class='collapsible-left'><span class='collapsible-icon'>\uD83D\uDD12</span>"
             + "<div><div class='collapsible-label'>Make connection permanently trusted</div>"
             + "<div class='collapsible-sublabel'>Install certificate once &mdash; no more warnings</div>"
             + "</div></div><span class='chevron' id='chevron'>&#9660;</span></button>"
             + "<div class='panel' id='certPanel'><div class='panel-inner'>"
             + "<a href='/cert' class='dl-btn'><span class='dl-icon'>&#11015;</span>"
             + "<span>Download Certificate (" + fileExt + ") &middot; " + osLabel + "</span></a>"
             + "<div class='steps-label'>Installation steps</div>"
             + "<ul class='steps'>" + steps + "</ul>"
             + "<div class='trust-note'>Installing this certificate makes your browser trust WebShare. "
             + "<b>Your connection is already encrypted</b> &mdash; this step only removes the browser notice.</div>"
             + "</div></div>";
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════════════

    private ClientOS detectOS(HttpServerExchange ex) {
        String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
        if (ua == null) return ClientOS.WINDOWS;
        ua = ua.toLowerCase();
        if (ua.contains("iphone") || ua.contains("ipad"))        return ClientOS.IOS;
        if (ua.contains("android"))                              return ClientOS.ANDROID;
        if (ua.contains("macintosh") || ua.contains("mac os x")) return ClientOS.MAC;
        return ClientOS.WINDOWS;
    }

    private String getQueryParam(HttpServerExchange ex, String key) {
        Deque<String> d = ex.getQueryParameters().get(key);
        return (d == null || d.isEmpty()) ? null : d.getFirst();
    }

    private void jsonResp(HttpServerExchange ex, boolean ok, String msg) {
        send(ex, ok ? 200 : 500, "application/json",
                "{\"ok\":" + ok + ",\"message\":" + jsonString(msg) + "}");
    }

    /**
     * FIX (#12): properly escapes ALL JSON special characters including tab
     * and all other control characters below 0x20.
     */
    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** FIX (#6): XML-escapes user-controlled strings before embedding in XML. */
    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** FIX (#9): generates a cryptographically random nonce for each response. */
    private String freshNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private String calculateSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[4 * 1024 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
        }
        byte[]        hash = digest.digest();
        StringBuilder hex  = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private String getLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return "127.0.0.1"; }
    }

    private FormData parseForm(HttpServerExchange ex) {
        try {
            FormParserFactory.Builder b = FormParserFactory.builder();
            FormDataParser parser = b.build().createParser(ex);
            return (parser != null) ? parser.parseBlocking() : null;
        } catch (Exception e) { return null; }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Response primitives
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * FIX (#8): security headers applied exactly once per response.
     * HTML pages that need a matching nonce call addSecurityHeaders(ex, nonce)
     * directly and write the response themselves.
     * All other responses go through send() which generates a throwaway nonce
     * (harmless — it just appears in a CSP header on a non-HTML response).
     */
    private void addSecurityHeaders(HttpServerExchange ex, String nonce) {
        HeaderMap h = ex.getResponseHeaders();
        h.put(new HttpString("X-Content-Type-Options"), "nosniff");
        h.put(new HttpString("X-Frame-Options"),        "DENY");
        h.put(new HttpString("Referrer-Policy"),        "strict-origin-when-cross-origin");
        h.put(new HttpString("Content-Security-Policy"),
                "default-src 'self'; "
                + "script-src 'self' 'nonce-" + nonce + "'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:;");
    }

    private void send(HttpServerExchange ex, int status, String mime, String body) {
        ex.setStatusCode(status);
        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, mime + "; charset=UTF-8");
        addSecurityHeaders(ex, freshNonce());
        ex.getResponseSender().send(body);
    }

    private void redirect(HttpServerExchange ex, String loc) {
        ex.setStatusCode(302);
        ex.getResponseHeaders().put(Headers.LOCATION, loc);
        ex.endExchange();
    }

    private void forbidden(HttpServerExchange ex)       { send(ex, 403, "text/plain", "Forbidden"); }
    private void notFound(HttpServerExchange ex)        { send(ex, 404, "text/plain", "Not Found"); }
    private void tooManyRequests(HttpServerExchange ex) { send(ex, 429, "text/plain", "Too Many Requests"); }
}

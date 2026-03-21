package com.webshare.service;

import com.webshare.util.AppLogger;
import fi.iki.elonen.NanoHTTPD;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetAddress;
import java.net.URLConnection;
import java.security.KeyStore;
import java.util.*;

public class WebShareServer extends NanoHTTPD {

    private final FileService     fileService;
    private final TemplateService templateService;
    private final SessionManager  session;
    private final RateLimiter     rateLimiter = new RateLimiter();

    public WebShareServer(int port, File folder, SessionManager session) {
        super(port);
        try {
            this.fileService = new FileService(folder);
        } catch (IOException e) {
            throw new RuntimeException("Invalid share folder: " + folder.getAbsolutePath(), e);
        }
        this.templateService = new TemplateService();
        this.session         = session;

        // Large thread pool — handles many concurrent connections without blocking
        // Default NanoHTTPD pool is too small (8 threads) causing slowness
        setAsyncRunner(new BoundRunner(
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "nanohttpd-worker");
                t.setDaemon(true);
                return t;
            })
        ));
    }

    /**
     * Custom async runner using a proper thread pool.
     * CachedThreadPool creates threads on demand and reuses idle ones —
     * perfect for a local file server with bursty traffic.
     */
    private static class BoundRunner implements AsyncRunner {
        private final java.util.concurrent.ExecutorService exec;
        private final java.util.List<ClientHandler> running =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        BoundRunner(java.util.concurrent.ExecutorService exec) {
            this.exec = exec;
        }

        @Override
        public void closeAll() {
            for (ClientHandler c : running) c.close();
        }

        @Override
        public void closed(ClientHandler c) {
            running.remove(c);
        }

        @Override
        public void exec(ClientHandler c) {
            running.add(c);
            exec.submit(c);
        }
    }

    public SessionManager getSession() { return session; }

    // ══════════════════════════════════════════════════════════════════════
    // Server start / stop
    // ══════════════════════════════════════════════════════════════════════

    public String startServer() {
        try {
            enableHttps();
            super.start(2000, false); // 2s socket timeout — faster dead connection detection
            String ip   = InetAddress.getLocalHost().getHostAddress();
            int    port = getListeningPort();
            AppLogger.info("Server started — https://" + ip + ":" + port);
            return "https://" + ip + ":" + port;
        } catch (Exception e) {
            AppLogger.error("Failed to start server", e);
            return "Failed to Start Server";
        }
    }

    /**
     * Loads webshare.jks from the working directory and enables TLS.
     * Generate once with:
     *   keytool -genkeypair -keyalg RSA -keysize 2048 -validity 3650
     *     -alias webshare -keystore webshare.jks
     *     -storepass changeit -keypass changeit
     *     -dname "CN=WebShare,OU=Local,O=Local,L=Local,ST=Local,C=US"
     */
    private void enableHttps() throws Exception {
        File jks = new File(com.webshare.util.Utility.getJksPath());
        if (!jks.exists()) {
            AppLogger.info("webshare.jks not found — running on plain HTTP");
            return;
        }
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(jks)) {
            ks.load(fis, com.webshare.util.Utility.JKS_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, com.webshare.util.Utility.JKS_PASSWORD.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        // Use SSLServerSocketFactory with session caching enabled
        // This means subsequent TLS connections from same device reuse
        // the handshake — much faster after the first connection
        javax.net.ssl.SSLServerSocketFactory ssf =
            (javax.net.ssl.SSLServerSocketFactory) ctx.getServerSocketFactory();

        makeSecure(ssf, new String[]{"TLSv1.2", "TLSv1.3"});
        AppLogger.info("TLS enabled — TLSv1.2/1.3 with session caching.");
    }

    public void stopServer() {
        super.stop();
        session.clearSessions();
        AppLogger.info("Server stopped.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Request router
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public Response serve(IHTTPSession req) {
        String uri    = req.getUri();
        Method method = req.getMethod();
        AppLogger.info(method + " " + uri);

        try {
            // ── Public endpoints (no auth needed) ─────────────────────────
            if (uri.equals("/logo"))   return addSecurityHeaders(serveLogo());
            if (uri.equals("/js/webshare.js")) return addSecurityHeaders(serveStaticJs());
            if (uri.equals("/events")) return handleSSE(req);          // SSE — auth checked inside

            // ── Auth endpoints ─────────────────────────────────────────────
            if (method == Method.POST && uri.equals("/auth"))
                return handleAuth(req);

            if (uri.equals("/logout"))
                return handleLogout(req);

            // ── Main page ──────────────────────────────────────────────────
            if (uri.equals("/") || uri.equals("/index")) {
                if (!isAuthorized(req)) return addSecurityHeaders(serveKeyPage(false));
                return addSecurityHeaders(serveIndex());
            }

            // ── File download ──────────────────────────────────────────────
            if (uri.startsWith("/files/")) {
                if (!isAuthorized(req)) return addSecurityHeaders(forbidden());
                return addSecurityHeaders(serveFile(uri));
            }

            // ── Upload ─────────────────────────────────────────────────────
            if (method == Method.POST && uri.equals("/upload")) {
                if (!isAuthorized(req))          return addSecurityHeaders(forbidden());
                if (!session.isUploadAllowed())  return addSecurityHeaders(uploadDenied());
                return addSecurityHeaders(handleUpload(req));
            }

            // ── API: file list JSON ────────────────────────────────────────
            if (uri.equals("/api/files")) {
                if (!isAuthorized(req)) return addSecurityHeaders(forbidden());
                return addSecurityHeaders(serveFileListJson());
            }

            return addSecurityHeaders(notFound());

        } catch (SecurityException se) {
            return addSecurityHeaders(forbidden());
        } catch (Exception e) {
            AppLogger.error("Error: " + uri, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                "text/plain", "Internal error");   // never leak stack traces
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Auth handlers
    // ══════════════════════════════════════════════════════════════════════

    private Response handleAuth(IHTTPSession req) throws Exception {
        String ip = getClientIp(req);

        if (rateLimiter.isBlocked(ip))
            return addSecurityHeaders(tooManyRequests());

        Map<String, String> body = new HashMap<>();
        req.parseBody(body);
        List<String> vals = req.getParameters().getOrDefault("key", List.of());
        String key = vals.isEmpty() ? "" : vals.get(0);

        if (!session.checkKey(key)) {
            rateLimiter.recordFail(ip);
            AppLogger.info("Failed auth attempt from " + ip
                + " (" + rateLimiter.failCount(ip) + " fails)");
            // Redirect back — don't say whether key was wrong vs. blocked
            Response r = newFixedLengthResponse(
                Response.Status.REDIRECT, "text/plain", "");
            r.addHeader("Location", "/?invalid=1");
            return addSecurityHeaders(r);
        }

        rateLimiter.reset(ip);
        String token = session.createSession();
        Response r = newFixedLengthResponse(
            Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", "/");
        r.addHeader("Set-Cookie", sessionCookie(token));
        return addSecurityHeaders(r);
    }

    private Response handleLogout(IHTTPSession req) {
        String sid = extractSid(req);
        if (sid != null) session.revokeSession(sid);
        Response r = newFixedLengthResponse(
            Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", "/");
        r.addHeader("Set-Cookie", "sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
        return addSecurityHeaders(r);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Auth helpers
    // ══════════════════════════════════════════════════════════════════════

    private boolean isAuthorized(IHTTPSession req) {
        String sid = extractSid(req);
        return sid != null && session.checkSession(sid);
    }

    private String extractSid(IHTTPSession req) {
        String cookie = req.getHeaders().getOrDefault("cookie", "");
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals("sid"))
                return kv[1].trim();
        }
        return null;
    }

    private String sessionCookie(String token) {
        // Max-Age=1800 → 30-minute session
        return "sid=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800";
    }

    private String getClientIp(IHTTPSession req) {
        String xff = req.getHeaders().get("x-forwarded-for");
        return (xff != null) ? xff.split(",")[0].trim()
                             : req.getRemoteIpAddress();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Page / content handlers
    // ══════════════════════════════════════════════════════════════════════

    private Response serveIndex() throws Exception {
        List<Map<String, Object>> files = fileService.listFiles(session.isExecAllowed());

        // Build JSON — Thymeleaf never touches this, injected directly into HTML
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) json.append(",");
            json.append("{\"name\":\"")
                .append(jsonEsc(String.valueOf(files.get(i).get("name"))))
                .append("\",\"size\":").append(files.get(i).get("size")).append("}");
        }
        json.append("]");

        // Render template with no variables — just gets the HTML skeleton
        Map<String, Object> vars = new HashMap<>();
        String html = templateService.render("index", vars);

        // Inject data script tag just before </body> — bypasses Thymeleaf entirely
        String dataScript =
            "<script id=\"ws-data\" type=\"application/json\">" +
            "{\"uploadAllowed\":" + session.isUploadAllowed() +
            ",\"files\":" + json + "}" +
            "</script>";
        html = html.replace("</body>", dataScript + "</body>");

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private Response serveStaticJs() {
        try {
            java.io.InputStream is = getClass().getResourceAsStream("/static/js/webshare.js");
            if (is == null) return notFound();
            String js = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Response r = newFixedLengthResponse(Response.Status.OK, "application/javascript", js);
            r.addHeader("Cache-Control", "no-store");
            return r;
        } catch (Exception e) {
            return notFound();
        }
    }

    private Response serveKeyPage(boolean invalid) {
        String errorBlock = invalid
            ? "<div class='err' style='display:block'>Invalid key — try again</div>"
            : "<div class='err' id='err'></div>";

        String html =
            "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>WebShare — Enter Key</title>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&family=DM+Sans:wght@400;500&display=swap');" +
            "*{box-sizing:border-box;margin:0;padding:0}" +
            "body{background:#0c0d10;color:#e8eaf0;font-family:'DM Sans',sans-serif;" +
            "min-height:100vh;display:flex;align-items:center;justify-content:center;}" +
            ".card{background:#13141a;border:1px solid #2a2d3a;border-radius:16px;" +
            "padding:40px 32px;width:340px;text-align:center;}" +
            ".logo-wrap{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:6px;}" +
            ".logo-img{width:44px;height:44px;border-radius:10px;object-fit:contain;}" +
            ".logo-text{font-family:'Space Mono',monospace;font-size:20px;font-weight:700;}" +
            ".logo-text .w{color:#e8eaf0;}.logo-text .s{color:#4fffb0;}" +
            ".tagline{color:#6b7280;font-family:'Space Mono',monospace;font-size:10px;" +
            "letter-spacing:1.5px;margin-bottom:28px;}" +
            "p{color:#6b7280;font-size:13px;margin-bottom:20px;}" +
            "input{width:100%;background:#1a1c24;border:1px solid #2a2d3a;" +
            "border-radius:10px;padding:14px;color:#e8eaf0;" +
            "font-family:'Space Mono',monospace;font-size:24px;text-align:center;" +
            "letter-spacing:10px;outline:none;margin-bottom:14px;}" +
            "input:focus{border-color:#4fffb0;}" +
            "input::placeholder{color:#2a2d3a;letter-spacing:6px;}" +
            "button{width:100%;background:rgba(78,255,176,0.1);" +
            "border:1px solid rgba(78,255,176,0.4);color:#4fffb0;" +
            "border-radius:10px;padding:13px;font-family:'Space Mono',monospace;" +
            "font-size:13px;font-weight:700;cursor:pointer;letter-spacing:1px;}" +
            "button:hover{background:rgba(78,255,176,0.18);}" +
            ".err{color:#ff6b6b;font-size:12px;font-family:'Space Mono',monospace;margin-top:10px;}" +
            "</style></head><body>" +
            "<div class='card'>" +
            "<div class='logo-wrap'>" +
            "<img src='/logo' class='logo-img' alt='WebShare' onerror=\"this.style.display='none'\">" +
            "<div class='logo-text'><span class='w'>Web</span><span class='s'>Share</span></div>" +
            "</div>" +
            "<div class='tagline'>LOCAL FILE SERVER</div>" +
            "<p>Enter the access key shown on the host device</p>" +
            // ── Form POSTs to /auth — key never appears in URL ────────────
            "<form method='POST' action='/auth'>" +
            "<input type='password' name='key' maxlength='6' placeholder='······'" +
            " oninput=\"this.value=this.value.replace(/\\D/g,'')\" autofocus>" +
            "<button type='submit'>ACCESS →</button>" +
            "</form>" +
            errorBlock +
            "</div>" +
            // ── Show error if redirected back with ?invalid=1 ─────────────
            "<script>" +
            "if(window.location.search.includes('invalid')){" +
            "var e=document.getElementById('err');" +
            "if(e){e.style.display='block';e.textContent='Invalid key — try again';}" +
            "history.replaceState(null,'','/');}" + // clean URL immediately
            "</script>" +
            "</body></html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SSE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * /events — polled by browser every 2 seconds.
     * Returns a tiny JSON object with current state counters.
     * Browser compares counters to detect changes — no broken pipe issues.
     */
    private Response handleSSE(IHTTPSession req) {
        if (!isAuthorized(req)) return addSecurityHeaders(forbidden());

        // Lightweight state endpoint — browser polls this every 2s
        // Returns version counters only — no held threads, no pipes
        String json = "{"
            + "\"v\":" + session.getVersion()
            + ",\"upload\":" + session.isUploadAllowed()
            + ",\"exec\":" + session.isExecAllowed()
            + "}";

        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        r.addHeader("Cache-Control", "no-store, no-cache");
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }


    // ══════════════════════════════════════════════════════════════════════
    // File / upload handlers
    // ══════════════════════════════════════════════════════════════════════

    private Response serveFile(String uri) throws Exception {
        File file = fileService.getFile(uri, session.isExecAllowed());
        if (file == null) return notFound();
        AppLogger.info("Serving: " + file.getName());
        String mime = URLConnection.guessContentTypeFromName(file.getName());
        if (mime == null) mime = "application/octet-stream";
        Response r = newChunkedResponse(Response.Status.OK, mime, new FileInputStream(file));
        // Force download — prevents the browser from executing served files
        r.addHeader("Content-Disposition",
            "attachment; filename=\"" + file.getName().replace("\"", "") + "\"");
        return r;
    }

    private Response handleUpload(IHTTPSession req) {
        try {
            Map<String, String> tmpFiles = new HashMap<>();
            req.parseBody(tmpFiles);
            Map<String, List<String>> params = req.getParameters();

            if (tmpFiles.isEmpty()) return jsonResp(false, "No file received");

            int saved = 0;
            for (Map.Entry<String, String> e : tmpFiles.entrySet()) {
                List<String> names = params.get(e.getKey());
                String originalName = (names != null && !names.isEmpty())
                    ? names.get(0) : e.getKey();

                // Strip any path traversal attempts
                String safeName = new File(originalName).getName();
                File tmp  = new File(e.getValue());
                File dest = new File(fileService.getFolder(), safeName);

                // Prevent path traversal — dest must be inside the share folder
                if (!dest.getCanonicalPath()
                        .startsWith(fileService.getFolder().getCanonicalPath())) {
                    AppLogger.info("Path traversal attempt blocked: " + originalName);
                    continue;
                }

                try (FileInputStream  in  = new FileInputStream(tmp);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                AppLogger.info("Uploaded: " + dest.getName());
                saved++;
            }

            if (saved > 0) session.broadcastFilesChanged();
            return jsonResp(saved > 0, saved + " file(s) uploaded");

        } catch (Exception e) {
            AppLogger.error("Upload error", e);
            return jsonResp(false, "Upload failed");   // don't leak exception message
        }
    }

    private Response serveLogo() {
        try {
            InputStream is = getClass().getResourceAsStream("/logo.png");
            if (is == null) return notFound();
            return newChunkedResponse(Response.Status.OK, "image/png", is);
        } catch (Exception e) {
            return notFound();
        }
    }

    private Response serveFileListJson() {
        List<Map<String, Object>> files = fileService.listFiles(session.isExecAllowed());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            Map<String, Object> f = files.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(jsonEsc(String.valueOf(f.get("name"))))
              .append("\",\"size\":").append(f.get("size")).append("}");
        }
        sb.append("]");
        Response r = newFixedLengthResponse(Response.Status.OK,
            "application/json", sb.toString());
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Security headers — added to every response
    // ══════════════════════════════════════════════════════════════════════

    private Response addSecurityHeaders(Response r) {
        r.addHeader("X-Content-Type-Options",            "nosniff");
        r.addHeader("X-Frame-Options",                   "DENY");
        r.addHeader("X-XSS-Protection",                  "1; mode=block");
        r.addHeader("Referrer-Policy",                   "no-referrer");
        r.addHeader("Cache-Control",                     "no-store");
        r.addHeader("Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "font-src https://fonts.gstatic.com; script-src 'self' 'unsafe-inline'; img-src 'self' data:;");
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Small helpers
    // ══════════════════════════════════════════════════════════════════════

    private Response jsonResp(boolean ok, String msg) {
        String json = "{\"ok\":" + ok + ",\"message\":\"" + jsonEsc(msg) + "\"}";
        return newFixedLengthResponse(
            ok ? Response.Status.OK : Response.Status.INTERNAL_ERROR,
            "application/json", json);
    }

    private Response forbidden() {
        return newFixedLengthResponse(Response.Status.FORBIDDEN,
            "text/plain", "Access denied");
    }

    private Response uploadDenied() {
        return jsonResp(false, "Upload not permitted by host");
    }

    private Response notFound() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND,
            "text/plain", "Not found");
    }

    private Response tooManyRequests() {
        return newFixedLengthResponse(Response.Status.lookup(429),
            "text/plain", "Too many failed attempts. Try again in 10 minutes.");
    }

    private String jsonEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
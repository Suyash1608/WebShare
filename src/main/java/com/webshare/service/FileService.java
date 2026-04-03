package com.webshare.service;

import com.webshare.util.AppLogger;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FileService {

    private final File   folder;
    private final String canonicalBase; // cached once — avoids repeated I/O

    // Executable extensions — blocked by default unless host enables them
    // .js is included because Windows Script Host can execute .js files directly.
    private static final Set<String> EXEC_EXTENSIONS = Set.of(
        ".exe", ".bat", ".cmd", ".ps1", ".msi", ".vbs", ".scr", ".com", ".pif",
        ".sh", ".jar", ".js", ".php", ".py", ".rb"
    );

    // /files/ prefix as a constant — substring(7) was a magic number tied to this
    private static final String FILES_PREFIX = "/files/";

    public FileService(File folder) throws IOException {
        this.folder        = folder;
        this.canonicalBase = folder.getCanonicalPath();
    }

    public File getFolder() { return folder; }

    // ── List files ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> listFiles(boolean execAllowed) {
        List<Map<String, Object>> files = new ArrayList<>();
        File[] list = folder.listFiles();
        if (list == null) {
            // null means I/O error or folder doesn't exist — log so it's visible
            AppLogger.info("listFiles returned null — folder may be inaccessible: " + folder);
            return files;
        }
        for (File f : list) {
            if (!f.isFile()) continue;
            if (!execAllowed && isExecExtension(f.getName())) continue;

            // FIX: skip symlinks that escape the share folder
            // (getFile blocks downloads of these, but listFiles would still expose the name)
            try {
                String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(canonicalBase + File.separator)
                        && !canonical.equals(canonicalBase)) {
                    AppLogger.info("Symlink escaping share folder hidden from listing: " + f.getName());
                    continue;
                }
            } catch (IOException e) {
                AppLogger.info("Could not resolve canonical path for: " + f.getName() + " — skipping");
                continue;
            }

            Map<String, Object> m = new HashMap<>();
            m.put("name", f.getName());
            m.put("size", f.length());
            files.add(m);
        }
        // Sort alphabetically for consistent UI
        files.sort(Comparator.comparing(m -> m.get("name").toString().toLowerCase()));
        return files;
    }

    // ── Get a single file safely ───────────────────────────────────────────

    public File getFile(String uri, boolean execAllowed) throws IOException {
        // Strip /files/ prefix using the constant — not a magic number
        String name = uri;
        if (name.startsWith(FILES_PREFIX))  name = name.substring(FILES_PREFIX.length());
        else if (name.startsWith("/"))      name = name.substring(1);

        // Decode URL encoding safely (UTF-8 always, never platform default).
        // Only decoded once — double-decode attacks are not possible here, but callers
        // must never pre-decode the URI before passing it in or this guarantee breaks.
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);

        // Reject null bytes — used in some path traversal attacks
        if (name.contains("\0")) {
            AppLogger.info("Null byte in filename rejected: " + uri);
            throw new SecurityException("Access denied");
        }

        // Block executable extensions unless host has enabled them
        if (!execAllowed && isExecExtension(name)) {
            AppLogger.info("Exec extension blocked (not enabled by host): " + name);
            throw new SecurityException("Access denied");
        }

        File file = new File(folder, name);

        // ── Path traversal check ───────────────────────────────────────────
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(canonicalBase + File.separator)
                && !canonical.equals(canonicalBase)) {
            AppLogger.info("Path traversal attempt blocked: " + uri);
            throw new SecurityException("Access denied");
        }

        return (file.exists() && file.isFile()) ? file : null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean isExecExtension(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : EXEC_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}

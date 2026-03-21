package com.webshare.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Central logger for WebShare.
 * Writes to console + a daily log file at ~/WebShare/logs/webshare-YYYY-MM-DD.log
 * Each day gets its own file — never overwrites previous days.
 */
public class AppLogger {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Public API ─────────────────────────────────────────────────────────

    public static void info(String message) {
        write("INFO ", message, null);
    }

    public static void warn(String message) {
        write("WARN ", message, null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    // ── Init ───────────────────────────────────────────────────────────────

    public static void init() {
        // Silence ALL NanoHTTPD noise permanently —
        // This must be done via the root java.util.logging system,
        // not just the named logger, to catch all subloggers too
        java.util.logging.Logger nanoRoot =
            java.util.logging.Logger.getLogger("fi.iki.elonen");
        nanoRoot.setLevel(java.util.logging.Level.OFF);
        nanoRoot.setUseParentHandlers(false);

        // Also silence the root logger's console handler for NanoHTTPD
        // by adding a filter that drops everything from fi.iki.elonen
        java.util.logging.Logger rootLogger =
            java.util.logging.Logger.getLogger("");
        for (java.util.logging.Handler h : rootLogger.getHandlers()) {
            h.setFilter(record -> {
                String src = record.getLoggerName();
                return src == null || !src.startsWith("fi.iki.elonen");
            });
        }

        info("===== WebShare started =====");
        info("Log folder: " + Utility.getLogFolderPath());
        info("Java      : " + System.getProperty("java.version"));
        info("OS        : " + System.getProperty("os.name")
            + " "           + System.getProperty("os.version"));
        info("Home      : " + System.getProperty("user.home"));
        info("JKS       : " + Utility.getJksPath());
        info("Share     : " + Utility.getShareFolderPath());
    }

    // ── Core writer ────────────────────────────────────────────────────────

    private static void write(String level, String message, Throwable t) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String line = "[" + timestamp + "] [" + level + "] " + message;

        // Console
        System.out.println(line);
        if (t != null) t.printStackTrace(System.err);

        // Daily log file — ~/WebShare/logs/webshare-2026-03-21.log
        try {
            File logDir = new File(Utility.getLogFolderPath());
            if (!logDir.exists()) logDir.mkdirs();

            String today    = LocalDate.now().format(DATE_FMT);
            File   logFile  = new File(logDir, "webshare-" + today + ".log");

            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println(line);
                if (t != null) {
                    t.printStackTrace(pw);
                    pw.println();
                }
            }
        } catch (Exception ignored) {
            // Can't write log — don't crash the app
        }
    }
}
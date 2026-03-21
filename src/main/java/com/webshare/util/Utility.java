package com.webshare.util;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class Utility {

    private static final int PORT_START = 8080;
    private static final int PORT_END   = 8100;

    public static final String JKS_PASSWORD = "changeit";

    private static final String BASE =
        System.getProperty("user.home") + File.separator + "WebShare";

    /** ~/WebShare/certificates/webshare.jks */
    public static String getJksPath() {
        return BASE + File.separator + "certificates" + File.separator + "webshare.jks";
    }

    /** ~/WebShare/shared/ — where shared files live */
    public static String getShareFolderPath() {
        return BASE + File.separator + "shared";
    }

    /** ~/WebShare/logs/ — daily log files */
    public static String getLogFolderPath() {
        return BASE + File.separator + "logs";
    }

    // ── Share folder ───────────────────────────────────────────────────────

    /**
     * Creates (or returns) ~/WebShare as the share folder.
     * On Unix/macOS the folder is created with 700 permissions (owner only).
     * Returns null if the folder couldn't be created.
     */
    /**
     * Creates and returns ~/WebShare/shared/ as the share folder.
     * Also ensures ~/WebShare/certificates/ and ~/WebShare/logs/ exist.
     */
    public static String createOrGetFolder() {
        // Create all required subdirectories
        String[] dirs = { BASE, getShareFolderPath(), getLogFolderPath(),
                          new File(getJksPath()).getParent() };
        for (String path : dirs) {
            File dir = new File(path);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    AppLogger.error("Could not create directory: " + path, null);
                    return null;
                }
                setPosixPermissions(dir);
                AppLogger.info("Created directory: " + path);
            } else if (!dir.isDirectory()) {
                AppLogger.error("Path exists but is not a directory: " + path, null);
                return null;
            }
        }
        return getShareFolderPath();
    }

    private static void setPosixPermissions(File folder) {
        try {
            Set<PosixFilePermission> perms =
                PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(folder.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows — POSIX not supported, skip silently
        } catch (IOException e) {
            AppLogger.error("Could not set folder permissions", e);
        }
    }

    // ── TLS certificate (auto-generated) ───────────────────────────────────

    /**
     * Ensures webshare.jks exists — generates it automatically if not.
     * Called once on startup. Every installation gets its own unique key.
     * Uses the JDK's built-in keytool via Runtime — no extra libraries needed.
     */
    public static void ensureJksExists() {
        File jks = new File(getJksPath());

        // Ensure certificates/ directory exists
        File parent = jks.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        if (jks.exists() && jks.length() > 0) {
            AppLogger.info("TLS certificate found: " + jks.getAbsolutePath());
            return; // trust it — keytool output is always valid if file exists and non-empty
        }

        // Delete zero-byte file if somehow created
        if (jks.exists()) jks.delete();

        AppLogger.info("Generating TLS certificate (first launch)...");
        try {
            String javaHome = System.getProperty("java.home");
            String keytool  = javaHome + File.separator + "bin"
                            + File.separator + "keytool";

            if (System.getProperty("os.name").toLowerCase().contains("win"))
                keytool += ".exe";

            if (!new File(keytool).exists()) keytool = "keytool";

            ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-keyalg",    "RSA",
                "-keysize",   "2048",
                "-validity",  "3650",
                "-alias",     "webshare",
                "-keystore",  getJksPath(),
                "-storetype", "JKS",
                "-storepass", JKS_PASSWORD,
                "-keypass",   JKS_PASSWORD,
                "-dname",     "CN=WebShare,OU=Local,O=Local,L=Local,ST=Local,C=US"
            );
            pb.redirectErrorStream(true);
            Process proc   = pb.start();
            String  output = new String(proc.getInputStream().readAllBytes());
            int     exit   = proc.waitFor();

            File generated = new File(getJksPath());
            if (exit == 0 && generated.exists() && generated.length() > 0) {
                AppLogger.info("TLS certificate generated successfully.");
            } else {
                AppLogger.error("keytool failed (exit " + exit + "): " + output, null);
                AppLogger.info("Server will fall back to plain HTTP.");
            }

        } catch (Exception e) {
            AppLogger.error("Could not generate TLS certificate", e);
            AppLogger.info("Server will fall back to plain HTTP.");
        }
    }

    // ── Port selection ─────────────────────────────────────────────────────

    /**
     * Finds the first free port in [8080, 8100].
     * Returns -1 if none are available.
     */
    public static int selectPort() {
        for (int port = PORT_START; port <= PORT_END; port++) {
            if (isPortFree(port)) return port;
        }
        AppLogger.error("No free port found in range "
            + PORT_START + "–" + PORT_END, null);
        return -1;
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            AppLogger.info("Port " + port + " in use, trying next...");
            return false;
        }
    }
}
package com.webshare.service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SessionManager {

    private final String  accessKey;
    private volatile boolean uploadAllowed = false;
    private volatile boolean execAllowed   = false;

    // Single version counter — incremented on ANY state change
    // (files added/removed, permissions changed)
    // Browser compares its last known version — if different, refreshes
    private final AtomicLong version = new AtomicLong(0);

    // ── Session tokens ─────────────────────────────────────────────────────
    private final Map<String, Long> sessionTokens = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    public SessionManager() {
        SecureRandom sr = new SecureRandom();
        this.accessKey = String.format("%06d", sr.nextInt(1_000_000));
    }

    // ── Version ────────────────────────────────────────────────────────────

    /** Returns current version — browser uses this to detect any change */
    public long getVersion() { return version.get(); }

    /** Call whenever files or permissions change */
    public void bump() { version.incrementAndGet(); }

    // ── Access key ─────────────────────────────────────────────────────────

    public String getAccessKey() { return accessKey; }

    public boolean checkKey(String key) {
        if (key == null) return false;
        return constantTimeEquals(accessKey, key.trim());
    }

    // ── Session tokens ─────────────────────────────────────────────────────

    public String createSession() {
        String token = UUID.randomUUID().toString();
        sessionTokens.put(token, System.currentTimeMillis());
        return token;
    }

    public boolean checkSession(String token) {
        if (token == null || token.isBlank()) return false;
        Long created = sessionTokens.get(token);
        if (created == null) return false;
        if (System.currentTimeMillis() - created > SESSION_TTL_MS) {
            sessionTokens.remove(token);
            return false;
        }
        return true;
    }

    public void revokeSession(String token) {
        if (token != null) sessionTokens.remove(token);
    }

    public void clearSessions() { sessionTokens.clear(); }

    public int activeSessionCount() { return sessionTokens.size(); }

    // ── Upload / exec toggles ──────────────────────────────────────────────

    public boolean isUploadAllowed() { return uploadAllowed; }

    public void setUploadAllowed(boolean v) {
        uploadAllowed = v;
        bump();
    }

    public boolean isExecAllowed() { return execAllowed; }

    public void setExecAllowed(boolean v) {
        execAllowed = v;
        bump();
    }

    // ── File change notification ───────────────────────────────────────────

    public void broadcastFilesChanged() { bump(); }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
package com.webshare.service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SessionManager {

    // PIN has ~20 bits of entropy (000000–999999).
    // Security against brute force depends entirely on RateLimiter holding.
    // If the rate limiter is bypassed, the PIN falls quickly.
    private final String  accessKey;

    private volatile boolean uploadAllowed = false;
    private volatile boolean execAllowed   = false;

    // Single version counter — incremented on ANY state change
    // (files added/removed, permissions changed).
    // Browser compares its last known version — if different, refreshes.
    private final AtomicLong version = new AtomicLong(0);

    // ── Session tokens ─────────────────────────────────────────────────────
    // Expired sessions are only evicted when checkSession() is called for that
    // specific token. Tokens from clients that never re-authenticate accumulate
    // until clearSessions() is called (on server stop). Acceptable for a LAN
    // server; add a periodic sweep for broader deployments.
    private final Map<String, Long> sessionTokens = new ConcurrentHashMap<>();

    // SESSION_TTL_MS applies from last activity (sliding window), not creation.
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
        // constantTimeEquals always compares full strings — length check inside
        // is safe here because the PIN is always exactly 6 digits and input is
        // trimmed before calling. If the PIN length ever becomes variable, the
        // length short-circuit in constantTimeEquals would leak that information.
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
        Long lastSeen = sessionTokens.get(token);
        if (lastSeen == null) return false;
        if (System.currentTimeMillis() - lastSeen > SESSION_TTL_MS) {
            sessionTokens.remove(token);
            return false;
        }
        // Sliding window — refresh TTL on every authenticated request
        sessionTokens.put(token, System.currentTimeMillis());
        return true;
    }

    public void revokeSession(String token) {
        if (token != null) sessionTokens.remove(token);
    }

    public void clearSessions() { sessionTokens.clear(); }

    /**
     * Returns the number of stored session tokens, including expired ones
     * not yet evicted. Use for diagnostics only — not a count of active sessions.
     */
    public int storedSessionCount() { return sessionTokens.size(); }

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

    /**
     * Constant-time string comparison — prevents timing attacks on the PIN.
     * Early-exits on length mismatch, which technically leaks whether the
     * submitted PIN has the correct length. Safe here because the PIN is
     * always exactly 6 digits and input is validated before this call.
     */
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

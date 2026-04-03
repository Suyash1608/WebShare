package com.webshare.service;

import com.webshare.util.AppLogger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory IP-based rate limiter.
 * Blocks an IP for LOCKOUT_MS after MAX_FAILS consecutive failed auth attempts.
 *
 * Limitations (acceptable for a LAN server):
 * - Fail counts for IPs that never reach MAX_FAILS are never evicted.
 *   Add a ScheduledExecutorService sweep if exposed to broader networks.
 * - Security depends on this rate limiter holding — the 6-digit PIN has ~20 bits
 *   of entropy and would fall quickly if the lockout were bypassed.
 */
public class RateLimiter {

    private static final int  MAX_FAILS  = 5;
    private static final long LOCKOUT_MS = 10 * 60 * 1000L; // 10 minutes

    // ip → fail count
    // Note: entries for IPs that fail < MAX_FAILS times are never removed.
    // Acceptable on a LAN; add periodic sweep for broader deployments.
    private final Map<String, Integer> fails   = new ConcurrentHashMap<>();

    // ip → blocked-until timestamp
    private final Map<String, Long>    blocked = new ConcurrentHashMap<>();

    /** Returns true if the IP is currently blocked. */
    public boolean isBlocked(String ip) {
        Long until = blocked.get(ip);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            // Lockout expired — clean up
            blocked.remove(ip);
            fails.remove(ip);
            AppLogger.info("RateLimiter: lockout expired — " + ip);
            return false;
        }
        return true;
    }

    /**
     * Call on every failed auth attempt.
     *
     * Note: if recordFail is called concurrently for the same IP (e.g. two
     * threads both pass isBlocked before either records a fail), the fail count
     * may be incremented twice atomically but blocked.put may also be called
     * twice, effectively resetting the lockout timestamp. This is a minor
     * TOCTOU race — low risk on a LAN server but worth noting.
     * If called for an already-blocked IP, the lockout timestamp is refreshed.
     */
    public void recordFail(String ip) {
        int count = fails.merge(ip, 1, Integer::sum);
        if (count >= MAX_FAILS) {
            blocked.put(ip, System.currentTimeMillis() + LOCKOUT_MS);
            AppLogger.info("RateLimiter: IP blocked for 10 min — " + ip);
        }
    }

    /** Returns the current fail count for an IP (for logging). */
    public int failCount(String ip) {
        return fails.getOrDefault(ip, 0);
    }

    /** Call on successful auth — clears the IP's fail history. */
    public void reset(String ip) {
        fails.remove(ip);
        blocked.remove(ip);
    }
}

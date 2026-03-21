package com.webshare.service;

import com.webshare.util.AppLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory IP-based rate limiter.
 * Blocks an IP for LOCKOUT_MS after MAX_FAILS consecutive failed auth attempts.
 */
public class RateLimiter {

    private static final int  MAX_FAILS  = 5;
    private static final long LOCKOUT_MS = 10 * 60 * 1000L; // 10 minutes

    // ip → fail count
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
            return false;
        }
        return true;
    }

    /** Call on every failed auth attempt. */
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
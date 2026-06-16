package com.aryamann.ratelimiter.core;

/**
 * The rate limiting algorithms supported by this library. The active algorithm is selectable
 * per rule (tier / route) via configuration — switching never requires a code change.
 */
public enum Algorithm {
    /** Refill-based bucket; smooths traffic while allowing controlled bursts up to capacity. */
    TOKEN_BUCKET,
    /** Sorted set of request timestamps; exact sliding window, higher memory. */
    SLIDING_WINDOW_LOG,
    /** Weighted current + previous fixed-window counters; accurate and cheap. */
    SLIDING_WINDOW_COUNTER,
    /** Single counter per window; cheapest, but allows bursts at the window boundary. Baseline. */
    FIXED_WINDOW
}

package com.eno.stockbot.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenAsksToWaitUntilWindowSlides() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(3, now::get);

        assertEquals(0, limiter.tryAcquire());
        now.set(10_000);
        assertEquals(0, limiter.tryAcquire());
        assertEquals(0, limiter.tryAcquire());

        now.set(20_000);
        assertEquals(40_000, limiter.tryAcquire());   // 最早一筆在 t=0，要等到 t=60000

        now.set(60_000);
        assertEquals(0, limiter.tryAcquire());
        assertTrue(limiter.tryAcquire() > 0);         // t=10000 的兩筆還在視窗內
    }
}

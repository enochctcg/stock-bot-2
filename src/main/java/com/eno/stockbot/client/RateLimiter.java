package com.eno.stockbot.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 滑動視窗限流：任意 60 秒內最多 permitsPerMinute 次請求。
 * 超過時阻塞等待，確保不會打到富果的 429。
 */
public class RateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final int permitsPerMinute;
    private final LongSupplier clockMillis;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int permitsPerMinute) {
        this(permitsPerMinute, System::currentTimeMillis);
    }

    RateLimiter(int permitsPerMinute, LongSupplier clockMillis) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("permitsPerMinute must be > 0");
        }
        this.permitsPerMinute = permitsPerMinute;
        this.clockMillis = clockMillis;
    }

    /** 取得一個額度，必要時阻塞。 */
    public void acquire() throws InterruptedException {
        while (true) {
            long waitMs;
            synchronized (this) {
                waitMs = tryAcquire();
                if (waitMs == 0) {
                    return;
                }
            }
            Thread.sleep(waitMs);
        }
    }

    /** @return 0 代表取得成功；否則為需要等待的毫秒數 */
    synchronized long tryAcquire() {
        long now = clockMillis.getAsLong();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= WINDOW_MS) {
            timestamps.pollFirst();
        }
        if (timestamps.size() < permitsPerMinute) {
            timestamps.addLast(now);
            return 0;
        }
        return Math.max(1, WINDOW_MS - (now - timestamps.peekFirst()));
    }
}

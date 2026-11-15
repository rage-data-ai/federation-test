package com.cmcc.cmdevops.security.federation.account;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户名限制连续登录失败：窗口内失败满 {@link #MAX_FAILURES} 次后，
 * 直到最早一次失败滑出窗口前都拒绝再试。只在进程内存里计数，重启清零。
 */
final class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final long WINDOW_MS = 15 * 60_000L;
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    /** 还要等多少毫秒才能再试；0 表示现在可以试。 */
    long retryAfterMs(String username, long now) {
        Deque<Long> queue = failures.get(username);
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            drop(queue, now);
            if (queue.size() < MAX_FAILURES) {
                return 0;
            }
            return queue.peekFirst() + WINDOW_MS - now;
        }
    }

    void recordFailure(String username, long now) {
        if (failures.size() >= MAX_TRACKED) {
            failures.values().removeIf(queue -> {
                synchronized (queue) {
                    drop(queue, now);
                    return queue.isEmpty();
                }
            });
        }
        Deque<Long> queue = failures.computeIfAbsent(username, key -> new ArrayDeque<>());
        synchronized (queue) {
            drop(queue, now);
            queue.addLast(now);
        }
    }

    void reset(String username) {
        failures.remove(username);
    }

    private static void drop(Deque<Long> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst() >= WINDOW_MS) {
            queue.removeFirst();
        }
    }
}

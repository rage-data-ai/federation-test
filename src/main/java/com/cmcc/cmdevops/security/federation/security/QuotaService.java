package com.cmcc.cmdevops.security.federation.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

@Service
public class QuotaService {

    private final FederationProperties properties;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public QuotaService(FederationProperties properties) {
        this.properties = properties;
    }

    public void check(String user) {
        int limit = properties.getQuota().getMaxRequestsPerMinute();
        if (limit <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> queue = windows.computeIfAbsent(user, key -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > 60_000) {
                queue.removeFirst();
            }
            if (queue.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "超过每分钟配额 " + limit);
            }
            queue.addLast(now);
        }
    }
}

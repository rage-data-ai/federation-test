package com.cmcc.cmdevops.security.federation.account;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryUserStore implements UserStore {

    private final Map<Long, UserAccount> users = new LinkedHashMap<>();
    private final Map<String, UserSession> sessions = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public synchronized UserAccount insert(UserAccount account) {
        if (findByUsername(account.username()).isPresent()) {
            throw new DuplicateUsernameException(account.username());
        }
        UserAccount saved = account.withId(nextId.getAndIncrement());
        users.put(saved.id(), saved);
        return saved;
    }

    @Override
    public synchronized void update(UserAccount account) {
        if (users.containsKey(account.id())) {
            users.put(account.id(), account);
        }
    }

    @Override
    public synchronized Optional<UserAccount> find(long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public synchronized Optional<UserAccount> findByUsername(String username) {
        return users.values().stream().filter(user -> user.username().equals(username)).findFirst();
    }

    @Override
    public synchronized List<UserAccount> list() {
        return users.values().stream().sorted(Comparator.comparingLong(UserAccount::id)).toList();
    }

    @Override
    public synchronized void saveSession(UserSession session) {
        sessions.put(session.tokenHash(), session);
    }

    @Override
    public synchronized Optional<UserSession> findSession(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public synchronized void extendSession(String tokenHash, long expiresAt) {
        UserSession session = sessions.get(tokenHash);
        if (session != null) {
            sessions.put(tokenHash,
                    new UserSession(tokenHash, session.userId(), session.createdAt(), expiresAt));
        }
    }

    @Override
    public synchronized void deleteSession(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public synchronized void deleteSessionsOf(long userId, String keepTokenHash) {
        sessions.values().removeIf(session -> session.userId() == userId
                && !session.tokenHash().equals(keepTokenHash));
    }

    @Override
    public synchronized void deleteExpiredSessions(long now) {
        sessions.values().removeIf(session -> session.expiresAt() <= now);
    }

    /** 测试用：清空账号与会话。 */
    public synchronized void clear() {
        users.clear();
        sessions.clear();
        nextId.set(1);
    }
}

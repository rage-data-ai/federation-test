package com.cmcc.cmdevops.security.federation.ask.prompt;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryAskPromptStore implements AskPromptStore {

    private final Map<Long, AskPrompt> items = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public synchronized AskPrompt save(AskPrompt item) {
        long id = item.id() > 0 ? item.id() : nextId.getAndIncrement();
        if (item.id() > 0) {
            nextId.accumulateAndGet(item.id() + 1, Math::max);
        }
        AskPrompt saved = new AskPrompt(
                id,
                item.scope(),
                item.owner(),
                item.content(),
                item.status(),
                item.source(),
                item.version(),
                item.createdBy(),
                item.activatedBy(),
                item.createdAt(),
                item.updatedAt());
        items.put(id, saved);
        return saved;
    }

    @Override
    public synchronized Optional<AskPrompt> find(long id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized List<AskPrompt> list(String scope, String owner) {
        return items.values().stream()
                .filter(item -> scope == null || scope.equals(item.scope()))
                .filter(item -> owner == null || owner.equals(item.owner())
                        || (AskPrompt.GLOBAL.equals(item.scope()) && owner != null))
                .sorted(Comparator.comparingLong(AskPrompt::updatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized Optional<AskPrompt> activeGlobal() {
        return items.values().stream()
                .filter(item -> AskPrompt.GLOBAL.equals(item.scope()) && AskPrompt.ACTIVE.equals(item.status()))
                .max(Comparator.comparingInt(AskPrompt::version));
    }

    @Override
    public synchronized Optional<AskPrompt> activePersonal(String owner) {
        if (owner == null || owner.isBlank()) {
            return Optional.empty();
        }
        return items.values().stream()
                .filter(item -> AskPrompt.PERSONAL.equals(item.scope())
                        && owner.equals(item.owner())
                        && AskPrompt.ACTIVE.equals(item.status()))
                .max(Comparator.comparingInt(AskPrompt::version));
    }

    @Override
    public synchronized void delete(long id) {
        items.remove(id);
    }
}

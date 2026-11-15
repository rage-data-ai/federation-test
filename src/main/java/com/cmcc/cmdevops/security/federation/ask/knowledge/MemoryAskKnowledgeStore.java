package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryAskKnowledgeStore implements AskKnowledgeStore {

    private final Map<Long, AskKnowledge> items = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public synchronized AskKnowledge save(AskKnowledge item) {
        long id = item.id() > 0 ? item.id() : nextId.getAndIncrement();
        if (item.id() > 0) {
            nextId.accumulateAndGet(item.id() + 1, Math::max);
        }
        AskKnowledge saved = new AskKnowledge(
                id,
                item.question(),
                item.answerPair(),
                item.evidence() == null ? Map.of() : item.evidence(),
                item.sourceRunId(),
                item.status(),
                item.createdBy(),
                item.reviewedBy(),
                item.reviewNote(),
                item.createdAt(),
                item.updatedAt());
        items.put(id, saved);
        return saved;
    }

    @Override
    public synchronized Optional<AskKnowledge> find(long id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized List<AskKnowledge> list(String status) {
        return items.values().stream()
                .filter(item -> status == null || status.equals(item.status()))
                .sorted(Comparator.comparingLong(AskKnowledge::updatedAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<AskKnowledge> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AskKnowledge> found = new ArrayList<>();
        for (Long id : ids) {
            AskKnowledge item = items.get(id);
            if (item != null) {
                found.add(item);
            }
        }
        return found;
    }

    @Override
    public synchronized void delete(long id) {
        items.remove(id);
    }
}

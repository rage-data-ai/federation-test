package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 只存在内存里的实现，供不接联邦库的装配和单元测试使用。 */
public class MemoryAskFeedbackStore implements AskFeedbackStore {

    private final Map<String, AskRun> runs = new LinkedHashMap<>();
    private final Map<String, String> ratings = new LinkedHashMap<>();
    private final Map<Long, AskSuggestion> suggestions = new LinkedHashMap<>();
    private long nextSuggestionId = 1;

    @Override
    public synchronized void saveRun(AskRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public synchronized Optional<AskRun> findRun(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public synchronized void rate(String runId, String userName, String rating, long now) {
        String key = runId + "\n" + userName;
        if (rating == null) {
            ratings.remove(key);
        } else {
            ratings.put(key, rating);
        }
    }

    @Override
    public synchronized Optional<String> ratingOf(String runId, String userName) {
        return Optional.ofNullable(ratings.get(runId + "\n" + userName));
    }

    @Override
    public synchronized AskSuggestion addSuggestion(String runId, String userName, String content, long now) {
        AskSuggestion suggestion = new AskSuggestion(nextSuggestionId++, runId, null, userName, content,
                AskSuggestion.OPEN, null, null, null, now, now);
        suggestions.put(suggestion.id(), suggestion);
        return withQuestion(suggestion);
    }

    @Override
    public synchronized Optional<AskSuggestion> findSuggestion(long id) {
        return Optional.ofNullable(suggestions.get(id)).map(this::withQuestion);
    }

    @Override
    public synchronized List<AskSuggestion> listSuggestions(String userName, String status) {
        return suggestions.values().stream()
                .filter(item -> userName == null || userName.equals(item.userName()))
                .filter(item -> status == null || status.equals(item.status()))
                .sorted(Comparator.comparingLong(AskSuggestion::createdAt).reversed()
                        .thenComparing(Comparator.comparingLong(AskSuggestion::id).reversed()))
                .map(this::withQuestion)
                .toList();
    }

    @Override
    public synchronized void updateSuggestion(AskSuggestion suggestion) {
        suggestions.put(suggestion.id(), suggestion);
    }

    @Override
    public synchronized void deleteSuggestion(long id) {
        suggestions.remove(id);
    }

    private AskSuggestion withQuestion(AskSuggestion item) {
        AskRun run = item.runId() == null ? null : runs.get(item.runId());
        return new AskSuggestion(item.id(), item.runId(), run == null ? null : run.question(), item.userName(),
                item.content(), item.status(), item.disposition(), item.reply(), item.handledBy(),
                item.createdAt(), item.updatedAt());
    }
}

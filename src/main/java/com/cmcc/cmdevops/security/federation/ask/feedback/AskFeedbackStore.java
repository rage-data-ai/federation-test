package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.List;
import java.util.Optional;

/** 问数记录、评价和建议的持久化。 */
public interface AskFeedbackStore {

    void saveRun(AskRun run);

    Optional<AskRun> findRun(String id);

    /** 同一用户对同一次问数只保留一条评价；{@code rating} 为 null 时撤销。 */
    void rate(String runId, String userName, String rating, long now);

    Optional<String> ratingOf(String runId, String userName);

    AskSuggestion addSuggestion(String runId, String userName, String content, long now);

    Optional<AskSuggestion> findSuggestion(long id);

    /** 按创建时间倒序；{@code userName}、{@code status} 为 null 时不按该项过滤。 */
    List<AskSuggestion> listSuggestions(String userName, String status);

    void updateSuggestion(AskSuggestion suggestion);

    void deleteSuggestion(long id);
}

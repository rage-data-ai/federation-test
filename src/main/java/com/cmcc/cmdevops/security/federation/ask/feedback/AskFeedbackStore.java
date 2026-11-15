package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.List;
import java.util.Optional;

/** 问数记录、评价和建议的持久化。 */
public interface AskFeedbackStore {

    void saveRun(AskRun run);

    Optional<AskRun> findRun(String id);

    /**
     * 某用户的问数记录，按创建时间倒序。
     * {@code limit} ≤ 0 时不截断。
     */
    List<AskRun> listRunsByUser(String userName, int limit);

    /** 某用户某段对话里的问数记录，按创建时间正序。 */
    List<AskRun> listRunsByConversation(String userName, String conversationId);

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

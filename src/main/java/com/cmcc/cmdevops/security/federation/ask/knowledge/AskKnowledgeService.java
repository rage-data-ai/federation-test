package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.ask.feedback.AskRun;
import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledge.AnswerPair;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/**
 * 共享知识库：好评待审入库、人工维护、按问题检索。谁都能看已通过的；待审与驳回仅管理员（提出人可看自己提交的）。
 */
@Service
public class AskKnowledgeService {

    public static final int MAX_QUESTION = 2000;
    public static final int MAX_ANSWER = 8000;
    public static final int MAX_SQL = 8000;
    public static final int MAX_NOTE = 1000;
    public static final int DEFAULT_TOP_K = 5;

    private final AskKnowledgeStore store;
    private final KnowledgeVectorIndex vectors;
    private final LongSupplier clock;

    @Autowired
    public AskKnowledgeService(AskKnowledgeStore store, KnowledgeVectorIndex vectors) {
        this(store, vectors, System::currentTimeMillis);
    }

    public AskKnowledgeService(AskKnowledgeStore store, KnowledgeVectorIndex vectors, LongSupplier clock) {
        this.store = store;
        this.vectors = vectors;
        this.clock = clock;
    }

    public record KnowledgePatch(
            String question,
            String answer,
            String sql,
            List<Map<String, Object>> caliber,
            Map<String, Object> evidence,
            String status,
            String reviewNote) {
    }

    public record Match(AskKnowledge knowledge, double score) {
    }

    /** 建议采纳去向=knowledge：用建议正文生成待审知识。 */
    public AskKnowledge draftFromSuggestion(AskRun run, String suggestionContent) {
        String question = run != null && run.question() != null && !run.question().isBlank()
                ? run.question()
                : requireText(suggestionContent, "问题", MAX_QUESTION);
        String answer = requireText(suggestionContent, "答案", MAX_ANSWER);
        long now = clock.getAsLong();
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (run != null) {
            evidence.put("sourceRunId", run.id());
            evidence.put("conversationId", run.conversationId());
            if (run.evidence() != null) {
                evidence.putAll(run.evidence());
            }
        }
        evidence.put("fromSuggestion", true);
        return store.save(new AskKnowledge(
                0,
                question,
                new AnswerPair(answer, run == null ? null : run.sql(), List.of()),
                evidence,
                run == null ? null : run.id(),
                AskKnowledge.PENDING,
                GatewayUser.current().name(),
                null,
                null,
                now,
                now));
    }

    /** 评为有用时从问数记录生成待审知识；已有同 run 的待审/已通过条目则跳过。 */
    public AskKnowledge draftFromRun(AskRun run) {
        if (run == null || !AskRun.ANSWERED.equals(run.status())) {
            throw new IllegalArgumentException("只有给出了回答的问数才能入库");
        }
        for (AskKnowledge existing : store.list(null)) {
            if (run.id().equals(existing.sourceRunId())
                    && (AskKnowledge.PENDING.equals(existing.status())
                            || AskKnowledge.APPROVED.equals(existing.status()))) {
                return existing;
            }
        }
        long now = clock.getAsLong();
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (run.evidence() != null) {
            evidence.putAll(run.evidence());
        }
        evidence.put("sourceRunId", run.id());
        evidence.put("conversationId", run.conversationId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> caliber = run.evidence() != null && run.evidence().get("caliber") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return store.save(new AskKnowledge(
                0,
                requireText(run.question(), "问题", MAX_QUESTION),
                new AnswerPair(
                        requireText(run.answer(), "答案", MAX_ANSWER),
                        run.sql() == null || run.sql().isBlank() ? null : requireText(run.sql(), "SQL", MAX_SQL),
                        caliber),
                evidence,
                run.id(),
                AskKnowledge.PENDING,
                GatewayUser.current().name(),
                null,
                null,
                now,
                now));
    }

    public AskKnowledge create(String question, String answer, String sql, List<Map<String, Object>> caliber,
            Map<String, Object> evidence) {
        long now = clock.getAsLong();
        boolean admin = GatewayUser.current().hasRole(GatewayRole.ADMIN);
        AskKnowledge saved = store.save(new AskKnowledge(
                0,
                requireText(question, "问题", MAX_QUESTION),
                new AnswerPair(
                        requireText(answer, "答案", MAX_ANSWER),
                        sql == null || sql.isBlank() ? null : requireText(sql, "SQL", MAX_SQL),
                        caliber == null ? List.of() : caliber),
                evidence == null ? Map.of() : evidence,
                null,
                admin ? AskKnowledge.APPROVED : AskKnowledge.PENDING,
                GatewayUser.current().name(),
                admin ? GatewayUser.current().name() : null,
                null,
                now,
                now));
        syncVector(saved);
        return saved;
    }

    public AskKnowledge get(long id) {
        return visible(id);
    }

    public List<AskKnowledge> list(String status) {
        if (status != null && !status.isBlank() && !AskKnowledge.STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知的知识状态：" + status);
        }
        GatewayUser user = GatewayUser.current();
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        List<AskKnowledge> items = store.list(status == null || status.isBlank() ? null : status);
        if (admin) {
            return items;
        }
        return items.stream()
                .filter(item -> AskKnowledge.APPROVED.equals(item.status())
                        || user.name().equals(item.createdBy()))
                .toList();
    }

    public AskKnowledge update(long id, KnowledgePatch patch) {
        AskKnowledge current = visible(id);
        GatewayUser user = GatewayUser.current();
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        boolean author = user.name().equals(current.createdBy());
        if (!admin && !author) {
            throw forbidden("只有提出人或管理员能改知识");
        }
        if (!admin && !AskKnowledge.PENDING.equals(current.status())) {
            throw new IllegalStateException("知识已处理，内容不能再改");
        }

        String question = current.question();
        String answer = current.answerPair().answer();
        String sql = current.answerPair().sql();
        List<Map<String, Object>> caliber = current.answerPair().caliber();
        Map<String, Object> evidence = current.evidence();
        if (patch.question() != null) {
            question = requireText(patch.question(), "问题", MAX_QUESTION);
        }
        if (patch.answer() != null) {
            answer = requireText(patch.answer(), "答案", MAX_ANSWER);
        }
        if (patch.sql() != null) {
            sql = patch.sql().isBlank() ? null : requireText(patch.sql(), "SQL", MAX_SQL);
        }
        if (patch.caliber() != null) {
            caliber = patch.caliber();
        }
        if (patch.evidence() != null) {
            evidence = patch.evidence();
        }

        String status = current.status();
        String reviewedBy = current.reviewedBy();
        String reviewNote = current.reviewNote();
        if (patch.status() != null || patch.reviewNote() != null) {
            if (!admin) {
                throw forbidden("审核知识需要管理员角色");
            }
            if (patch.status() != null) {
                if (!AskKnowledge.STATUSES.contains(patch.status())) {
                    throw new IllegalArgumentException("未知的知识状态：" + patch.status());
                }
                status = patch.status();
            }
            if (patch.reviewNote() != null) {
                reviewNote = patch.reviewNote().isBlank()
                        ? null
                        : requireText(patch.reviewNote(), "审核说明", MAX_NOTE);
            }
            reviewedBy = AskKnowledge.PENDING.equals(status) ? null : user.name();
        }

        AskKnowledge updated = new AskKnowledge(
                current.id(),
                question,
                new AnswerPair(answer, sql, caliber == null ? List.of() : caliber),
                evidence == null ? Map.of() : evidence,
                current.sourceRunId(),
                status,
                current.createdBy(),
                reviewedBy,
                reviewNote,
                current.createdAt(),
                clock.getAsLong());
        if (same(current, updated)) {
            return current;
        }
        AskKnowledge saved = store.save(updated);
        syncVector(saved);
        return saved;
    }

    public void delete(long id) {
        AskKnowledge current = visible(id);
        GatewayUser user = GatewayUser.current();
        if (!user.hasRole(GatewayRole.ADMIN)
                && !(user.name().equals(current.createdBy()) && AskKnowledge.PENDING.equals(current.status()))) {
            throw forbidden("只有管理员，或提出人在待审时能删除知识");
        }
        store.delete(id);
        vectors.delete(id);
    }

    /** 问数路径用：按问题召回已通过的知识，按相似度降序。 */
    public List<Match> search(String question, int topK) {
        String text = requireText(question, "问题", MAX_QUESTION);
        List<KnowledgeVectorIndex.Hit> hits = vectors.search(text, Math.max(1, topK));
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (KnowledgeVectorIndex.Hit hit : hits) {
            scores.put(hit.id(), hit.score());
        }
        List<AskKnowledge> items = store.findByIds(new ArrayList<>(scores.keySet()));
        List<Match> matches = new ArrayList<>();
        for (AskKnowledge item : items) {
            if (AskKnowledge.APPROVED.equals(item.status())) {
                matches.add(new Match(item, scores.getOrDefault(item.id(), 0.0)));
            }
        }
        return matches;
    }

    /** 审核通过后把问题向量写入索引；驳回或待审则从索引去掉。 */
    void syncVector(AskKnowledge item) {
        if (AskKnowledge.APPROVED.equals(item.status())) {
            vectors.upsert(item.id(), item.question());
        } else {
            vectors.delete(item.id());
        }
    }

    private AskKnowledge visible(long id) {
        GatewayUser user = GatewayUser.current();
        return store.find(id)
                .filter(item -> user.hasRole(GatewayRole.ADMIN)
                        || AskKnowledge.APPROVED.equals(item.status())
                        || user.name().equals(item.createdBy()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识不存在：" + id));
    }

    private static boolean same(AskKnowledge a, AskKnowledge b) {
        return Objects.equals(a.question(), b.question())
                && Objects.equals(a.answerPair(), b.answerPair())
                && Objects.equals(a.evidence(), b.evidence())
                && Objects.equals(a.status(), b.status())
                && Objects.equals(a.reviewNote(), b.reviewNote())
                && Objects.equals(a.reviewedBy(), b.reviewedBy());
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String text = value.strip();
        if (text.length() > max) {
            throw new IllegalArgumentException(label + "不能超过 " + max + " 字");
        }
        return text;
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}

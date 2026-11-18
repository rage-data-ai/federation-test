package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.time.Instant;
import java.time.ZoneOffset;
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
 * 共享知识库：好评待审入库、人工维护、按问题检索。
 * MySQL/Milvus 的 answer 均为「问：…\n答：…」问答对；召回优先使用 Milvus 返回的 answer/year/version。
 */
@Service
public class AskKnowledgeService {

    public static final int MAX_QUESTION = 2000;
    public static final int MAX_ANSWER_BODY = 8000;
    public static final int MAX_ANSWER = MAX_QUESTION + MAX_ANSWER_BODY + 16;
    public static final int MAX_SQL = 8000;
    public static final int MAX_NOTE = 1000;
    public static final int MAX_YEAR = 16;
    public static final int MAX_VERSION = 32;
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
            String kind,
            String status,
            String reviewNote,
            String year,
            String version) {
    }

    public record Match(AskKnowledge knowledge, double score) {
    }

    /** 建议采纳去向=knowledge：用建议正文生成待审知识。 */
    public AskKnowledge draftFromSuggestion(AskRun run, String suggestionContent) {
        String question = run != null && run.question() != null && !run.question().isBlank()
                ? run.question()
                : requireText(suggestionContent, "问题", MAX_QUESTION);
        String answerBody = requireText(suggestionContent, "答案", MAX_ANSWER_BODY);
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
                question.strip(),
                new AnswerPair(
                        KnowledgeQaPair.format(question, answerBody),
                        run == null ? null : run.sql(),
                        List.of()),
                evidence,
                run == null ? null : run.id(),
                AskKnowledge.KIND_SMART_ASK,
                AskKnowledge.PENDING,
                GatewayUser.current().name(),
                null,
                null,
                defaultYear(now),
                AskKnowledge.DEFAULT_VERSION,
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
        String question = requireText(run.question(), "问题", MAX_QUESTION);
        String answerBody = requireText(run.answer(), "答案", MAX_ANSWER_BODY);
        return store.save(new AskKnowledge(
                0,
                question,
                new AnswerPair(
                        KnowledgeQaPair.format(question, answerBody),
                        run.sql() == null || run.sql().isBlank() ? null : requireText(run.sql(), "SQL", MAX_SQL),
                        caliber),
                evidence,
                run.id(),
                AskKnowledge.KIND_SMART_ASK,
                AskKnowledge.PENDING,
                GatewayUser.current().name(),
                null,
                null,
                defaultYear(now),
                AskKnowledge.DEFAULT_VERSION,
                now,
                now));
    }

    public AskKnowledge create(
            String question,
            String answer,
            String sql,
            List<Map<String, Object>> caliber,
            Map<String, Object> evidence) {
        return create(question, answer, sql, caliber, evidence, AskKnowledge.KIND_SMART_ASK, null, null);
    }

    public AskKnowledge create(
            String question,
            String answer,
            String sql,
            List<Map<String, Object>> caliber,
            Map<String, Object> evidence,
            String kind) {
        return create(question, answer, sql, caliber, evidence, kind, null, null);
    }

    public AskKnowledge create(
            String question,
            String answer,
            String sql,
            List<Map<String, Object>> caliber,
            Map<String, Object> evidence,
            String kind,
            String year,
            String version) {
        long now = clock.getAsLong();
        boolean admin = GatewayUser.current().hasRole(GatewayRole.ADMIN);
        String q = requireText(question, "问题", MAX_QUESTION);
        String pair = KnowledgeQaPair.format(q, requireText(answer, "答案", MAX_ANSWER_BODY));
        if (pair.length() > MAX_ANSWER) {
            throw new IllegalArgumentException("问答对过长（最多 " + MAX_ANSWER + " 字）");
        }
        AskKnowledge saved = store.save(new AskKnowledge(
                0,
                q,
                new AnswerPair(
                        pair,
                        sql == null || sql.isBlank() ? null : requireText(sql, "SQL", MAX_SQL),
                        caliber == null ? List.of() : caliber),
                evidence == null ? Map.of() : evidence,
                null,
                normalizeKind(kind),
                admin ? AskKnowledge.APPROVED : AskKnowledge.PENDING,
                GatewayUser.current().name(),
                admin ? GatewayUser.current().name() : null,
                null,
                normalizeYear(year, now),
                normalizeVersion(version),
                now,
                now));
        syncVector(saved);
        return saved;
    }

    public AskKnowledge get(long id) {
        return visible(id);
    }

    public List<AskKnowledge> list(String status) {
        return list(status, null);
    }

    public List<AskKnowledge> list(String status, String kind) {
        if (status != null && !status.isBlank() && !AskKnowledge.STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知的知识状态：" + status);
        }
        String kindFilter = kind == null || kind.isBlank() ? null : normalizeKind(kind);
        GatewayUser user = GatewayUser.current();
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        List<AskKnowledge> items = store.list(status == null || status.isBlank() ? null : status);
        return items.stream()
                .filter(item -> kindFilter == null || kindFilter.equals(item.kindOrDefault()))
                .filter(item -> admin
                        || AskKnowledge.APPROVED.equals(item.status())
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
        String year = current.year();
        String version = current.versionOrDefault();
        if (patch.question() != null) {
            question = requireText(patch.question(), "问题", MAX_QUESTION);
        }
        if (patch.answer() != null) {
            answer = KnowledgeQaPair.format(question, requireText(patch.answer(), "答案", MAX_ANSWER_BODY));
        } else if (patch.question() != null) {
            // 问题变更时，用原答句重拼问答对，保持 MySQL/Milvus answer 一致
            answer = KnowledgeQaPair.format(question, KnowledgeQaPair.answerBody(answer, current.question()));
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
        if (patch.year() != null) {
            year = normalizeYear(patch.year(), clock.getAsLong());
        }
        if (patch.version() != null) {
            version = normalizeVersion(patch.version());
        }

        String kind = current.kindOrDefault();
        if (patch.kind() != null) {
            kind = normalizeKind(patch.kind());
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
                kind,
                status,
                current.createdBy(),
                reviewedBy,
                reviewNote,
                year,
                version,
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

    /** 问数路径用：按问题召回已通过的智能问数知识，按相似度降序。 */
    public List<Match> search(String question, int topK) {
        return search(question, topK, AskKnowledge.KIND_SMART_ASK);
    }

    public List<Match> search(String question, int topK, String kind) {
        String text = requireText(question, "问题", MAX_QUESTION);
        String kindFilter = normalizeKind(kind);
        List<KnowledgeVectorIndex.Hit> hits = vectors.search(text, Math.max(1, topK * 3));
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeVectorIndex.Hit> byId = new LinkedHashMap<>();
        for (KnowledgeVectorIndex.Hit hit : hits) {
            byId.putIfAbsent(hit.id(), hit);
        }
        List<AskKnowledge> items = store.findByIds(new ArrayList<>(byId.keySet()));
        Map<Long, AskKnowledge> mysqlById = new LinkedHashMap<>();
        for (AskKnowledge item : items) {
            mysqlById.put(item.id(), item);
        }
        List<Match> matches = new ArrayList<>();
        for (KnowledgeVectorIndex.Hit hit : byId.values()) {
            AskKnowledge mysql = mysqlById.get(hit.id());
            if (mysql != null) {
                if (!AskKnowledge.APPROVED.equals(mysql.status()) || !kindFilter.equals(mysql.kindOrDefault())) {
                    continue;
                }
                // Milvus 带回的问答对 / year / version 覆盖展示字段，与库内保持同构
                AskKnowledge merged = mergeHit(mysql, hit);
                matches.add(new Match(merged, hit.score()));
            } else if (!hit.answer().isBlank()) {
                // 向量库有、MySQL 暂缺：仍可召回（仅 Milvus 字段）
                matches.add(new Match(fromHit(hit, kindFilter), hit.score()));
            }
            if (matches.size() >= Math.max(1, topK)) {
                break;
            }
        }
        return matches;
    }

    private static AskKnowledge mergeHit(AskKnowledge mysql, KnowledgeVectorIndex.Hit hit) {
        String answer = hit.answer() == null || hit.answer().isBlank()
                ? mysql.answerPair().answer()
                : hit.answer();
        String year = hit.year() == null || hit.year().isBlank() ? mysql.year() : hit.year();
        String version = hit.version() == null || hit.version().isBlank()
                ? mysql.versionOrDefault()
                : hit.version();
        String question = hit.question() == null || hit.question().isBlank() ? mysql.question() : hit.question();
        return new AskKnowledge(
                mysql.id(),
                question,
                new AnswerPair(answer, mysql.answerPair().sql(), mysql.answerPair().caliber()),
                mysql.evidence(),
                mysql.sourceRunId(),
                mysql.kindOrDefault(),
                mysql.status(),
                mysql.createdBy(),
                mysql.reviewedBy(),
                mysql.reviewNote(),
                year,
                version,
                mysql.createdAt(),
                mysql.updatedAt());
    }

    private static AskKnowledge fromHit(KnowledgeVectorIndex.Hit hit, String kind) {
        return new AskKnowledge(
                hit.id(),
                hit.question(),
                new AnswerPair(hit.answer(), null, List.of()),
                Map.of(),
                null,
                kind,
                AskKnowledge.APPROVED,
                "milvus",
                null,
                null,
                hit.year(),
                hit.version() == null || hit.version().isBlank() ? AskKnowledge.DEFAULT_VERSION : hit.version(),
                0L,
                0L);
    }

    private static String normalizeKind(String kind) {
        String value = kind == null || kind.isBlank() ? AskKnowledge.KIND_SMART_ASK : kind.strip();
        if (!AskKnowledge.KINDS.contains(value)) {
            throw new IllegalArgumentException("未知的知识类型：" + kind + "（可用：smart_ask、general）");
        }
        return value;
    }

    private static String normalizeYear(String year, long epochMs) {
        if (year == null || year.isBlank()) {
            return defaultYear(epochMs);
        }
        String value = year.strip();
        if (value.length() > MAX_YEAR) {
            throw new IllegalArgumentException("年份过长（最多 " + MAX_YEAR + " 字）");
        }
        return value;
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return AskKnowledge.DEFAULT_VERSION;
        }
        String value = version.strip();
        if (value.length() > MAX_VERSION) {
            throw new IllegalArgumentException("版本过长（最多 " + MAX_VERSION + " 字）");
        }
        return value;
    }

    private static String defaultYear(long epochMs) {
        return String.valueOf(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).getYear());
    }

    /** 审核通过后把问答对与向量写入索引；驳回或待审则从索引去掉。 */
    void syncVector(AskKnowledge item) {
        try {
            if (AskKnowledge.APPROVED.equals(item.status())) {
                vectors.upsert(item);
            } else {
                vectors.delete(item.id());
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AskKnowledgeService.class)
                    .warn("知识向量同步失败 id={}: {}", item.id(), e.toString());
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
                && Objects.equals(a.kindOrDefault(), b.kindOrDefault())
                && Objects.equals(a.answerPair(), b.answerPair())
                && Objects.equals(a.evidence(), b.evidence())
                && Objects.equals(a.status(), b.status())
                && Objects.equals(a.reviewNote(), b.reviewNote())
                && Objects.equals(a.reviewedBy(), b.reviewedBy())
                && Objects.equals(a.yearOrDefault(), b.yearOrDefault())
                && Objects.equals(a.versionOrDefault(), b.versionOrDefault());
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String text = value.strip();
        if (text.length() > max) {
            throw new IllegalArgumentException(label + "过长（最多 " + max + " 字）");
        }
        return text;
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}

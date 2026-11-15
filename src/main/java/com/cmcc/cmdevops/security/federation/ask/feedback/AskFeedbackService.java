package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService;
import com.cmcc.cmdevops.security.federation.ask.prompt.AskPromptService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/**
 * 评价与建议的业务规则。
 * <p>
 * 问数记录只对发起人和管理员可见；建议由提出人撰写、管理员处置。
 * 评为有用时写入待审知识（见 {@link AskKnowledgeService#draftFromRun}）。
 * 采纳并指定去向时落地到知识库 / 提示词草稿（本体去向仅记录，改本体仍走本体编辑）。
 */
@Service
public class AskFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AskFeedbackService.class);

    public static final String UP = "up";
    public static final String DOWN = "down";

    static final int MAX_CONTENT = 2000;
    static final int MAX_REPLY = 1000;

    private final AskFeedbackStore store;
    private final AskKnowledgeService knowledge;
    private final AskPromptService prompts;
    private final LongSupplier clock;

    @Autowired
    public AskFeedbackService(AskFeedbackStore store, AskKnowledgeService knowledge, AskPromptService prompts) {
        this(store, knowledge, prompts, System::currentTimeMillis);
    }

    AskFeedbackService(AskFeedbackStore store, LongSupplier clock) {
        this(store, null, null, clock);
    }

    AskFeedbackService(AskFeedbackStore store, AskKnowledgeService knowledge, LongSupplier clock) {
        this(store, knowledge, null, clock);
    }

    public AskFeedbackService(
            AskFeedbackStore store, AskKnowledgeService knowledge, AskPromptService prompts, LongSupplier clock) {
        this.store = store;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.clock = clock;
    }

    /** @param rating 当前用户对这次问数的评价，没有评价时为 null */
    public record RunView(AskRun run, String rating) {
    }

    /** 各字段为 null 表示不改；{@code disposition} 传空串表示清除。 */
    public record SuggestionPatch(String content, String status, String disposition, String reply) {
    }

    public RunView run(String runId) {
        AskRun run = visibleRun(runId);
        return new RunView(run, store.ratingOf(run.id(), GatewayUser.current().name()).orElse(null));
    }

    /** @param rating {@link #UP} / {@link #DOWN}，null 表示撤销 */
    public RunView rate(String runId, String rating) {
        AskRun run = visibleRun(runId);
        if (rating != null && !UP.equals(rating) && !DOWN.equals(rating)) {
            throw new IllegalArgumentException("评价只能是 up 或 down");
        }
        if (UP.equals(rating) && !AskRun.ANSWERED.equals(run.status())) {
            throw new IllegalArgumentException("只有给出了回答的问数才能评为有用");
        }
        store.rate(run.id(), GatewayUser.current().name(), rating, clock.getAsLong());
        if (UP.equals(rating) && knowledge != null) {
            try {
                knowledge.draftFromRun(run);
            } catch (RuntimeException e) {
                log.warn("好评入库待审失败 runId={}: {}", run.id(), e.toString());
            }
        }
        return new RunView(run, rating);
    }

    public AskSuggestion suggest(String content, String runId) {
        String text = requireText(content, "建议内容", MAX_CONTENT);
        String linked = runId == null || runId.isBlank() ? null : visibleRun(runId).id();
        return store.addSuggestion(linked, GatewayUser.current().name(), text, clock.getAsLong());
    }

    /**
     * @param status   只看某个状态；null 表示全部
     * @param mineOnly 管理员默认看所有人的建议，传 true 只看自己的；非管理员始终只看自己的
     */
    public List<AskSuggestion> suggestions(String status, boolean mineOnly) {
        if (status != null && !AskSuggestion.STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知的建议状态：" + status);
        }
        GatewayUser user = GatewayUser.current();
        boolean all = user.hasRole(GatewayRole.ADMIN) && !mineOnly;
        return store.listSuggestions(all ? null : user.name(), status);
    }

    public AskSuggestion update(long id, SuggestionPatch patch) {
        GatewayUser user = GatewayUser.current();
        AskSuggestion current = visibleSuggestion(id);
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        boolean author = user.name().equals(current.userName());

        String content = current.content();
        if (patch.content() != null && !patch.content().strip().equals(current.content())) {
            if (!author) {
                throw forbidden("只有提出人能修改建议内容");
            }
            if (!AskSuggestion.OPEN.equals(current.status())) {
                throw new IllegalStateException("建议已处理，内容不能再改");
            }
            content = requireText(patch.content(), "建议内容", MAX_CONTENT);
        }

        String status = current.status();
        String disposition = current.disposition();
        String reply = current.reply();
        String handledBy = current.handledBy();
        boolean handling = patch.status() != null || patch.disposition() != null || patch.reply() != null;
        if (handling) {
            if (!admin) {
                throw forbidden("处置建议需要管理员角色");
            }
            if (patch.status() != null) {
                if (!AskSuggestion.STATUSES.contains(patch.status())) {
                    throw new IllegalArgumentException("未知的建议状态：" + patch.status());
                }
                status = patch.status();
            }
            if (patch.disposition() != null) {
                if (!patch.disposition().isEmpty() && !AskSuggestion.DISPOSITIONS.contains(patch.disposition())) {
                    throw new IllegalArgumentException("未知的去向：" + patch.disposition());
                }
                disposition = patch.disposition().isEmpty() ? null : patch.disposition();
            }
            if (patch.reply() != null) {
                reply = patch.reply().isBlank() ? null : requireText(patch.reply(), "答复", MAX_REPLY);
            }
            if (!AskSuggestion.ADOPTED.equals(status)) {
                disposition = null;
            }
            handledBy = AskSuggestion.OPEN.equals(status) ? null : user.name();
        }

        AskSuggestion updated = new AskSuggestion(current.id(), current.runId(), current.runQuestion(),
                current.userName(), content, status, disposition, reply, handledBy, current.createdAt(),
                clock.getAsLong());
        if (Objects.equals(updated.content(), current.content())
                && Objects.equals(updated.status(), current.status())
                && Objects.equals(updated.disposition(), current.disposition())
                && Objects.equals(updated.reply(), current.reply())) {
            return current;
        }
        store.updateSuggestion(updated);
        if (AskSuggestion.ADOPTED.equals(updated.status())
                && updated.disposition() != null
                && !Objects.equals(updated.disposition(), current.disposition())) {
            applyDisposition(updated);
        }
        return updated;
    }

    /** 采纳去向落地；失败只打日志，不回滚建议状态。 */
    private void applyDisposition(AskSuggestion suggestion) {
        try {
            if ("knowledge".equals(suggestion.disposition()) && knowledge != null) {
                AskRun run = suggestion.runId() == null ? null : store.findRun(suggestion.runId()).orElse(null);
                knowledge.draftFromSuggestion(run, suggestion.content());
            } else if ("prompt".equals(suggestion.disposition()) && prompts != null) {
                prompts.draftFromSuggestion(suggestion);
            }
            // ontology：只记录去向，改本体走 Ontology 编辑页
        } catch (RuntimeException e) {
            log.warn("建议去向落地失败 id={} disposition={}: {}",
                    suggestion.id(), suggestion.disposition(), e.toString());
        }
    }

    public void delete(long id) {
        GatewayUser user = GatewayUser.current();
        AskSuggestion current = visibleSuggestion(id);
        if (!user.hasRole(GatewayRole.ADMIN) && !AskSuggestion.OPEN.equals(current.status())) {
            throw new IllegalStateException("建议已处理，只有管理员能删除");
        }
        store.deleteSuggestion(id);
    }

    private AskRun visibleRun(String runId) {
        GatewayUser user = GatewayUser.current();
        return store.findRun(runId)
                .filter(run -> user.hasRole(GatewayRole.ADMIN) || user.name().equals(run.userName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "问数记录不存在：" + runId));
    }

    private AskSuggestion visibleSuggestion(long id) {
        GatewayUser user = GatewayUser.current();
        return store.findSuggestion(id)
                .filter(item -> user.hasRole(GatewayRole.ADMIN) || user.name().equals(item.userName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "建议不存在：" + id));
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

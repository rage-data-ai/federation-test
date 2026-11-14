package com.cmcc.cmdevops.security.federation.ask.prompt;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackStore;
import com.cmcc.cmdevops.security.federation.ask.feedback.AskSuggestion;
import com.cmcc.cmdevops.security.federation.llm.LlmClient;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/**
 * 补充提示词维护与合成。基础规则由调用方传入；本服务只拼全局与个人生效段。
 */
@Service
public class AskPromptService {

    public static final int MAX_CONTENT = 4000;

    private static final String PROPOSAL_PROMPT = """
            你是问数系统的提示词编辑。根据用户反馈（建议与差评线索）起草一段「全局补充提示词」，
            用来纠正口径、表达习惯或禁止事项。不要重复基础规则，不要写 SQL。
            只输出提示词正文，不要标题或其它说明。
            """;

    private final AskPromptStore store;
    private final AskFeedbackStore feedback;
    private final LlmClient llm;
    private final LongSupplier clock;

    @Autowired
    public AskPromptService(AskPromptStore store, AskFeedbackStore feedback, LlmClient llm) {
        this(store, feedback, llm, System::currentTimeMillis);
    }

    public AskPromptService(AskPromptStore store, AskFeedbackStore feedback, LlmClient llm, LongSupplier clock) {
        this.store = store;
        this.feedback = feedback;
        this.llm = llm;
        this.clock = clock;
    }

    /** 测试用：无反馈与模型。 */
    public AskPromptService(AskPromptStore store, LongSupplier clock) {
        this(store, null, null, clock);
    }

    public record PromptPatch(String content, String status) {
    }

    /** 基础规则优先，其后追加全局、个人生效补充。 */
    public String compose(String base) {
        StringBuilder out = new StringBuilder(base == null ? "" : base.strip());
        store.activeGlobal().ifPresent(global -> append(out, "全局补充提示词", global.content()));
        String user = GatewayUser.current().name();
        if (user != null && !user.isBlank() && !"anonymous".equals(user)) {
            store.activePersonal(user).ifPresent(personal -> append(out, "个人补充提示词", personal.content()));
        }
        return out.toString();
    }

    public List<AskPrompt> list(String scope) {
        if (scope != null && !scope.isBlank() && !AskPrompt.SCOPES.contains(scope)) {
            throw new IllegalArgumentException("未知的提示词范围：" + scope);
        }
        GatewayUser user = GatewayUser.current();
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        String filterScope = scope == null || scope.isBlank() ? null : scope;
        List<AskPrompt> items = store.list(filterScope, admin ? null : user.name());
        if (admin) {
            return items;
        }
        return items.stream()
                .filter(item -> AskPrompt.GLOBAL.equals(item.scope())
                        || user.name().equals(item.owner()))
                .toList();
    }

    public AskPrompt get(long id) {
        return visible(id);
    }

    public AskPrompt create(String scope, String content, String source) {
        GatewayUser user = GatewayUser.current();
        String resolvedScope = requireScope(scope);
        if (AskPrompt.GLOBAL.equals(resolvedScope) && !user.hasRole(GatewayRole.ADMIN)) {
            throw forbidden("只有管理员能写全局提示词");
        }
        String resolvedSource = source == null || source.isBlank() ? AskPrompt.HUMAN : source;
        if (!AskPrompt.SOURCES.contains(resolvedSource)) {
            throw new IllegalArgumentException("未知的提示词来源：" + resolvedSource);
        }
        long now = clock.getAsLong();
        String owner = AskPrompt.PERSONAL.equals(resolvedScope) ? user.name() : null;
        int version = nextVersion(resolvedScope, owner);
        boolean autoActive = AskPrompt.PERSONAL.equals(resolvedScope) && AskPrompt.HUMAN.equals(resolvedSource);
        AskPrompt saved = store.save(new AskPrompt(
                0,
                resolvedScope,
                owner,
                requireText(content),
                autoActive ? AskPrompt.ACTIVE : AskPrompt.DRAFT,
                resolvedSource,
                version,
                user.name(),
                autoActive ? user.name() : null,
                now,
                now));
        if (autoActive) {
            archiveOthers(saved);
        }
        return saved;
    }

    /** 分析师工具：把用户偏好写成个人生效提示词。 */
    public AskPrompt rememberPreference(String preference) {
        return create(AskPrompt.PERSONAL, preference, AskPrompt.HUMAN);
    }

    public AskPrompt update(long id, PromptPatch patch) {
        AskPrompt current = visible(id);
        GatewayUser user = GatewayUser.current();
        boolean admin = user.hasRole(GatewayRole.ADMIN);
        boolean owner = user.name().equals(current.owner());
        if (AskPrompt.GLOBAL.equals(current.scope()) && !admin) {
            throw forbidden("只有管理员能改全局提示词");
        }
        if (AskPrompt.PERSONAL.equals(current.scope()) && !admin && !owner) {
            throw forbidden("只能改自己的个人提示词");
        }
        if (AskPrompt.ARCHIVED.equals(current.status()) && (patch.content() != null)) {
            throw new IllegalStateException("已归档的提示词不能改内容，请新建版本");
        }
        String content = current.content();
        if (patch.content() != null) {
            content = requireText(patch.content());
        }
        String status = current.status();
        String activatedBy = current.activatedBy();
        if (patch.status() != null) {
            if (!AskPrompt.STATUSES.contains(patch.status())) {
                throw new IllegalArgumentException("未知的提示词状态：" + patch.status());
            }
            if (AskPrompt.ACTIVE.equals(patch.status()) && AskPrompt.GLOBAL.equals(current.scope()) && !admin) {
                throw forbidden("激活全局提示词需要管理员");
            }
            status = patch.status();
            activatedBy = AskPrompt.ACTIVE.equals(status) ? user.name() : current.activatedBy();
        }
        AskPrompt updated = new AskPrompt(
                current.id(),
                current.scope(),
                current.owner(),
                content,
                status,
                current.source(),
                current.version(),
                current.createdBy(),
                activatedBy,
                current.createdAt(),
                clock.getAsLong());
        if (same(current, updated)) {
            return current;
        }
        AskPrompt saved = store.save(updated);
        if (AskPrompt.ACTIVE.equals(saved.status())) {
            archiveOthers(saved);
        }
        return saved;
    }

    public AskPrompt activate(long id) {
        return update(id, new PromptPatch(null, AskPrompt.ACTIVE));
    }

    public void delete(long id) {
        AskPrompt current = visible(id);
        GatewayUser user = GatewayUser.current();
        if (AskPrompt.GLOBAL.equals(current.scope()) && !user.hasRole(GatewayRole.ADMIN)) {
            throw forbidden("只有管理员能删全局提示词");
        }
        if (AskPrompt.PERSONAL.equals(current.scope())
                && !user.hasRole(GatewayRole.ADMIN)
                && !user.name().equals(current.owner())) {
            throw forbidden("只能删自己的个人提示词");
        }
        store.delete(id);
    }

    /**
     * 管理员：根据开放建议起草一条全局草稿提示词（需人工激活）。
     */
    public AskPrompt proposeFromFeedback() {
        if (!GatewayUser.current().hasRole(GatewayRole.ADMIN)) {
            throw forbidden("从反馈生成提示词需要管理员");
        }
        if (feedback == null || llm == null) {
            throw new IllegalStateException("反馈或模型不可用");
        }
        List<AskSuggestion> open = feedback.listSuggestions(null, AskSuggestion.OPEN);
        if (open.isEmpty()) {
            throw new IllegalArgumentException("没有开放建议可供生成");
        }
        String body = open.stream()
                .map(item -> "- [" + item.userName() + "] " + item.content())
                .collect(Collectors.joining("\n"));
        String draft = llm.model().chat(PROPOSAL_PROMPT + "\n\n开放建议：\n" + body);
        if (draft == null || draft.isBlank()) {
            throw new IllegalStateException("模型未返回提示词草稿");
        }
        return create(AskPrompt.GLOBAL, draft.strip(), AskPrompt.MODEL);
    }

    /** 建议采纳去向=prompt 时写入全局草稿。 */
    public AskPrompt draftFromSuggestion(AskSuggestion suggestion) {
        if (suggestion == null || suggestion.content() == null || suggestion.content().isBlank()) {
            throw new IllegalArgumentException("建议内容为空，无法生成提示词");
        }
        String prefix = suggestion.runQuestion() == null || suggestion.runQuestion().isBlank()
                ? ""
                : "针对问题「" + suggestion.runQuestion() + "」：";
        return create(AskPrompt.GLOBAL, prefix + suggestion.content().strip(), AskPrompt.HUMAN);
    }

    private void archiveOthers(AskPrompt active) {
        List<AskPrompt> peers = AskPrompt.GLOBAL.equals(active.scope())
                ? store.list(AskPrompt.GLOBAL, null)
                : store.list(AskPrompt.PERSONAL, active.owner());
        for (AskPrompt peer : peers) {
            if (peer.id() == active.id() || !AskPrompt.ACTIVE.equals(peer.status())) {
                continue;
            }
            if (AskPrompt.PERSONAL.equals(active.scope()) && !Objects.equals(active.owner(), peer.owner())) {
                continue;
            }
            store.save(new AskPrompt(
                    peer.id(),
                    peer.scope(),
                    peer.owner(),
                    peer.content(),
                    AskPrompt.ARCHIVED,
                    peer.source(),
                    peer.version(),
                    peer.createdBy(),
                    peer.activatedBy(),
                    peer.createdAt(),
                    clock.getAsLong()));
        }
    }

    private int nextVersion(String scope, String owner) {
        List<AskPrompt> peers = AskPrompt.GLOBAL.equals(scope)
                ? store.list(AskPrompt.GLOBAL, null)
                : store.list(AskPrompt.PERSONAL, owner);
        int max = 0;
        for (AskPrompt peer : peers) {
            if (AskPrompt.PERSONAL.equals(scope) && !Objects.equals(owner, peer.owner())) {
                continue;
            }
            max = Math.max(max, peer.version());
        }
        return max + 1;
    }

    private AskPrompt visible(long id) {
        GatewayUser user = GatewayUser.current();
        return store.find(id)
                .filter(item -> user.hasRole(GatewayRole.ADMIN)
                        || AskPrompt.GLOBAL.equals(item.scope())
                        || user.name().equals(item.owner()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提示词不存在：" + id));
    }

    private static void append(StringBuilder out, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        out.append("\n\n## ").append(title).append('\n').append(content.strip());
    }

    private static String requireScope(String scope) {
        if (scope == null || scope.isBlank() || !AskPrompt.SCOPES.contains(scope)) {
            throw new IllegalArgumentException("提示词范围必须是 global 或 personal");
        }
        return scope;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("提示词内容不能为空");
        }
        String text = value.strip();
        if (text.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("提示词不能超过 " + MAX_CONTENT + " 字");
        }
        return text;
    }

    private static boolean same(AskPrompt a, AskPrompt b) {
        return Objects.equals(a.content(), b.content())
                && Objects.equals(a.status(), b.status())
                && Objects.equals(a.activatedBy(), b.activatedBy());
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}

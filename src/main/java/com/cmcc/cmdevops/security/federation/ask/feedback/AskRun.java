package com.cmcc.cmdevops.security.federation.ask.feedback;

import java.util.List;
import java.util.Map;

import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;

/**
 * 一次问数的服务端记录。评价、建议和后续的知识入库都只引用这里的内容，不采信前端回传的 SQL。
 *
 * @param conversationId 同一段对话里的多次问数共用一个 id
 * @param history        发起这次问数时已有的对话（用户与助手的轮次）
 * @param status         {@link #ANSWERED} / {@link #CLARIFY} / {@link #ERROR}
 * @param evidence       支撑回答的证据：口径、推理步骤、结果预览、命中的本体实体、用到的表、SQL 执行轨迹
 * @param createdAt      发起时刻，epoch 毫秒
 */
public record AskRun(
        String id,
        String conversationId,
        String userName,
        String question,
        List<Turn> history,
        String status,
        String answer,
        String sql,
        Map<String, Object> evidence,
        String error,
        long createdAt,
        long elapsedMs) {

    public static final String ANSWERED = "answered";
    public static final String CLARIFY = "clarify";
    public static final String ERROR = "error";
}

package com.cmcc.cmdevops.security.federation.ask.knowledge;

/**
 * MySQL {@code answer} 与 Milvus {@code answer} 统一为「问题答案对」文本。
 */
public final class KnowledgeQaPair {

    private KnowledgeQaPair() {
    }

    /** 拼成「问：…\n答：…」；若已是问答对则原样返回。 */
    public static String format(String question, String answerOrPair) {
        String q = question == null ? "" : question.strip();
        String a = answerOrPair == null ? "" : answerOrPair.strip();
        if (a.startsWith("问：") && a.contains("\n答：")) {
            return a;
        }
        return "问：" + q + "\n答：" + a;
    }

    /** 从问答对中取出答句；无法解析时返回原文。 */
    public static String answerBody(String storedAnswer, String question) {
        if (storedAnswer == null || storedAnswer.isBlank()) {
            return "";
        }
        String raw = storedAnswer.strip();
        String prefix = "问：" + (question == null ? "" : question.strip()) + "\n答：";
        if (raw.startsWith(prefix)) {
            return raw.substring(prefix.length());
        }
        int idx = raw.indexOf("\n答：");
        if (idx >= 0) {
            return raw.substring(idx + "\n答：".length());
        }
        return raw;
    }
}

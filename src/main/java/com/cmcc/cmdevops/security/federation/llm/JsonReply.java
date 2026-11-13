package com.cmcc.cmdevops.security.federation.llm;

public final class JsonReply {

    private JsonReply() {
    }

    /** 从模型回复中取出最外层 JSON 对象，容忍 ```json 代码块和前后说明文字。 */
    public static String extractObject(String reply) {
        if (reply == null) {
            throw new IllegalStateException("大模型没有返回内容");
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            String hint = start >= 0
                    ? "大模型返回的 JSON 没写完（输出被截断）："
                    : "大模型没有返回 JSON：";
            throw new IllegalStateException(hint + abbreviate(reply, 300));
        }
        return reply.substring(start, end + 1);
    }

    public static String abbreviate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}

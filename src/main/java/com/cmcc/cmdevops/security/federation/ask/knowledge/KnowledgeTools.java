package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.knowledge.AskKnowledgeService.Match;

import dev.langchain4j.agent.tool.Tool;

/** 供 llm_chat / Agent 节点绑定的知识库检索工具。 */
@Component
public class KnowledgeTools {

    private final AskKnowledgeService knowledge;

    public KnowledgeTools(AskKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Tool("按用户问题检索已审核的问数知识（问题–答案–SQL），返回最相关的若干条摘要；无命中时返回空列表说明")
    public String searchKnowledge(String question) {
        if (question == null || question.isBlank()) {
            return "问题为空，无法检索";
        }
        List<Match> matches = knowledge.search(question.strip(), AskKnowledgeService.DEFAULT_TOP_K);
        if (matches.isEmpty()) {
            return "知识库无命中";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Match match : matches) {
            AskKnowledge item = match.knowledge();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("score", match.score());
            row.put("question", item.question());
            row.put("answer", item.answerPair().answer());
            row.put("sql", item.answerPair().sql());
            sb.append(i++).append(". ").append(row).append('\n');
        }
        return sb.toString().strip();
    }
}

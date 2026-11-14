package com.cmcc.cmdevops.security.federation.ask.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.AskService.AskRequest;
import com.cmcc.cmdevops.security.federation.ask.AskService.Turn;

class AskRunRecorderTest {

    private final List<String> forwarded = new ArrayList<>();

    @Test
    void forwardsEveryEventToTheCaller() {
        AskRunRecorder recorder = recorder(new AskRequest("有多少告警", List.of()));

        recorder.emit("stage", Map.of("name", "ontology"));
        recorder.emit("answer", Map.of("answer", "42 条"));

        assertEquals(List.of("stage", "answer"), forwarded);
    }

    @Test
    void keepsOnlyTheFirstRowsAsEvidence() {
        AskRunRecorder recorder = recorder(new AskRequest("有多少告警", List.of()));
        List<List<Object>> rows = IntStream.range(0, 50).mapToObj(i -> List.<Object>of(i)).toList();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", "共 50 台");
        answer.put("sql", "SELECT 1");
        answer.put("rows", rows);
        answer.put("rowCount", 50L);

        recorder.emit("answer", answer);
        AskRun run = recorder.toRun(1_500);

        assertEquals(AskRun.ANSWERED, run.status());
        assertEquals("SELECT 1", run.sql());
        assertEquals(rows.subList(0, AskRunRecorder.EVIDENCE_ROWS), run.evidence().get("rows"));
        assertEquals(50L, run.evidence().get("rowCount"));
        assertEquals(500, run.elapsedMs());
    }

    @Test
    void fallsBackToRunIdWhenConversationIdIsNotUsable() {
        for (String requested : Arrays.asList(null, "", "含中文", "a b", "x".repeat(65))) {
            AskRunRecorder recorder = recorder(new AskRequest("q", List.of(), requested));
            assertEquals(recorder.id(), recorder.conversationId(), String.valueOf(requested));
        }
        assertEquals("conv_1-A", recorder(new AskRequest("q", List.of(), "conv_1-A")).conversationId());
    }

    @Test
    void dropsEmptyHistoryTurnsAndTrimsQuestion() {
        AskRunRecorder recorder = recorder(new AskRequest("  有多少告警 ",
                Arrays.asList(new Turn("user", "上一问"), null, new Turn("assistant", null))));

        AskRun run = recorder.toRun(1_000);

        assertEquals("有多少告警", run.question());
        assertEquals(List.of(new Turn("user", "上一问")), run.history());
    }

    @Test
    void errorEventAndMissingAnswerBothEndAsError() {
        AskRunRecorder reported = recorder(new AskRequest("q", List.of()));
        reported.emit("error", Map.of("message", "本体映射的表在元数据中都不存在"));
        assertEquals(AskRun.ERROR, reported.toRun(1_000).status());
        assertEquals("本体映射的表在元数据中都不存在", reported.toRun(1_000).error());

        AskRun silent = recorder(new AskRequest("q", List.of())).toRun(1_000);
        assertEquals(AskRun.ERROR, silent.status());
        assertEquals("问数没有产生回答", silent.error());
        assertNull(silent.answer());
        assertFalse(silent.evidence().containsKey("sqls"));
    }

    private AskRunRecorder recorder(AskRequest request) {
        return new AskRunRecorder(request, "alice", (event, data) -> forwarded.add(event), 1_000);
    }
}

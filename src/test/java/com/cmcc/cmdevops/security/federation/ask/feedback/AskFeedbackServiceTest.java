package com.cmcc.cmdevops.security.federation.ask.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.ask.feedback.AskFeedbackService.SuggestionPatch;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

class AskFeedbackServiceTest {

    private final MemoryAskFeedbackStore store = new MemoryAskFeedbackStore();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AskFeedbackService service = new AskFeedbackService(store, clock::incrementAndGet);

    @BeforeEach
    void seedRuns() {
        store.saveRun(run("run-alice", "alice", AskRun.ANSWERED));
        store.saveRun(run("run-clarify", "alice", AskRun.CLARIFY));
        store.saveRun(run("run-bob", "bob", AskRun.ANSWERED));
    }

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void ownerRatesChangesAndClearsRating() {
        as("alice");

        assertNull(service.run("run-alice").rating());
        assertEquals("up", service.rate("run-alice", "up").rating());
        assertEquals("up", service.run("run-alice").rating());
        assertEquals("down", service.rate("run-alice", "down").rating());
        assertEquals("down", service.run("run-alice").rating());
        assertNull(service.rate("run-alice", null).rating());
        assertNull(service.run("run-alice").rating());
    }

    @Test
    void ratingIsKeptPerUser() {
        as("alice");
        service.rate("run-alice", "up");

        asAdmin("root");
        assertNull(service.run("run-alice").rating());
        service.rate("run-alice", "down");

        as("alice");
        assertEquals("up", service.run("run-alice").rating());
    }

    @Test
    void rejectsUnknownRatingAndUpOnUnanswered() {
        as("alice");

        assertThrows(IllegalArgumentException.class, () -> service.rate("run-alice", "great"));
        assertThrows(IllegalArgumentException.class, () -> service.rate("run-clarify", "up"));
        assertEquals("down", service.rate("run-clarify", "down").rating());
    }

    @Test
    void runsOfOthersLookMissingUnlessAdmin() {
        as("alice");
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.run("run-bob")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.rate("run-bob", "up")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.run("nope")).getStatusCode());

        asAdmin("root");
        assertEquals("bob", service.run("run-bob").run().userName());
    }

    @Test
    void suggestionLinksToVisibleRunAndValidatesContent() {
        as("alice");

        AskSuggestion linked = service.suggest("  高危应该按 level >= 4 算 ", "run-alice");
        assertEquals("高危应该按 level >= 4 算", linked.content());
        assertEquals("run-alice", linked.runId());
        assertEquals("问题 run-alice", linked.runQuestion());
        assertEquals(AskSuggestion.OPEN, linked.status());
        assertEquals("alice", linked.userName());

        assertNull(service.suggest("希望能导出结果", " ").runId());
        assertThrows(IllegalArgumentException.class, () -> service.suggest("  ", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.suggest("字".repeat(AskFeedbackService.MAX_CONTENT + 1), null));
        assertThrows(ResponseStatusException.class, () -> service.suggest("针对别人的问数", "run-bob"));
    }

    @Test
    void listShowsOwnSuggestionsUnlessAdmin() {
        as("alice");
        service.suggest("alice 的建议", null);
        as("bob");
        AskSuggestion bobs = service.suggest("bob 的建议", null);

        assertEquals(List.of("bob 的建议"), contents(service.suggestions(null, false)));

        asAdmin("root");
        assertEquals(List.of("bob 的建议", "alice 的建议"), contents(service.suggestions(null, false)));
        assertTrue(service.suggestions(null, true).isEmpty());

        service.update(bobs.id(), new SuggestionPatch(null, AskSuggestion.REJECTED, null, null));
        assertEquals(List.of("alice 的建议"), contents(service.suggestions(AskSuggestion.OPEN, false)));
        assertEquals(List.of("bob 的建议"), contents(service.suggestions(AskSuggestion.REJECTED, false)));
        assertThrows(IllegalArgumentException.class, () -> service.suggestions("done", false));
    }

    @Test
    void adminAdoptsWithDispositionAndReply() {
        as("alice");
        long id = service.suggest("高危应该按 level >= 4 算", "run-alice").id();

        asAdmin("root");
        AskSuggestion adopted = service.update(id,
                new SuggestionPatch(null, AskSuggestion.ADOPTED, "ontology", " 已改派生概念 "));

        assertEquals(AskSuggestion.ADOPTED, adopted.status());
        assertEquals("ontology", adopted.disposition());
        assertEquals("已改派生概念", adopted.reply());
        assertEquals("root", adopted.handledBy());
        assertTrue(adopted.updatedAt() > adopted.createdAt());
        assertEquals(adopted, store.findSuggestion(id).orElseThrow());

        AskSuggestion reopened = service.update(id, new SuggestionPatch(null, AskSuggestion.OPEN, null, ""));
        assertEquals(AskSuggestion.OPEN, reopened.status());
        assertNull(reopened.disposition());
        assertNull(reopened.reply());
        assertNull(reopened.handledBy());
    }

    @Test
    void dispositionOnlySurvivesOnAdoptedSuggestions() {
        as("alice");
        long id = service.suggest("加个导出", null).id();

        asAdmin("root");
        assertNull(service.update(id, new SuggestionPatch(null, AskSuggestion.REJECTED, "prompt", null)).disposition());
        assertEquals("prompt",
                service.update(id, new SuggestionPatch(null, AskSuggestion.ADOPTED, "prompt", null)).disposition());
        assertNull(service.update(id, new SuggestionPatch(null, null, "", null)).disposition());
        assertThrows(IllegalArgumentException.class,
                () -> service.update(id, new SuggestionPatch(null, null, "trash", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(id, new SuggestionPatch(null, "done", null, null)));
    }

    @Test
    void authorEditsOnlyWhileOpenAndCannotHandle() {
        as("alice");
        long id = service.suggest("加个导出", null).id();

        assertEquals("加个 CSV 导出", service.update(id, new SuggestionPatch("加个 CSV 导出", null, null, null)).content());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.update(id, new SuggestionPatch(null, AskSuggestion.ADOPTED, null, null))).getStatusCode());

        asAdmin("root");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.update(id, new SuggestionPatch("管理员改写", null, null, null))).getStatusCode());
        service.update(id, new SuggestionPatch(null, AskSuggestion.REJECTED, null, null));

        as("alice");
        assertThrows(IllegalStateException.class,
                () -> service.update(id, new SuggestionPatch("再改一次", null, null, null)));
        assertThrows(IllegalStateException.class, () -> service.delete(id));
    }

    @Test
    void unchangedPatchDoesNotTouchTheRecord() {
        as("alice");
        AskSuggestion created = service.suggest("加个导出", null);

        AskSuggestion same = service.update(created.id(), new SuggestionPatch(" 加个导出 ", null, null, null));

        assertEquals(created.updatedAt(), same.updatedAt());
    }

    @Test
    void deleteByAuthorWhileOpenOrByAdmin() {
        as("alice");
        long mine = service.suggest("写错了", null).id();
        long handled = service.suggest("加个导出", null).id();
        service.delete(mine);
        assertTrue(store.findSuggestion(mine).isEmpty());

        as("bob");
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.delete(handled)).getStatusCode());

        asAdmin("root");
        service.update(handled, new SuggestionPatch(null, AskSuggestion.ADOPTED, "knowledge", null));
        service.delete(handled);
        assertTrue(store.findSuggestion(handled).isEmpty());
    }

    private static void as(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY)));
    }

    private static void asAdmin(String name) {
        GatewayUser.set(new GatewayUser(name, Set.of(GatewayRole.QUERY, GatewayRole.ADMIN)));
    }

    private static List<String> contents(List<AskSuggestion> suggestions) {
        return suggestions.stream().map(AskSuggestion::content).toList();
    }

    private static AskRun run(String id, String user, String status) {
        return new AskRun(id, id, user, "问题 " + id, List.of(), status, "回答", "SELECT 1", Map.of(), null, 1_000, 5);
    }
}

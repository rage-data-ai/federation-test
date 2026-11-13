package com.cmcc.cmdevops.security.federation.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftRequest;
import com.cmcc.cmdevops.security.federation.ontology.OntologyDraftService.DraftResult;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;
import com.cmcc.cmdevops.security.federation.ontology.OntologyStore;

@RestController
@RequestMapping("/api/v1/ontology")
public class OntologyController {

    private static final Logger log = LoggerFactory.getLogger(OntologyController.class);
    private static final long DRAFT_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final MediaType TURTLE = new MediaType("text", "turtle", StandardCharsets.UTF_8);

    private final OntologyRepository repository;
    private final OntologyDraftService draftService;
    private final OntologyStore store;

    public OntologyController(OntologyRepository repository, OntologyDraftService draftService, OntologyStore store) {
        this.repository = repository;
        this.draftService = draftService;
        this.store = store;
    }

    @GetMapping
    public Snapshot snapshot() {
        return repository.snapshot();
    }

    @GetMapping("/turtle")
    public ResponseEntity<String> turtle() {
        return ResponseEntity.ok().contentType(TURTLE).body(repository.turtle());
    }

    @PutMapping("/concepts/{name}")
    public Snapshot saveConcept(@PathVariable("name") String name, @RequestBody Concept body) {
        repository.saveConcept(new Concept(name, body.labelZh(), body.labelEn(), body.aliases(), body.comment(),
                body.parent(), body.table(), body.timeColumn(), body.largeTable()));
        return repository.snapshot();
    }

    @PutMapping("/attributes/{name}")
    public Snapshot saveAttribute(@PathVariable("name") String name, @RequestBody Attribute body) {
        repository.saveAttribute(new Attribute(name, body.labelZh(), body.labelEn(), body.aliases(), body.comment(),
                body.domain(), body.datatype(), body.column(), body.unit(), body.identifier(), body.codeList()));
        return repository.snapshot();
    }

    @PutMapping("/relations/{name}")
    public Snapshot saveRelation(@PathVariable("name") String name, @RequestBody Relation body) {
        repository.saveRelation(new Relation(name, body.labelZh(), body.labelEn(), body.aliases(), body.comment(),
                body.domain(), body.range(), body.joinFrom(), body.joinTo(), body.inverse(), body.transitive(),
                body.functional()));
        return repository.snapshot();
    }

    @PutMapping("/codes/{name}")
    public Snapshot saveCode(@PathVariable("name") String name, @RequestBody Code body) {
        repository.saveCode(new Code(name, body.codeList(), body.value(), body.labelZh(), body.labelEn(),
                body.aliases(), body.comment()));
        return repository.snapshot();
    }

    @PutMapping("/defined/{name}")
    public Snapshot saveDefined(@PathVariable("name") String name, @RequestBody DefinedConcept body) {
        repository.saveDefinedConcept(new DefinedConcept(name, body.labelZh(), body.labelEn(), body.aliases(),
                body.comment(), body.base(), body.conditions(), body.definitionText()));
        return repository.snapshot();
    }

    @PostMapping("/reason")
    public Map<String, Object> reason() {
        long started = System.currentTimeMillis();
        store.materialize();
        return Map.of("inferredTriples", store.inferredSize(), "elapsedMs", System.currentTimeMillis() - started);
    }

    @PostMapping(value = "/draft", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter draft(@RequestBody DraftRequest body) {
        SseEmitter emitter = new SseEmitter(DRAFT_TIMEOUT_MS);
        Thread.ofVirtual().name("draft-", 0).start(() -> {
            try {
                DraftResult result = draftService.draft(body, phase -> send(emitter, "phase", phase));
                send(emitter, "result", result);
            } catch (RuntimeException e) {
                log.warn("本体起草失败: {}", e.getMessage());
                send(emitter, "error", Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("起草进度已断开: {}", e.getMessage());
        }
    }

    @DeleteMapping("/entities/{name}")
    public Snapshot delete(@PathVariable("name") String name) {
        repository.delete(name);
        return repository.snapshot();
    }
}

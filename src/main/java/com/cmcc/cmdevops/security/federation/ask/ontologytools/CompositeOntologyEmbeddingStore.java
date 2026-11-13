package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.ArrayList;
import java.util.List;

/**
 * 多后端组合：按顺序加载，命中后回填前面未命中的后端；保存时写全部。
 */
public final class CompositeOntologyEmbeddingStore implements OntologyEmbeddingStore {

    private final List<OntologyEmbeddingStore> delegates;

    public CompositeOntologyEmbeddingStore(List<OntologyEmbeddingStore> delegates) {
        this.delegates = List.copyOf(delegates == null ? List.of() : delegates);
    }

    @Override
    public OntologyEmbeddingIndex load(String fingerprint, String embedderName) {
        List<OntologyEmbeddingStore> miss = new ArrayList<>();
        for (OntologyEmbeddingStore store : delegates) {
            OntologyEmbeddingIndex hit = store.load(fingerprint, embedderName);
            if (hit != null) {
                for (OntologyEmbeddingStore earlier : miss) {
                    earlier.save(hit);
                }
                return hit;
            }
            miss.add(store);
        }
        return null;
    }

    @Override
    public void save(OntologyEmbeddingIndex index) {
        for (OntologyEmbeddingStore store : delegates) {
            store.save(index);
        }
    }
}

package com.cmcc.cmdevops.security.federation.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetadataStoreTest {

    @Test
    void deleteTableRemovesOnlyThatSnapshotAndSurvivesReload() {
        MemoryMetadataStore records = new MemoryMetadataStore();
        MetadataStore store = new MetadataStore(records);
        store.put(table("ck81", "pangu", "alert"));
        store.put(table("mysql70", "tsoc", "asset"));

        assertTrue(store.deleteTable("ck81", "pangu", "alert"));
        assertFalse(store.deleteTable("ck81", "pangu", "alert"));
        assertEquals(1, store.list().size());
        assertEquals("asset", store.list().get(0).getTable());

        MetadataStore reloaded = new MetadataStore(records);
        reloaded.init();
        assertEquals(1, reloaded.list().size());
        assertEquals("mysql70.tsoc.asset", reloaded.list().get(0).key());
    }

    private static TableSnapshot table(String catalog, String schema, String name) {
        TableSnapshot snapshot = new TableSnapshot();
        snapshot.setCatalog(catalog);
        snapshot.setSchema(schema);
        snapshot.setTable(name);
        return snapshot;
    }
}

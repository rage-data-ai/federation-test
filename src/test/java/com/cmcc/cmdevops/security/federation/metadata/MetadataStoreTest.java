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

    @Test
    void replaceFindListByCatalogAndCaseInsensitiveDelete() {
        MemoryMetadataStore records = new MemoryMetadataStore();
        MetadataStore store = new MetadataStore(records);
        store.replaceCatalog("ck81", java.util.List.of(
                table("ck81", "pangu", "alert"),
                table("ck81", "pangu", "event")));
        store.put(table("mysql70", "tsoc", "asset"));

        assertEquals(2, store.listByCatalog("CK81").size());
        assertTrue(store.find("ck81.pangu.alert").isPresent());
        assertTrue(store.find("CK81.PANGU.ALERT").isPresent());
        assertTrue(store.find(null).isEmpty());

        assertTrue(store.deleteTable("CK81", "PANGU", "ALERT"));
        store.deleteCatalog("mysql70");
        assertEquals(1, store.list().size());
        assertEquals("event", store.list().get(0).getTable());

        MemoryMetadataStore memory = new MemoryMetadataStore();
        memory.put(table("A", "s", "t"));
        memory.replaceCatalog("A", java.util.List.of(table("A", "s", "u")));
        assertEquals(1, memory.list().size());
        assertTrue(memory.deleteTable("a", "s", "U"));
        assertFalse(memory.deleteTable("a", "s", "missing"));
        memory.deleteCatalog("A");
        assertTrue(memory.list().isEmpty());
    }

    private static TableSnapshot table(String catalog, String schema, String name) {
        TableSnapshot snapshot = new TableSnapshot();
        snapshot.setCatalog(catalog);
        snapshot.setSchema(schema);
        snapshot.setTable(name);
        return snapshot;
    }
}

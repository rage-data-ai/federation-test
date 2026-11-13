package com.cmcc.cmdevops.security.federation.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.KafkaTopicLister;
import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;

class MetadataServiceCollectTest {

    private MetadataStore store;
    private CatalogRegistry catalogRegistry;
    private QueryExecutor queryExecutor;
    private KafkaTopicLister kafkaTopicLister;
    private MetadataService service;

    @BeforeEach
    void setUp() {
        store = mock(MetadataStore.class);
        catalogRegistry = mock(CatalogRegistry.class);
        queryExecutor = mock(QueryExecutor.class);
        kafkaTopicLister = mock(KafkaTopicLister.class);
        service = new MetadataService(store, catalogRegistry, queryExecutor, kafkaTopicLister);
    }

    @Test
    void listAndDeleteDelegate() {
        when(store.list()).thenReturn(List.of());
        assertTrue(service.list().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> service.delete("", "s", "t"));
        when(store.deleteTable("c", "s", "t")).thenReturn(true);
        assertTrue(service.delete("c", "s", "t"));
    }

    @Test
    void collectRequiresCatalog() {
        assertThrows(IllegalArgumentException.class, () -> service.collect(new CollectRequest()));
        CollectRequest request = new CollectRequest();
        request.setCatalog("missing");
        when(catalogRegistry.get("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.collect(request));
    }

    @Test
    void collectInformationSchemaReplacesCatalog() {
        CatalogRecord record = mysql("mysql70");
        when(catalogRegistry.get("mysql70")).thenReturn(record);
        when(queryExecutor.execute(anyString(), anyInt())).thenReturn(new QueryResponse.ResultSetData(
                List.of(),
                List.of(
                        List.of("information_schema", "tables", "c", "varchar"),
                        List.of("tsoc", "asset", "id", "bigint"),
                        List.of("tsoc", "asset", "name", "varchar"),
                        List.of("tsoc", ".inner.x", "id", "bigint"))));

        CollectRequest request = new CollectRequest();
        request.setCatalog("mysql70");
        List<TableSnapshot> snapshots = service.collect(request);

        assertEquals(1, snapshots.size());
        assertEquals("tsoc", snapshots.get(0).getSchema());
        assertEquals("asset", snapshots.get(0).getTable());
        assertEquals(2, snapshots.get(0).getColumns().size());
        verify(store).replaceCatalog(eq("mysql70"), eq(snapshots));
        verify(store, never()).put(any());
    }

    @Test
    void collectSingleTablePutsSnapshot() {
        CatalogRecord record = mysql("mysql70");
        when(catalogRegistry.get("mysql70")).thenReturn(record);
        when(queryExecutor.execute(contains("table_name = 'asset'"), anyInt()))
                .thenReturn(new QueryResponse.ResultSetData(
                        List.of(),
                        List.of(List.of("tsoc", "asset", "id", "bigint"))));

        CollectRequest request = new CollectRequest();
        request.setCatalog("mysql70");
        request.setSchema("tsoc");
        request.setTable("asset");
        List<TableSnapshot> snapshots = service.collect(request);

        assertEquals(1, snapshots.size());
        verify(store).put(snapshots.get(0));
        verify(store, never()).replaceCatalog(anyString(), any());
    }

    @Test
    void collectSingleTableMissingThrows() {
        CatalogRecord record = mysql("mysql70");
        when(catalogRegistry.get("mysql70")).thenReturn(record);
        when(queryExecutor.execute(anyString(), anyInt()))
                .thenReturn(new QueryResponse.ResultSetData(List.of(), List.of()));

        CollectRequest request = new CollectRequest();
        request.setCatalog("mysql70");
        request.setSchema("tsoc");
        request.setTable("missing");
        assertThrows(IllegalStateException.class, () -> service.collect(request));
    }

    @Test
    void collectClickhouseUsesSystemColumns() {
        CatalogRecord record = new CatalogRecord();
        record.setName("ck81");
        record.setType("clickhouse");
        when(catalogRegistry.get("ck81")).thenReturn(record);
        when(queryExecutor.execute(contains("system.columns"), anyInt()))
                .thenReturn(new QueryResponse.ResultSetData(
                        List.of(),
                        List.of(List.of("pangu", "alert", "id", "UInt64"))));

        CollectRequest request = new CollectRequest();
        request.setCatalog("ck81");
        request.setSchema("pangu");
        List<TableSnapshot> snapshots = service.collect(request);
        assertEquals("alert", snapshots.get(0).getTable());
        verify(store).replaceCatalog(eq("ck81"), eq(snapshots));
    }

    @Test
    void collectKafkaRefreshesTopicsWhenExtraChanges() {
        CatalogRecord record = new CatalogRecord();
        record.setName("kafka81");
        record.setType("kafka");
        record.setExtra("old");
        when(catalogRegistry.get("kafka81")).thenReturn(record);
        when(kafkaTopicLister.list(record)).thenReturn(List.of("__consumer_offsets", "probe", "alert"));
        when(queryExecutor.execute(anyString(), anyInt()))
                .thenReturn(new QueryResponse.ResultSetData(List.of(), List.of()));

        CollectRequest request = new CollectRequest();
        request.setCatalog("kafka81");
        service.collect(request);

        assertEquals("alert,probe", record.getExtra());
        verify(catalogRegistry).save(record, true);
    }

    @Test
    void collectKafkaSkipsSaveWhenExtraUnchanged() {
        CatalogRecord record = new CatalogRecord();
        record.setName("kafka81");
        record.setType("kafka");
        record.setExtra("alert,probe");
        when(catalogRegistry.get("kafka81")).thenReturn(record);
        when(kafkaTopicLister.list(record)).thenReturn(List.of("probe", "alert"));
        when(queryExecutor.execute(anyString(), anyInt()))
                .thenReturn(new QueryResponse.ResultSetData(List.of(), List.of()));

        CollectRequest request = new CollectRequest();
        request.setCatalog("kafka81");
        service.collect(request);
        verify(catalogRegistry, never()).save(any(), anyBoolean());
    }

    @Test
    void singleTableWithoutSchemaFails() {
        CatalogRecord record = mysql("mysql70");
        when(catalogRegistry.get("mysql70")).thenReturn(record);

        CollectRequest request = new CollectRequest();
        request.setCatalog("mysql70");
        request.setTable("asset");
        assertThrows(IllegalArgumentException.class, () -> service.collect(request));
    }

    @Test
    void quoteEscapesUnsafeIdentifiers() {
        assertEquals("mysql70", MetadataService.quote("mysql70"));
        assertEquals("\"weird\"\"name\"", MetadataService.quote("weird\"name"));
    }

    private static CatalogRecord mysql(String name) {
        CatalogRecord record = new CatalogRecord();
        record.setName(name);
        record.setType("mysql");
        return record;
    }
}

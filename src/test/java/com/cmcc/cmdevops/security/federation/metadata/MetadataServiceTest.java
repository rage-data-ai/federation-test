package com.cmcc.cmdevops.security.federation.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetadataServiceTest {

    @Test
    void clickhouseColumnsSqlFiltersSchemaAndTable() {
        String sql = MetadataService.clickhouseColumnsSql("ck81", "pangu", "thbd_config");
        assertTrue(sql.contains("FROM ck81.system.columns"));
        assertTrue(sql.contains("\"table\""));
        assertTrue(sql.contains("database = 'pangu'"));
        assertTrue(sql.contains("\"table\" = 'thbd_config'"));
        assertTrue(sql.contains("database NOT IN ('system', 'information_schema')"));
    }

    @Test
    void skipClickhouseInnerAndSystemTables() {
        assertTrue(MetadataService.skipCollectedTable("system", "columns"));
        assertTrue(MetadataService.skipCollectedTable("pangu", ".inner.cache_event_day_local"));
        assertFalse(MetadataService.skipCollectedTable("pangu", "thbd_config"));
    }

    @Test
    void skipInternalKafkaTopics() {
        assertTrue(MetadataService.skipKafkaTopic("__consumer_offsets"));
        assertFalse(MetadataService.skipKafkaTopic("probe"));
        assertFalse(MetadataService.skipKafkaTopic("my-topic"));
        assertEquals("alert,my-topic,probe", MetadataService.joinKafkaTopics(
                java.util.List.of("__consumer_offsets", "probe", "my-topic", "alert")));
    }
}

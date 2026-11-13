package com.cmcc.cmdevops.security.federation.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrinoCatalogFactoryTest {

    @Test
    void renderMysql() {
        CatalogRecord record = new CatalogRecord();
        record.setName("mysql70");
        record.setType("mysql");
        record.setHost("192.168.19.70");
        record.setPort(3308);
        record.setUsername("tsoc");
        record.setPassword("secret");
        String text = TrinoCatalogFactory.render(record);
        assertTrue(text.contains("connector.name=mysql"));
        assertTrue(text.contains("connection-url=jdbc:mysql://192.168.19.70:3308"));
        assertTrue(text.contains("connection-user=tsoc"));
    }

    @Test
    void mysqlUrlDropsTheDatabase() {
        CatalogRecord record = new CatalogRecord();
        record.setName("mysql70");
        record.setType("mysql");
        record.setHost("192.168.19.70");
        record.setPort(3308);
        record.setDatabase("federal");
        assertTrue(TrinoCatalogFactory.render(record).contains("connection-url=jdbc:mysql://192.168.19.70:3308\n"));

        record.setUrl("jdbc:mysql://192.168.19.70:3308/federal?sslMode=DISABLED&characterEncoding=utf8");
        assertTrue(TrinoCatalogFactory.render(record)
                .contains("connection-url=jdbc:mysql://192.168.19.70:3308/?sslMode=DISABLED&characterEncoding=utf8\n"));
        assertEquals("jdbc:mysql://h:1?a=b/c", TrinoCatalogFactory.withoutDatabase("jdbc:mysql://h:1?a=b/c"));
    }

    @Test
    void createCatalogSqlEscapesProperties() {
        CatalogRecord record = new CatalogRecord();
        record.setName("ck81");
        record.setType("clickhouse");
        record.setHost("192.168.75.81");
        record.setPort(8123);
        record.setDatabase("pangu");
        record.setUsername("pangu");
        record.setPassword("pangu.CK@2021");
        String sql = TrinoCatalogFactory.createCatalogSql(record);
        assertTrue(sql.startsWith("CREATE CATALOG ck81 USING clickhouse"));
        assertTrue(sql.contains("\"connection-url\" = 'jdbc:clickhouse://192.168.75.81:8123/pangu'"));
        assertTrue(sql.contains("\"clickhouse.map-string-as-varchar\" = 'true'"));
        assertEquals("DROP CATALOG IF EXISTS ck81", TrinoCatalogFactory.dropCatalogSql("ck81"));
        String kafkaSql = TrinoCatalogFactory.createCatalogSql(kafkaRecord());
        assertTrue(kafkaSql.contains("\"kafka.table-names\" = 'orders,events.clicks'"));
    }

    private CatalogRecord kafkaRecord() {
        CatalogRecord record = new CatalogRecord();
        record.setName("kafka");
        record.setType("kafka");
        record.setHost("192.168.1.10");
        record.setPort(9092);
        record.setDatabase("default");
        record.setExtra("orders,events.clicks");
        return record;
    }

    @Test
    void renderClickhouseMapsStringAsVarchar() {
        CatalogRecord record = new CatalogRecord();
        record.setName("ck81");
        record.setType("clickhouse");
        record.setHost("192.168.75.81");
        record.setPort(8123);
        record.setDatabase("pangu");
        String text = TrinoCatalogFactory.render(record);
        assertTrue(text.contains("connector.name=clickhouse"));
        assertTrue(text.contains("clickhouse.map-string-as-varchar=true"));
    }

    @Test
    void renderElasticsearch() {
        CatalogRecord record = new CatalogRecord();
        record.setName("es_prod");
        record.setType("elasticsearch");
        record.setHost("10.0.0.8");
        record.setPort(9200);
        record.setDatabase("logs");
        record.setUsername("elastic");
        record.setPassword("secret");
        record.setTls(true);
        String text = TrinoCatalogFactory.render(record);
        assertTrue(text.contains("connector.name=elasticsearch"));
        assertTrue(text.contains("elasticsearch.host=10.0.0.8"));
        assertTrue(text.contains("elasticsearch.default-schema-name=logs"));
        assertTrue(text.contains("elasticsearch.tls.enabled=true"));
        assertEquals("es_prod.properties", TrinoCatalogFactory.fileName(record));
    }

    @Test
    void renderKafkaTopics() {
        CatalogRecord record = new CatalogRecord();
        record.setName("kafka");
        record.setType("kafka");
        record.setHost("192.168.1.10");
        record.setPort(9092);
        record.setDatabase("default");
        record.setExtra("orders,events.clicks");
        String text = TrinoCatalogFactory.render(record);
        assertTrue(text.contains("kafka.nodes=192.168.1.10:9092"));
        assertTrue(text.contains("kafka.table-names=orders,events.clicks"));
        assertTrue(text.contains("kafka.table-description-supplier=FILE"));
    }

    @Test
    void renderKafkaSaslClientConfig() {
        CatalogRecord record = new CatalogRecord();
        record.setName("kafka81");
        record.setType("kafka");
        record.setHost("192.168.75.81");
        record.setPort(9093);
        record.setUsername("kafka");
        record.setPassword("secret+value=");
        String text = TrinoCatalogFactory.render(record);
        assertTrue(text.contains("kafka.nodes=192.168.75.81:9093"));
        assertTrue(text.contains("kafka.config.resources=/etc/trino/kafka/kafka81-client.properties"));
        String client = TrinoCatalogFactory.renderKafkaClientConfig(record);
        assertTrue(client.contains("security.protocol=SASL_PLAINTEXT"));
        assertTrue(client.contains("sasl.mechanism=PLAIN"));
        assertTrue(client.contains("username=\"kafka\""));
        assertTrue(client.contains("password=\"secret+value=\""));
    }
}

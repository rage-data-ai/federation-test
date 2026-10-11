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

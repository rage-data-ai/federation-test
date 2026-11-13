package com.cmcc.cmdevops.security.federation.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.springframework.stereotype.Component;

@Component
public class KafkaTopicLister {

    public List<String> list(CatalogRecord record) {
        Properties props = new Properties();
        int port = record.getPort() == null ? 9092 : record.getPort();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, record.getHost() + ":" + port);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 20_000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 20_000);
        if (TrinoCatalogFactory.hasKafkaSasl(record)) {
            props.put("security.protocol", "SASL_PLAINTEXT");
            props.put("sasl.mechanism", "PLAIN");
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + record.getUsername()
                            + "\" password=\""
                            + record.getPassword()
                            + "\";");
        }
        try (AdminClient admin = AdminClient.create(props)) {
            ListTopicsOptions options = new ListTopicsOptions();
            options.listInternal(false);
            return new ArrayList<>(admin.listTopics(options).names().get(20, TimeUnit.SECONDS))
                    .stream()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("无法列出 Kafka topic: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.getMessage() : current.getMessage();
    }
}

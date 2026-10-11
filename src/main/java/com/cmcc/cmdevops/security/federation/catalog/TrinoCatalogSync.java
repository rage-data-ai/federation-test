package com.cmcc.cmdevops.security.federation.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

@Component
public class TrinoCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(TrinoCatalogSync.class);

    private final Path catalogDir;
    private final Path kafkaConfigDir;

    public TrinoCatalogSync(FederationProperties properties) {
        this.catalogDir = Path.of(properties.getTrino().getCatalogDir());
        this.kafkaConfigDir = Path.of(properties.getTrino().getKafkaConfigDir());
    }

    public void write(CatalogRecord record) {
        if (!TrinoCatalogFactory.needsTrinoFile(record)) {
            return;
        }
        try {
            Files.createDirectories(catalogDir);
            Path file = catalogDir.resolve(TrinoCatalogFactory.fileName(record));
            Files.writeString(file, TrinoCatalogFactory.render(record), StandardCharsets.UTF_8);
            Path clientFile = kafkaConfigDir.resolve(TrinoCatalogFactory.kafkaClientFileName(record));
            if (TrinoCatalogFactory.hasKafkaSasl(record)) {
                Files.createDirectories(kafkaConfigDir);
                Files.writeString(clientFile, TrinoCatalogFactory.renderKafkaClientConfig(record), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(clientFile);
            }
            log.info("已写入 Trino catalog {}", file);
        } catch (IOException e) {
            throw new IllegalStateException("写入 Trino catalog 失败: " + e.getMessage(), e);
        }
    }

    public void delete(String name) {
        if (name == null) {
            return;
        }
        Path file = catalogDir.resolve(name.toLowerCase() + ".properties");
        Path clientFile = kafkaConfigDir.resolve(name.toLowerCase() + "-client.properties");
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(clientFile);
        } catch (IOException e) {
            throw new IllegalStateException("删除 Trino catalog 失败: " + e.getMessage(), e);
        }
    }
}

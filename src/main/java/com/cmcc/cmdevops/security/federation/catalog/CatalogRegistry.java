package com.cmcc.cmdevops.security.federation.catalog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Service
public class CatalogRegistry {

    private static final TypeReference<LinkedHashMap<String, CatalogRecord>> TYPE =
            new TypeReference<>() {
            };

    private final Path storePath;
    private final ObjectMapper objectMapper;
    private final TrinoCatalogSync trinoCatalogSync;
    private final Map<String, CatalogRecord> catalogs = new ConcurrentHashMap<>();

    @Autowired
    public CatalogRegistry(
            FederationProperties properties,
            ObjectMapper objectMapper,
            TrinoCatalogSync trinoCatalogSync) {
        this(Path.of(properties.getCatalogStore()), objectMapper, trinoCatalogSync);
    }

    private CatalogRegistry(Path storePath, ObjectMapper objectMapper, TrinoCatalogSync trinoCatalogSync) {
        this.storePath = storePath;
        this.objectMapper = objectMapper;
        this.trinoCatalogSync = trinoCatalogSync;
    }

    public static CatalogRegistry inMemory() {
        return new CatalogRegistry((Path) null, new ObjectMapper(), null);
    }

    @PostConstruct
    public void init() {
        load();
    }

    public List<CatalogRecord> list() {
        return new ArrayList<>(catalogs.values());
    }

    public CatalogRecord get(String name) {
        if (name == null) {
            return null;
        }
        CatalogRecord exact = catalogs.get(name);
        if (exact != null) {
            return exact;
        }
        return catalogs.values().stream()
                .filter(record -> name.equalsIgnoreCase(record.getName()))
                .findFirst()
                .orElse(null);
    }

    public synchronized CatalogRecord save(CatalogRecord incoming, boolean overwrite) {
        validate(incoming);
        CatalogRecord existing = catalogs.get(incoming.getName());
        if (existing != null && !overwrite) {
            throw new IllegalArgumentException("数据源已存在: " + incoming.getName());
        }
        if (existing != null && (incoming.getPassword() == null || incoming.getPassword().isBlank())) {
            incoming.setPassword(existing.getPassword());
        }
        incoming.setRoute("TRINO");
        catalogs.put(incoming.getName(), incoming);
        if (trinoCatalogSync != null) {
            trinoCatalogSync.write(incoming);
        }
        persist();
        return incoming;
    }

    public synchronized void delete(String name) {
        CatalogRecord removed = catalogs.remove(name);
        if (removed == null) {
            removed = get(name);
            if (removed != null) {
                catalogs.remove(removed.getName());
            }
        }
        if (removed == null) {
            throw new IllegalArgumentException("数据源不存在: " + name);
        }
        if (trinoCatalogSync != null) {
            trinoCatalogSync.delete(removed.getName());
        }
        persist();
    }

    public Map<String, Object> test(CatalogRecord incoming) {
        validate(incoming);
        CatalogRecord existing = catalogs.get(incoming.getName());
        if ((incoming.getPassword() == null || incoming.getPassword().isBlank()) && existing != null) {
            incoming.setPassword(existing.getPassword());
        }
        int port = incoming.getPort() == null ? JdbcUrlFactory.defaultPort(incoming.getType()) : incoming.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(incoming.getHost(), port), 5000);
            return Map.of("success", true, "message", "主机端口可连通，查询走 Trino");
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage() == null ? "连接失败" : e.getMessage());
        }
    }

    private void validate(CatalogRecord record) {
        if (record.getName() == null || !record.getName().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据源名称必须是字母或下划线开头的标识符");
        }
        if (record.getType() == null || record.getType().isBlank()) {
            throw new IllegalArgumentException("请选择数据源类型");
        }
        if (record.getHost() == null || record.getHost().isBlank()) {
            throw new IllegalArgumentException("请填写主机");
        }
        if ("kafka".equalsIgnoreCase(record.getType())
                && record.getExtra() != null
                && record.getExtra().isBlank()) {
            record.setExtra(null);
        }
    }

    private void load() {
        if (storePath == null || !Files.exists(storePath)) {
            return;
        }
        try {
            Map<String, CatalogRecord> loaded = objectMapper.readValue(storePath.toFile(), TYPE);
            loaded.forEach((name, record) -> {
                record.setName(name);
                record.setRoute("TRINO");
                catalogs.put(name, record);
                if (trinoCatalogSync != null) {
                    trinoCatalogSync.write(record);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取数据源配置失败: " + storePath, e);
        }
    }

    private void persist() {
        if (storePath == null) {
            return;
        }
        try {
            Files.createDirectories(storePath.getParent() == null ? Path.of(".") : storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), catalogs);
        } catch (IOException e) {
            throw new IllegalStateException("保存数据源配置失败: " + storePath, e);
        }
    }
}

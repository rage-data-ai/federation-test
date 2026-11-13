package com.cmcc.cmdevops.security.federation.catalog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.engine.CatalogDataSourceRegistry;
import com.cmcc.cmdevops.security.federation.metadata.MetadataStore;

import jakarta.annotation.PostConstruct;

@Service
public class CatalogRegistry {

    private static final Set<String> DIRECT_TYPES = Set.of("mysql", "postgresql", "postgres", "clickhouse", "doris");

    private final CatalogStore catalogStore;
    private final TrinoCatalogSync trinoCatalogSync;
    private final TrinoCatalogApplier trinoCatalogApplier;
    private final MetadataStore metadataStore;
    private final CatalogDataSourceRegistry dataSourceRegistry;
    private final Map<String, CatalogRecord> catalogs = new ConcurrentHashMap<>();

    @Autowired
    public CatalogRegistry(
            CatalogStore catalogStore,
            TrinoCatalogSync trinoCatalogSync,
            TrinoCatalogApplier trinoCatalogApplier,
            MetadataStore metadataStore,
            CatalogDataSourceRegistry dataSourceRegistry) {
        this.catalogStore = catalogStore;
        this.trinoCatalogSync = trinoCatalogSync;
        this.trinoCatalogApplier = trinoCatalogApplier;
        this.metadataStore = metadataStore;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    public static CatalogRegistry inMemory() {
        return new CatalogRegistry(new MemoryCatalogStore(), null, null, null, new CatalogDataSourceRegistry());
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
        normalizeRoute(incoming);
        catalogs.put(incoming.getName(), incoming);
        catalogStore.upsert(incoming);
        syncDirectPool(incoming);
        if (trinoCatalogSync != null) {
            trinoCatalogSync.write(incoming);
        }
        if (trinoCatalogApplier != null) {
            trinoCatalogApplier.apply(incoming);
        }
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
        if (dataSourceRegistry != null) {
            dataSourceRegistry.remove(removed.getName());
        }
        if (trinoCatalogSync != null) {
            trinoCatalogSync.delete(removed.getName());
        }
        if (trinoCatalogApplier != null) {
            trinoCatalogApplier.drop(removed.getName());
        }
        if (metadataStore != null) {
            metadataStore.deleteCatalog(removed.getName());
        }
        catalogStore.delete(removed.getName());
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
            String message = supportsDirectType(incoming.getType())
                    ? "主机端口可连通；单源可直连，跨源走 Trino"
                    : "主机端口可连通，查询走 Trino";
            return Map.of("success", true, "message", message);
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
        for (CatalogRecord record : catalogStore.list()) {
            normalizeRoute(record);
            catalogs.put(record.getName(), record);
            syncDirectPool(record);
            if (trinoCatalogSync != null) {
                trinoCatalogSync.write(record);
            }
        }
    }

    private void normalizeRoute(CatalogRecord record) {
        if (record.getRoute() == null || record.getRoute().isBlank()) {
            record.setRoute(supportsDirectType(record.getType()) ? "DIRECT" : "TRINO");
            return;
        }
        if (!supportsDirectType(record.getType())) {
            record.setRoute("TRINO");
        }
    }

    private void syncDirectPool(CatalogRecord record) {
        if (dataSourceRegistry == null) {
            return;
        }
        if (record.isDirect() && supportsDirectType(record.getType()) && record.resolveDriver() != null) {
            dataSourceRegistry.upsert(record);
        } else {
            dataSourceRegistry.remove(record.getName());
        }
    }

    private static boolean supportsDirectType(String type) {
        if (type == null) {
            return false;
        }
        return DIRECT_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }
}

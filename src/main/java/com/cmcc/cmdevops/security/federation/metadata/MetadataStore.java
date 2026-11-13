package com.cmcc.cmdevops.security.federation.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class MetadataStore {

    private final MetadataRecordStore records;
    private final Map<String, TableSnapshot> tables = new ConcurrentHashMap<>();

    public MetadataStore(MetadataRecordStore records) {
        this.records = records;
    }

    @PostConstruct
    public void init() {
        for (TableSnapshot snapshot : records.list()) {
            tables.put(snapshot.key(), snapshot);
        }
    }

    public List<TableSnapshot> list() {
        return new ArrayList<>(tables.values());
    }

    public Optional<TableSnapshot> find(String qualifiedTable) {
        if (qualifiedTable == null) {
            return Optional.empty();
        }
        TableSnapshot exact = tables.get(qualifiedTable);
        if (exact != null) {
            return Optional.of(exact);
        }
        return tables.values().stream()
                .filter(item -> item.key().equalsIgnoreCase(qualifiedTable))
                .findFirst();
    }

    public List<TableSnapshot> listByCatalog(String catalog) {
        return tables.values().stream()
                .filter(item -> catalog.equalsIgnoreCase(item.getCatalog()))
                .toList();
    }

    public synchronized void replaceCatalog(String catalog, List<TableSnapshot> snapshots) {
        tables.entrySet().removeIf(entry -> catalog.equalsIgnoreCase(entry.getValue().getCatalog()));
        for (TableSnapshot snapshot : snapshots) {
            tables.put(snapshot.key(), snapshot);
        }
        records.replaceCatalog(catalog, snapshots);
    }

    public synchronized void put(TableSnapshot snapshot) {
        tables.put(snapshot.key(), snapshot);
        records.put(snapshot);
    }

    public synchronized void deleteCatalog(String catalog) {
        tables.entrySet().removeIf(entry -> catalog.equalsIgnoreCase(entry.getValue().getCatalog()));
        records.deleteCatalog(catalog);
    }

    /** 只从已采集的元数据里去掉这张表，不碰数据库里的真实表。 */
    public synchronized boolean deleteTable(String catalog, String schema, String table) {
        String key = catalog + "." + schema + "." + table;
        if (tables.remove(key) == null) {
            String found = tables.keySet().stream()
                    .filter(name -> name.equalsIgnoreCase(key))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return false;
            }
            tables.remove(found);
        }
        return records.deleteTable(catalog, schema, table);
    }
}

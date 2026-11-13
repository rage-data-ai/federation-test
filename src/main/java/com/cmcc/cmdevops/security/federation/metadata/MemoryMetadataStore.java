package com.cmcc.cmdevops.security.federation.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MemoryMetadataStore implements MetadataRecordStore {

    private final ConcurrentMap<String, TableSnapshot> tables = new ConcurrentHashMap<>();

    @Override
    public List<TableSnapshot> list() {
        return new ArrayList<>(tables.values());
    }

    @Override
    public void replaceCatalog(String catalog, List<TableSnapshot> snapshots) {
        tables.entrySet().removeIf(entry -> catalog.equalsIgnoreCase(entry.getValue().getCatalog()));
        for (TableSnapshot snapshot : snapshots) {
            tables.put(snapshot.key(), snapshot);
        }
    }

    @Override
    public void put(TableSnapshot snapshot) {
        tables.put(snapshot.key(), snapshot);
    }

    @Override
    public void deleteCatalog(String catalog) {
        tables.entrySet().removeIf(entry -> catalog.equalsIgnoreCase(entry.getValue().getCatalog()));
    }

    @Override
    public boolean deleteTable(String catalog, String schema, String table) {
        String key = catalog + "." + schema + "." + table;
        if (tables.remove(key) != null) {
            return true;
        }
        String found = tables.keySet().stream().filter(name -> name.equalsIgnoreCase(key)).findFirst().orElse(null);
        if (found == null) {
            return false;
        }
        tables.remove(found);
        return true;
    }
}

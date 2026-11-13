package com.cmcc.cmdevops.security.federation.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MemoryCatalogStore implements CatalogStore {

    private final ConcurrentMap<String, CatalogRecord> rows = new ConcurrentHashMap<>();

    @Override
    public List<CatalogRecord> list() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public void upsert(CatalogRecord record) {
        rows.put(record.getName(), record);
    }

    @Override
    public void delete(String name) {
        rows.remove(name);
    }
}

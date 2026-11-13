package com.cmcc.cmdevops.security.federation.metadata;

import java.util.List;

/** 已采集元数据的持久化。表和列分两张表存放。 */
public interface MetadataRecordStore {

    List<TableSnapshot> list();

    void replaceCatalog(String catalog, List<TableSnapshot> snapshots);

    void put(TableSnapshot snapshot);

    void deleteCatalog(String catalog);

    boolean deleteTable(String catalog, String schema, String table);
}

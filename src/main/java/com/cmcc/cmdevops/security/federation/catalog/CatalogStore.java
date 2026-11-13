package com.cmcc.cmdevops.security.federation.catalog;

import java.util.List;

/** 数据源配置的持久化。一行一个数据源。 */
public interface CatalogStore {

    List<CatalogRecord> list();

    void upsert(CatalogRecord record);

    void delete(String name);
}

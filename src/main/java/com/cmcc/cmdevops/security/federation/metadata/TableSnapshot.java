package com.cmcc.cmdevops.security.federation.metadata;

import java.util.ArrayList;
import java.util.List;

public class TableSnapshot {

    private String catalog;
    private String schema;
    private String table;
    private String collectedAt;
    private List<ColumnSnapshot> columns = new ArrayList<>();

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(String collectedAt) {
        this.collectedAt = collectedAt;
    }

    public List<ColumnSnapshot> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnSnapshot> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    public String key() {
        return catalog + "." + schema + "." + table;
    }
}

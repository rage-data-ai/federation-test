package com.cmcc.cmdevops.security.federation.model;

public record TableRef(String catalog, String schema, String table, String alias) {

    public String qualifiedName() {
        if (schema == null || schema.isBlank()) {
            return catalog + "." + table;
        }
        return catalog + "." + schema + "." + table;
    }

    public String schemaTable() {
        if (schema == null || schema.isBlank()) {
            return table;
        }
        return schema + "." + table;
    }
}

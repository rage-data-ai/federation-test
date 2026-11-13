package com.cmcc.cmdevops.security.federation.metadata;

public class ColumnSnapshot {

    private String name;
    private String type;

    public ColumnSnapshot() {
    }

    public ColumnSnapshot(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

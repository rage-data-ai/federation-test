package com.cmcc.cmdevops.security.federation.catalog;

public record CatalogView(
        String name,
        String type,
        String route,
        String host,
        Integer port,
        String database,
        String username,
        String url,
        String extra,
        boolean tls,
        boolean hasPassword
) {
    public static CatalogView from(CatalogRecord record) {
        return new CatalogView(
                record.getName(),
                record.getType(),
                record.getRoute(),
                record.getHost(),
                record.getPort(),
                record.getDatabase(),
                record.getUsername(),
                record.getUrl(),
                record.getExtra(),
                record.isTls(),
                record.getPassword() != null && !record.getPassword().isBlank());
    }
}

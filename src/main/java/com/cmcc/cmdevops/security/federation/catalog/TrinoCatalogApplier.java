package com.cmcc.cmdevops.security.federation.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.engine.QueryExecutor;

@Component
public class TrinoCatalogApplier {

    private static final Logger log = LoggerFactory.getLogger(TrinoCatalogApplier.class);

    private final QueryExecutor queryExecutor;

    public TrinoCatalogApplier(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public void apply(CatalogRecord record) {
        if (!TrinoCatalogFactory.needsTrinoFile(record)) {
            return;
        }
        try {
            queryExecutor.execute(TrinoCatalogFactory.dropCatalogSql(record.getName()));
            queryExecutor.execute(TrinoCatalogFactory.createCatalogSql(record));
            log.info("已热加载 Trino catalog {}", record.getName());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Trino 动态加载 catalog 失败，请确认 Trino 已启动: " + rootMessage(e), e);
        }
    }

    public void drop(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            queryExecutor.execute(TrinoCatalogFactory.dropCatalogSql(name));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Trino 删除 catalog 失败: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.getMessage() : current.getMessage();
    }
}

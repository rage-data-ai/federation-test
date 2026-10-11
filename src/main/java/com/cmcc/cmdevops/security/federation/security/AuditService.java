package com.cmcc.cmdevops.security.federation.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public void log(String user, String originalSql, RoutePlan plan, QueryResponse response) {
        log.info("AUDIT user={} engine={} catalogs={} rows={} elapsedMs={} cached={} success={} sql={}",
                user,
                plan == null ? "-" : plan.engine(),
                plan == null ? "-" : plan.catalogs(),
                response.rowCount(),
                response.elapsedMs(),
                response.cached(),
                response.success(),
                compact(originalSql));
    }

    private String compact(String sql) {
        if (sql == null) {
            return "";
        }
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "..." : compact;
    }
}

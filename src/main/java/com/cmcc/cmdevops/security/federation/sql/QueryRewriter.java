package com.cmcc.cmdevops.security.federation.sql;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.model.AnalyzedQuery;
import com.cmcc.cmdevops.security.federation.model.EngineType;
import com.cmcc.cmdevops.security.federation.model.RoutePlan;
import com.cmcc.cmdevops.security.federation.model.VectorSearchCall;
import com.cmcc.cmdevops.security.federation.vector.VectorHit;

@Component
public class QueryRewriter {

    private final FederationProperties properties;

    public QueryRewriter(FederationProperties properties) {
        this.properties = properties;
    }

    public String applyKafkaMappings(String sql) {
        String rewritten = sql;
        for (Map.Entry<String, String> mapping : properties.getKafkaMappings().entrySet()) {
            rewritten = Pattern.compile("\\b" + Pattern.quote(mapping.getKey()) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(rewritten)
                    .replaceAll(Matcher.quoteReplacement(mapping.getValue()));
        }
        return rewritten;
    }

    public String injectVectorValues(AnalyzedQuery analyzed, Map<String, List<VectorHit>> hitsByPlaceholder) {
        String sql = analyzed.normalizedSql();
        for (VectorSearchCall call : analyzed.vectorSearches()) {
            List<VectorHit> hits = hitsByPlaceholder.getOrDefault(call.placeholder(), List.of());
            sql = sql.replace(call.placeholder(), toValues(hits, call.alias()));
        }
        return sql;
    }

    public String applyRouteRewrite(String sql, RoutePlan plan) {
        if (plan.engine() != EngineType.DIRECT || plan.directCatalog() == null) {
            return sql;
        }
        return Pattern.compile("\\b" + Pattern.quote(plan.directCatalog()) + "\\.", Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .replaceAll("");
    }

    public String toValues(List<VectorHit> hits, String alias) {
        String tableAlias = (alias == null || alias.isBlank()) ? "v" : alias;
        if (hits.isEmpty()) {
            return "(SELECT CAST(NULL AS BIGINT) AS id, CAST(NULL AS DOUBLE) AS score WHERE FALSE) AS " + tableAlias;
        }
        StringBuilder values = new StringBuilder("(VALUES ");
        for (int i = 0; i < hits.size(); i++) {
            VectorHit hit = hits.get(i);
            if (i > 0) {
                values.append(", ");
            }
            values.append("(CAST(")
                    .append(hit.id())
                    .append(" AS BIGINT), CAST(")
                    .append(hit.score())
                    .append(" AS DOUBLE))");
        }
        values.append(") AS ").append(tableAlias).append("(id, score)");
        return values.toString();
    }

    public boolean isGatewayCatalogCommand(String sql) {
        String normalized = sql.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return "SHOW CATALOGS".equals(normalized) || "SHOW CATALOG".equals(normalized);
    }
}

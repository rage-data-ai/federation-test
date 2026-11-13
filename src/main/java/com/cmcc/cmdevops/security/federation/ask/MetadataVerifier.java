package com.cmcc.cmdevops.security.federation.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.AttributeView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.RelationView;
import com.cmcc.cmdevops.security.federation.metadata.CollectRequest;
import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.metadata.MetadataService;
import com.cmcc.cmdevops.security.federation.metadata.MetadataStore;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;

/**
 * Metadata 环节：只核对本体已映射的表，确认表和映射列在已采集的元数据中真实存在，
 * 缺表时按表重采一次。
 */
@Component
public class MetadataVerifier {

    private final Function<String, Optional<TableSnapshot>> lookup;
    private final Consumer<String> collector;

    @Autowired
    public MetadataVerifier(MetadataStore store, MetadataService service) {
        this(store::find, table -> {
            String[] parts = table.split("\\.", 3);
            CollectRequest request = new CollectRequest();
            request.setCatalog(parts[0]);
            request.setSchema(parts[1]);
            request.setTable(parts[2]);
            service.collect(request);
        });
    }

    MetadataVerifier(Function<String, Optional<TableSnapshot>> lookup, Consumer<String> collector) {
        this.lookup = lookup;
        this.collector = collector;
    }

    public MetadataReport verify(BusinessContext context, boolean recover) {
        Map<String, Set<String>> mapped = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, String> timeColumns = new LinkedHashMap<>();
        for (ConceptView concept : context.concepts()) {
            if (concept.table() == null) {
                continue;
            }
            owners.putIfAbsent(concept.table(), concept.name());
            Set<String> columns = mapped.computeIfAbsent(concept.table(), ignored -> new LinkedHashSet<>());
            if (concept.timeColumn() != null) {
                columns.add(concept.timeColumn());
                if (concept.largeTable()) {
                    timeColumns.put(concept.table(), concept.timeColumn());
                }
            }
            concept.attributes().stream().map(AttributeView::column).filter(c -> c != null).forEach(columns::add);
        }
        Map<String, String> conceptTables = new LinkedHashMap<>();
        context.concepts().forEach(c -> conceptTables.put(c.name(), c.table()));
        for (RelationView relation : context.relations()) {
            addJoinColumn(mapped, conceptTables.get(relation.domain()), relation.join(), true);
            addJoinColumn(mapped, conceptTables.get(relation.range()), relation.join(), false);
        }

        List<String> issues = new ArrayList<>();
        List<String> recovered = new ArrayList<>();
        List<TableInfo> tables = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : mapped.entrySet()) {
            String table = entry.getKey();
            Optional<TableSnapshot> snapshot = lookup.apply(table);
            if (snapshot.isEmpty() && recover) {
                try {
                    collector.accept(table);
                    snapshot = lookup.apply(table);
                    if (snapshot.isPresent()) {
                        recovered.add("已重新采集表 " + table);
                    }
                } catch (RuntimeException e) {
                    issues.add("重新采集表 " + table + " 失败：" + e.getMessage());
                }
            }
            if (snapshot.isEmpty()) {
                issues.add("元数据中没有表 " + table + "（概念 " + owners.get(table) + "），请先在控制台采集该表");
                continue;
            }
            Map<String, ColumnSnapshot> byName = new LinkedHashMap<>();
            snapshot.get().getColumns().forEach(c -> byName.put(c.getName().toLowerCase(Locale.ROOT), c));
            List<ColumnInfo> mappedColumns = new ArrayList<>();
            for (String column : entry.getValue()) {
                ColumnSnapshot actual = byName.get(column.toLowerCase(Locale.ROOT));
                if (actual == null) {
                    issues.add("表 " + table + " 中不存在本体映射的列 " + column);
                } else {
                    mappedColumns.add(new ColumnInfo(actual.getName(), actual.getType()));
                }
            }
            List<String> otherColumns = snapshot.get().getColumns().stream()
                    .map(ColumnSnapshot::getName)
                    .filter(name -> entry.getValue().stream().noneMatch(name::equalsIgnoreCase))
                    .toList();
            tables.add(new TableInfo(table, owners.get(table), timeColumns.get(table), mappedColumns, otherColumns));
        }
        return new MetadataReport(tables, issues, recovered);
    }

    private static void addJoinColumn(Map<String, Set<String>> mapped, String table, String join, boolean left) {
        if (table == null || join == null) {
            return;
        }
        String[] sides = join.split(" = ", 2);
        String side = left ? sides[0] : sides[1];
        String column = side.substring(side.lastIndexOf('.') + 1);
        mapped.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(column);
    }

    public record ColumnInfo(String name, String type) {
    }

    /**
     * @param timeColumn 仅大表有值，生成 SQL 时必须按它做范围过滤
     */
    public record TableInfo(String table, String concept, String timeColumn, List<ColumnInfo> mappedColumns,
                            List<String> otherColumns) {
    }

    public record MetadataReport(List<TableInfo> tables, List<String> issues, List<String> recovered) {

        public SqlGuard.Scope scope() {
            Set<String> names = new LinkedHashSet<>();
            Map<String, String> large = new LinkedHashMap<>();
            for (TableInfo table : tables) {
                String key = table.table().toLowerCase(Locale.ROOT);
                names.add(key);
                if (table.timeColumn() != null) {
                    large.put(key, table.timeColumn());
                }
            }
            return new SqlGuard.Scope(names, large);
        }
    }
}

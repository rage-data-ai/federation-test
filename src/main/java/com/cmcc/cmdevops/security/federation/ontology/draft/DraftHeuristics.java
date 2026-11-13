package com.cmcc.cmdevops.security.federation.ontology.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cmcc.cmdevops.security.federation.metadata.ColumnSnapshot;
import com.cmcc.cmdevops.security.federation.ontology.OntologyRepository;

public final class DraftHeuristics {

    private DraftHeuristics() {
    }

    public static List<ColumnSnapshot> businessColumns(List<ColumnSnapshot> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<ColumnSnapshot> business = columns.stream().filter(column -> !technicalColumn(column.getName())).toList();
        return business.isEmpty() ? List.copyOf(columns) : business;
    }

    public static boolean technicalColumn(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.startsWith("standby") || normalized.startsWith("ext") || normalized.startsWith("_partition")
                || normalized.equals("version") || normalized.equals("sign");
    }

    public static boolean codeLike(String name, List<String> samples) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("_", "");
        List<String> values = samples == null ? List.of() : samples.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (normalized.endsWith("name") || normalized.endsWith("host") || normalized.endsWith("path")
                || normalized.endsWith("description") || normalized.endsWith("reason") || normalized.endsWith("message")
                || normalized.endsWith("user") || normalized.endsWith("comment")) {
            return false;
        }
        if (values.stream().anyMatch(value -> value.length() > 24)) {
            return false;
        }
        if (normalized.endsWith("type") || normalized.endsWith("status") || normalized.endsWith("level")
                || normalized.endsWith("code") || normalized.endsWith("kind") || normalized.endsWith("category")
                || normalized.endsWith("state") || normalized.endsWith("flag") || normalized.endsWith("result")) {
            return true;
        }
        return enumerated(values);
    }

    private static boolean enumerated(List<String> values) {
        if (values.isEmpty() || values.stream().anyMatch(value -> value.length() > 6)) {
            return false;
        }
        long distinct = values.stream().distinct().count();
        if (distinct < values.size()) {
            return true;
        }
        return values.stream().allMatch(value -> value.matches("\\d{1,4}"));
    }
    public static String normalizeDatatype(String modelType, String physical) {
        String fromPhysical = datatypeOf(physical);
        if (modelType == null || modelType.isBlank() || !OntologyRepository.DATATYPES.contains(modelType)) {
            return fromPhysical == null ? "string" : fromPhysical;
        }
        if (fromPhysical == null) {
            return modelType;
        }
        if ("string".equals(modelType) && ("integer".equals(fromPhysical) || "long".equals(fromPhysical)
                || "decimal".equals(fromPhysical) || "double".equals(fromPhysical) || "boolean".equals(fromPhysical)
                || "date".equals(fromPhysical) || "dateTime".equals(fromPhysical))) {
            return fromPhysical;
        }
        if (("integer".equals(modelType) || "long".equals(modelType))
                && ("date".equals(fromPhysical) || "dateTime".equals(fromPhysical) || "boolean".equals(fromPhysical))) {
            return fromPhysical;
        }
        return modelType;
    }
    private static String datatypeOf(String physical) {
        if (physical == null || physical.isBlank()) {
            return null;
        }
        String type = physical.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("datetime") || type.contains("timestamp")) {
            return "dateTime";
        }
        if (type.equals("date")) {
            return "date";
        }
        if (type.contains("decimal") || type.contains("numeric")) {
            return "decimal";
        }
        if (type.contains("float") || type.contains("double")) {
            return "double";
        }
        if (type.contains("uint64") || type.contains("int64") || type.contains("bigint")) {
            return "long";
        }
        if (type.contains("int") || type.equals("long")) {
            return "integer";
        }
        return null;
    }
    public static List<List<ColumnSnapshot>> columnBatches(List<ColumnSnapshot> columns, int size) {
        List<List<ColumnSnapshot>> batches = new ArrayList<>();
        for (int i = 0; i < columns.size(); i += size) {
            batches.add(List.copyOf(columns.subList(i, Math.min(i + size, columns.size()))));
        }
        return batches;
    }
}

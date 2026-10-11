package com.cmcc.cmdevops.security.federation.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class InMemoryVectorSearchClient implements VectorSearchClient {

    private static final List<Item> PRODUCTS = List.of(
            new Item(1, "phone", List.of(1.0, 0.0, 0.0)),
            new Item(2, "phone", List.of(0.92, 0.08, 0.0)),
            new Item(3, "laptop", List.of(0.0, 1.0, 0.0)),
            new Item(4, "phone", List.of(0.85, 0.1, 0.05)),
            new Item(5, "tablet", List.of(0.1, 0.2, 0.97))
    );

    @Override
    public List<VectorHit> search(String collection, String field, List<Double> vector, int k, String filter) {
        if (collection == null || !collection.toLowerCase(Locale.ROOT).contains("product")) {
            return List.of();
        }
        List<VectorHit> hits = new ArrayList<>();
        for (Item item : PRODUCTS) {
            if (!matchFilter(item, filter)) {
                continue;
            }
            hits.add(new VectorHit(item.id, cosine(vector, item.vector)));
        }
        hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    private boolean matchFilter(Item item, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String normalized = filter.replace("\"", "'").replace(" ", "");
        int eq = normalized.indexOf("==");
        if (eq < 0) {
            return true;
        }
        String field = normalized.substring(0, eq);
        String value = unquote(normalized.substring(eq + 2));
        return "category".equalsIgnoreCase(field) && item.category.equalsIgnoreCase(value);
    }

    private static double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        double dot = 0;
        double ln = 0;
        double rn = 0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            ln += l * l;
            rn += r * r;
        }
        if (ln == 0 || rn == 0) {
            return 0;
        }
        return dot / (Math.sqrt(ln) * Math.sqrt(rn));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record Item(long id, String category, List<Double> vector) {
    }
}

package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.JoinPath;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.PathStep;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;

/** Graph traversal for concept-to-concept join paths. */
public final class OntologyPathFinder {

    private static final int MAX_PATHS = 5;

    private final int maxDepth;

    public OntologyPathFinder(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public List<JoinPath> findPaths(OntologyIndex index, String from, String to) {
        String start = index.conceptFor(from);
        String goal = index.conceptFor(to);
        if (start == null || goal == null) {
            return List.of();
        }
        if (start.equals(goal)) {
            return List.of(new JoinPath(start, goal, List.of()));
        }
        Map<String, List<PathStep>> edges = new HashMap<>();
        for (Relation relation : index.relations.values()) {
            if (relation.domain() == null || relation.range() == null) {
                continue;
            }
            String join = joinText(index, relation);
            edges.computeIfAbsent(relation.domain(), ignored -> new ArrayList<>())
                    .add(new PathStep(relation.name(), relation.labelZh(), relation.domain(), relation.range(), join, false));
            edges.computeIfAbsent(relation.range(), ignored -> new ArrayList<>())
                    .add(new PathStep(relation.name(), relation.labelZh(), relation.range(), relation.domain(), join, true));
        }
        List<JoinPath> found = new ArrayList<>();
        Deque<List<PathStep>> queue = new ArrayDeque<>();
        queue.add(List.of());
        int shortest = Integer.MAX_VALUE;
        while (!queue.isEmpty() && found.size() < MAX_PATHS) {
            List<PathStep> path = queue.poll();
            if (path.size() >= maxDepth || path.size() >= shortest) {
                continue;
            }
            String node = path.isEmpty() ? start : path.get(path.size() - 1).to();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(start);
            path.forEach(step -> visited.add(step.to()));
            for (PathStep step : edges.getOrDefault(node, List.of())) {
                if (visited.contains(step.to())) {
                    continue;
                }
                List<PathStep> next = new ArrayList<>(path);
                next.add(step);
                if (step.to().equals(goal)) {
                    shortest = next.size();
                    found.add(new JoinPath(start, goal, next));
                } else {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    public static String joinText(OntologyIndex index, Relation relation) {
        if (relation.joinFrom() == null || relation.joinTo() == null) {
            return null;
        }
        return tableOf(index, relation.domain()) + "." + relation.joinFrom()
                + " = " + tableOf(index, relation.range()) + "." + relation.joinTo();
    }

    public static String tableOf(OntologyIndex index, String concept) {
        Concept c = index.concepts.get(concept);
        return c == null || c.table() == null ? concept : c.table();
    }
}

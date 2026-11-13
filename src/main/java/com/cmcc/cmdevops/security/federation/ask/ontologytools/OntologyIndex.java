package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

/** Lookup maps over one ontology snapshot. */
public final class OntologyIndex {

    public final Map<String, Concept> concepts;
    public final Map<String, Attribute> attributes;
    public final Map<String, Relation> relations;
    public final Map<String, DefinedConcept> defined;
    public final Map<String, Code> codes;

    public OntologyIndex(Snapshot snapshot) {
        concepts = byName(snapshot.concepts(), Concept::name);
        attributes = byName(snapshot.attributes(), Attribute::name);
        relations = byName(snapshot.relations(), Relation::name);
        defined = byName(snapshot.definedConcepts(), DefinedConcept::name);
        codes = byName(snapshot.codes(), Code::name);
    }

    /** 码值集合只属于一个概念的一个属性（由本体仓库保证）。 */
    public Attribute codeOwner(String codeList) {
        if (codeList == null) {
            return null;
        }
        return attributes.values().stream()
                .filter(attribute -> codeList.equals(attribute.codeList()))
                .findFirst()
                .orElse(null);
    }

    public String kindOf(String name) {
        if (concepts.containsKey(name)) {
            return "concept";
        }
        if (attributes.containsKey(name)) {
            return "attribute";
        }
        if (relations.containsKey(name)) {
            return "relation";
        }
        if (defined.containsKey(name)) {
            return "defined";
        }
        return codes.containsKey(name) ? "code" : null;
    }

    public String conceptFor(String name) {
        if (concepts.containsKey(name)) {
            return name;
        }
        DefinedConcept d = defined.get(name);
        return d == null ? null : d.base();
    }

    public String labelOf(String name) {
        if (concepts.containsKey(name)) {
            return concepts.get(name).labelZh();
        }
        if (attributes.containsKey(name)) {
            return attributes.get(name).labelZh();
        }
        if (relations.containsKey(name)) {
            return relations.get(name).labelZh();
        }
        if (defined.containsKey(name)) {
            return defined.get(name).labelZh();
        }
        return codes.containsKey(name) ? codes.get(name).labelZh() : null;
    }

    private static <T> Map<String, T> byName(List<T> items, Function<T, String> name) {
        Map<String, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(name.apply(item), item));
        return map;
    }
}

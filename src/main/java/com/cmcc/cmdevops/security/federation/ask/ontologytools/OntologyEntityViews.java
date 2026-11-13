package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.cmcc.cmdevops.security.federation.ask.OntologyTools.AttributeView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.CodeView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.ConceptView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.DefinedView;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.RelationView;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyQueryService;
import com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary;

/** Builds tool-facing views of ontology entities. */
public final class OntologyEntityViews {

    private static final Set<String> TEXT_TYPES = Set.of("string", "date", "dateTime");

    private final OntologyQueryService queryService;

    public OntologyEntityViews(OntologyQueryService queryService) {
        this.queryService = queryService;
    }

    public ConceptView conceptView(OntologyIndex index, String name) {
        Concept concept = index.concepts.get(name);
        List<AttributeView> attributes = index.attributes.values().stream()
                .filter(attribute -> name.equals(attribute.domain()))
                .map(attribute -> attributeView(index, attribute))
                .toList();
        List<String> related = index.relations.values().stream()
                .filter(relation -> name.equals(relation.domain()) || name.equals(relation.range()))
                .map(Relation::name)
                .toList();
        List<String> derived = index.defined.values().stream()
                .filter(defined -> name.equals(defined.base()))
                .map(DefinedConcept::name)
                .toList();
        return new ConceptView(concept.name(), concept.labelZh(), concept.aliases(), concept.comment(), concept.parent(),
                subConcepts(name), concept.table(), concept.timeColumn(), concept.largeTable(), concept.certified(),
                attributes, related, derived);
    }

    public List<String> subConcepts(String name) {
        String sparql = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "SELECT DISTINCT ?c WHERE { ?c rdfs:subClassOf <" + OntologyVocabulary.iri(name) + "> "
                + "FILTER(isIRI(?c) && ?c != <" + OntologyVocabulary.iri(name) + "> "
                + "&& STRSTARTS(STR(?c), \"" + OntologyVocabulary.SOC + "\")) } ORDER BY ?c";
        return queryService.select(sparql, true).rows().stream()
                .map(row -> OntologyVocabulary.localName(row.get(0)))
                .toList();
    }

    public static CodeView codeView(OntologyIndex index, Code code) {
        Attribute owner = index.codeOwner(code.codeList());
        return new CodeView(code.name(), code.codeList(), code.value(), code.labelZh(), code.aliases(),
                owner == null ? null : owner.domain(),
                owner == null ? null : owner.name(),
                owner == null ? null : owner.column());
    }

    public static AttributeView attributeView(OntologyIndex index, Attribute attribute) {
        List<CodeView> codes = attribute.codeList() == null ? List.of() : index.codes.values().stream()
                .filter(code -> attribute.codeList().equals(code.codeList()))
                .map(code -> codeView(index, code))
                .toList();
        return new AttributeView(attribute.name(), attribute.labelZh(), attribute.aliases(), attribute.comment(),
                attribute.domain(), attribute.column(), attribute.datatype(), attribute.unit(), attribute.identifier(),
                attribute.codeList(), codes);
    }

    public static RelationView relationView(OntologyIndex index, Relation relation) {
        return new RelationView(relation.name(), relation.labelZh(), relation.aliases(), relation.comment(),
                relation.domain(), relation.range(), OntologyPathFinder.joinText(index, relation), relation.inverse(),
                relation.transitive(), relation.functional());
    }

    public static DefinedView definedView(OntologyIndex index, DefinedConcept defined) {
        List<String> readable = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        for (Condition condition : defined.conditions()) {
            Attribute attribute = index.attributes.get(condition.attribute());
            String label = attribute == null || attribute.labelZh() == null ? condition.attribute() : attribute.labelZh();
            readable.add(label + " " + condition.operator() + " " + condition.value());
            if (attribute != null && attribute.column() != null) {
                boolean text = TEXT_TYPES.contains(Objects.toString(attribute.datatype(), "string"));
                String value = text ? "'" + condition.value().replace("'", "''") + "'" : condition.value();
                sql.add(attribute.column() + " " + condition.operator() + " " + value);
            }
        }
        Concept base = index.concepts.get(defined.base());
        return new DefinedView(defined.name(), defined.labelZh(), defined.aliases(), defined.comment(), defined.base(),
                base == null ? null : base.table(), readable,
                sql.isEmpty() ? null : String.join(" AND ", sql), defined.definitionText(), defined.sqlExpression(),
                defined.certified());
    }
}

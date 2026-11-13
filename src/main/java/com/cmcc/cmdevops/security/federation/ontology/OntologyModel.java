package com.cmcc.cmdevops.security.federation.ontology;

import java.util.List;

/**
 * 控制台、起草和智能体共用的本体视图。名称即 IRI 的本地名，物理映射只写列名，表在所属类上。
 */
public final class OntologyModel {

    private OntologyModel() {
    }

    public record Concept(
            String name,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment,
            String parent,
            String table,
            String timeColumn,
            boolean largeTable) {
    }

    public record Attribute(
            String name,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment,
            String domain,
            String datatype,
            String column,
            String unit,
            boolean identifier,
            String codeList) {
    }

    public record Relation(
            String name,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment,
            String domain,
            String range,
            String joinFrom,
            String joinTo,
            String inverse,
            boolean transitive,
            boolean functional) {
    }

    public record Code(
            String name,
            String codeList,
            String value,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment) {
    }

    /** operator 取 =、>、>=、<、<= */
    public record Condition(String attribute, String operator, String value) {
    }

    public record DefinedConcept(
            String name,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment,
            String base,
            List<Condition> conditions,
            String definitionText) {
    }

    public record Snapshot(
            List<Concept> concepts,
            List<Attribute> attributes,
            List<Relation> relations,
            List<Code> codes,
            List<DefinedConcept> definedConcepts) {
    }
}

package com.cmcc.cmdevops.security.federation.ontology;

import java.util.List;

/**
 * 控制台、起草和智能体共用的本体视图。名称即 IRI 的本地名，物理映射只写列名，表在所属类上。
 */
public final class OntologyModel {

    private OntologyModel() {
    }

    /**
     * @param subject 主题域。属性、码值、派生概念跟随所属概念；只有同主题域的概念之间能建立关系。
     *                为空表示未分主题域，未分主题域的概念之间也算同一组。
     */
    public record Concept(
            String name,
            String labelZh,
            String labelEn,
            List<String> aliases,
            String comment,
            String parent,
            String table,
            String timeColumn,
            boolean largeTable,
            String subject,
            boolean certified) {

        public Concept(String name, String labelZh, String labelEn, List<String> aliases, String comment,
                       String parent, String table, String timeColumn, boolean largeTable) {
            this(name, labelZh, labelEn, aliases, comment, parent, table, timeColumn, largeTable, null, false);
        }

        public Concept(String name, String labelZh, String labelEn, List<String> aliases, String comment,
                       String parent, String table, String timeColumn, boolean largeTable, String subject) {
            this(name, labelZh, labelEn, aliases, comment, parent, table, timeColumn, largeTable, subject, false);
        }

        public Concept withSubject(String subject) {
            return new Concept(name, labelZh, labelEn, aliases, comment, parent, table, timeColumn, largeTable, subject,
                    certified);
        }

        public Concept withCertified(boolean certified) {
            return new Concept(name, labelZh, labelEn, aliases, comment, parent, table, timeColumn, largeTable, subject,
                    certified);
        }
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
            String definitionText,
            /** 可复用 SQL 表达式（对齐 Auto Ontology SQL attributes），如 COUNT(*) / SUM(amount)；非过滤条件 */
            String sqlExpression,
            boolean certified) {

        /** 兼容旧调用：无度量表达式 */
        public DefinedConcept(
                String name,
                String labelZh,
                String labelEn,
                List<String> aliases,
                String comment,
                String base,
                List<Condition> conditions,
                String definitionText) {
            this(name, labelZh, labelEn, aliases, comment, base, conditions, definitionText, null, false);
        }

        public DefinedConcept(
                String name,
                String labelZh,
                String labelEn,
                List<String> aliases,
                String comment,
                String base,
                List<Condition> conditions,
                String definitionText,
                String sqlExpression) {
            this(name, labelZh, labelEn, aliases, comment, base, conditions, definitionText, sqlExpression, false);
        }

        public DefinedConcept withCertified(boolean certified) {
            return new DefinedConcept(name, labelZh, labelEn, aliases, comment, base, conditions, definitionText,
                    sqlExpression, certified);
        }
    }

    public record Snapshot(
            List<Concept> concepts,
            List<Attribute> attributes,
            List<Relation> relations,
            List<Code> codes,
            List<DefinedConcept> definedConcepts) {
    }
}

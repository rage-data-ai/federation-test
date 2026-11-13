package com.cmcc.cmdevops.security.federation.ontology;

import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * 业务本体使用的命名空间与映射注解。
 * 业务实体放在 {@link #SOC}，物理映射等网关自定义注解放在 {@link #FED}。
 */
public final class OntologyVocabulary {

    public static final String SOC = "https://cmdevops.cmcc.com/ontology/soc#";
    public static final String FED = "https://cmdevops.cmcc.com/ontology/fed#";
    public static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    public static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public static final Property ALT_LABEL = property(SKOS, "altLabel");

    /** 类 → 物理表 catalog.schema.table */
    public static final Property MAPPED_TABLE = property(FED, "mappedTable");
    /** 数据属性 → 列名 */
    public static final Property MAPPED_COLUMN = property(FED, "mappedColumn");
    /** 类上的时间列；带 {@link #LARGE_TABLE} 时查询必须过滤该列 */
    public static final Property TIME_COLUMN = property(FED, "timeColumn");
    public static final Property LARGE_TABLE = property(FED, "largeTable");
    /** 类 → 主题域名称。只有同主题域的类之间能建立关系 */
    public static final Property SUBJECT = property(FED, "subject");
    /** 对象属性的 Join：domain 表上的列 = range 表上的列 */
    public static final Property JOIN_FROM = property(FED, "joinFromColumn");
    public static final Property JOIN_TO = property(FED, "joinToColumn");
    public static final Property UNIT = property(FED, "unit");
    public static final Property IDENTIFIER = property(FED, "identifier");
    /** 数据属性 → 码值集合（一个 owl:Class，其个体即码值） */
    public static final Property CODE_LIST = property(FED, "codeList");
    public static final Property CODE_VALUE = property(FED, "codeValue");
    public static final Resource VALUE_SET = ResourceFactory.createResource(FED + "ValueSet");
    /** 派生概念中规则推理器无法表达的部分，例如聚合条件，用自然语言描述 */
    public static final Property DEFINITION_TEXT = property(FED, "definitionText");
    /** 可复用度量 / SQL 表达式（SELECT 片段），非 WHERE 条件 */
    public static final Property SQL_EXPRESSION = property(FED, "sqlExpression");

    private OntologyVocabulary() {
    }

    public static String iri(String name) {
        return SOC + name;
    }

    public static String localName(String iri) {
        if (iri == null) {
            return null;
        }
        int hash = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return hash >= 0 ? iri.substring(hash + 1) : iri;
    }

    public static String requireName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " 名称只能用字母开头的字母、数字、下划线: " + name);
        }
        return name;
    }

    private static Property property(String ns, String local) {
        return ResourceFactory.createProperty(ns, local);
    }
}

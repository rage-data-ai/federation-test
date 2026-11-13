package com.cmcc.cmdevops.security.federation.ontology;

import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.ALT_LABEL;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.CODE_LIST;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.CODE_VALUE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.DEFINITION_TEXT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.IDENTIFIER;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.JOIN_FROM;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.JOIN_TO;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.LARGE_TABLE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.MAPPED_COLUMN;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.MAPPED_TABLE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.SUBJECT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.TIME_COLUMN;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.UNIT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.VALUE_SET;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.iri;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.localName;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.requireName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

/**
 * RDF 读写适配：快照装配、限制条件、以及资源级读写辅助。
 */
final class OntologyRdfIO {

    static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    private static final Map<String, String> FACETS = Map.of(
            ">=", "minInclusive",
            ">", "minExclusive",
            "<=", "maxInclusive",
            "<", "maxExclusive");

    private OntologyRdfIO() {
    }

    static Snapshot snapshotOf(Model model) {
        List<Concept> concepts = new ArrayList<>();
        List<DefinedConcept> defined = new ArrayList<>();
        model.listSubjectsWithProperty(RDF.type, OWL2.Class).forEachRemaining(r -> {
            if (r.isAnon() || r.hasProperty(RDF.type, VALUE_SET)) {
                return;
            }
            if (r.hasProperty(OWL2.equivalentClass)) {
                defined.add(readDefined(model, r));
            } else {
                concepts.add(new Concept(
                        localName(r.getURI()), label(r, "zh"), label(r, "en"), aliases(r), comment(r),
                        objectName(r, RDFS.subClassOf), string(r, MAPPED_TABLE), string(r, TIME_COLUMN),
                        bool(r, LARGE_TABLE), string(r, SUBJECT)));
            }
        });

        List<Attribute> attributes = new ArrayList<>();
        model.listSubjectsWithProperty(RDF.type, OWL2.DatatypeProperty).forEachRemaining(r -> attributes.add(new Attribute(
                localName(r.getURI()), label(r, "zh"), label(r, "en"), aliases(r), comment(r),
                objectName(r, RDFS.domain), objectName(r, RDFS.range), string(r, MAPPED_COLUMN), string(r, UNIT),
                bool(r, IDENTIFIER), objectName(r, CODE_LIST))));

        List<Relation> relations = new ArrayList<>();
        model.listSubjectsWithProperty(RDF.type, OWL2.ObjectProperty).forEachRemaining(r -> {
            if (isInverseOnly(r)) {
                return;
            }
            Resource inverse = model.listSubjectsWithProperty(OWL2.inverseOf, r).nextOptional().orElse(null);
            relations.add(new Relation(
                    localName(r.getURI()), label(r, "zh"), label(r, "en"), aliases(r), comment(r),
                    objectName(r, RDFS.domain), objectName(r, RDFS.range), string(r, JOIN_FROM), string(r, JOIN_TO),
                    inverse == null ? null : localName(inverse.getURI()),
                    r.hasProperty(RDF.type, OWL2.TransitiveProperty),
                    r.hasProperty(RDF.type, OWL2.FunctionalProperty)));
        });

        List<Code> codes = new ArrayList<>();
        model.listSubjectsWithProperty(RDF.type, OWL2.NamedIndividual).forEachRemaining(r -> {
            String codeList = r.listProperties(RDF.type).toList().stream()
                    .map(Statement::getResource)
                    .filter(type -> type.hasProperty(RDF.type, VALUE_SET))
                    .map(type -> localName(type.getURI()))
                    .findFirst()
                    .orElse(null);
            codes.add(new Code(localName(r.getURI()), codeList, string(r, CODE_VALUE),
                    label(r, "zh"), label(r, "en"), aliases(r), comment(r)));
        });

        concepts.sort(Comparator.comparing(Concept::name));
        attributes.sort(Comparator.comparing(Attribute::name));
        relations.sort(Comparator.comparing(Relation::name));
        codes.sort(Comparator.comparing(Code::name));
        defined.sort(Comparator.comparing(DefinedConcept::name));
        return new Snapshot(concepts, attributes, relations, codes, defined);
    }

    private static DefinedConcept readDefined(Model model, Resource r) {
        Resource intersection = r.getPropertyResourceValue(OWL2.equivalentClass);
        String base = null;
        List<Condition> conditions = new ArrayList<>();
        Resource list = intersection == null ? null : intersection.getPropertyResourceValue(OWL2.intersectionOf);
        if (list != null) {
            for (RDFNode member : list.as(RDFList.class).asJavaList()) {
                if (member.isURIResource()) {
                    base = localName(member.asResource().getURI());
                } else {
                    Condition condition = readRestriction(member.asResource());
                    if (condition != null) {
                        conditions.add(condition);
                    }
                }
            }
        }
        return new DefinedConcept(localName(r.getURI()), label(r, "zh"), label(r, "en"), aliases(r), comment(r),
                base, conditions, string(r, DEFINITION_TEXT));
    }

    private static Condition readRestriction(Resource restriction) {
        String attribute = objectName(restriction, OWL2.onProperty);
        if (restriction.hasProperty(OWL2.hasValue)) {
            return new Condition(attribute, "=", lexical(restriction.getProperty(OWL2.hasValue).getObject()));
        }
        Resource datatype = restriction.getPropertyResourceValue(OWL2.someValuesFrom);
        Resource facets = datatype == null ? null : datatype.getPropertyResourceValue(OWL2.withRestrictions);
        if (facets == null) {
            return null;
        }
        for (RDFNode facet : facets.as(RDFList.class).asJavaList()) {
            for (Map.Entry<String, String> entry : FACETS.entrySet()) {
                Statement value = facet.asResource().getProperty(facetProperty(entry.getValue()));
                if (value != null) {
                    return new Condition(attribute, entry.getKey(), lexical(value.getObject()));
                }
            }
        }
        return null;
    }

    static Resource restriction(Model model, Condition condition) {
        requireName(condition.attribute(), "条件属性");
        Resource attribute = model.getResource(iri(condition.attribute()));
        if (!attribute.hasProperty(RDF.type, OWL2.DatatypeProperty)) {
            throw new IllegalArgumentException("条件引用的数据属性不存在: " + condition.attribute());
        }
        if (!notBlank(condition.value())) {
            throw new IllegalArgumentException("条件 " + condition.attribute() + " 缺少取值");
        }
        RDFDatatype type = datatypeOf(attribute);
        if (!type.isValid(condition.value().trim())) {
            throw new IllegalArgumentException("条件 " + condition.attribute() + " 的取值 " + condition.value()
                    + " 不符合类型 " + localName(type.getURI()));
        }
        Literal value = model.createTypedLiteral(condition.value().trim(), type);
        Resource restriction = model.createResource()
                .addProperty(RDF.type, OWL2.Restriction)
                .addProperty(OWL2.onProperty, attribute);
        String operator = condition.operator() == null ? "=" : condition.operator().trim();
        if ("=".equals(operator)) {
            return restriction.addProperty(OWL2.hasValue, value);
        }
        String facet = FACETS.get(operator);
        if (facet == null) {
            throw new IllegalArgumentException("不支持的比较符: " + operator + "，可选 =、>、>=、<、<=");
        }
        Resource facetNode = model.createResource().addProperty(facetProperty(facet), value);
        Resource datatype = model.createResource()
                .addProperty(RDF.type, RDFS.Datatype)
                .addProperty(OWL2.onDatatype, model.createResource(type.getURI()))
                .addProperty(OWL2.withRestrictions, model.createList(new RDFNode[] {facetNode}));
        return restriction.addProperty(OWL2.someValuesFrom, datatype);
    }

    private static RDFDatatype datatypeOf(Resource attribute) {
        Resource range = attribute.getPropertyResourceValue(RDFS.range);
        String uri = range == null ? XSD + "string" : range.getURI();
        return TypeMapper.getInstance().getSafeTypeByName(uri);
    }

    private static Property facetProperty(String local) {
        return ResourceFactory.createProperty(XSD + local);
    }

    static Resource replace(Model model, String name, Resource kind) {
        Resource r = model.getResource(iri(name));
        ensureKind(model, r, kind);
        removeDeep(model, r);
        return r.addProperty(RDF.type, kind);
    }

    static void ensureKind(Model model, Resource r, Resource kind) {
        List<Resource> types = r.listProperties(RDF.type).toList().stream()
                .map(Statement::getResource)
                .filter(type -> type.equals(OWL2.Class) || type.equals(OWL2.DatatypeProperty)
                        || type.equals(OWL2.ObjectProperty) || type.equals(OWL2.NamedIndividual))
                .toList();
        if (!types.isEmpty() && !types.contains(kind)) {
            throw new IllegalArgumentException("名称 " + localName(r.getURI()) + " 已被另一种实体使用: "
                    + localName(types.get(0).getURI()));
        }
        if (kind.equals(OWL2.Class) && r.hasProperty(RDF.type, VALUE_SET)) {
            throw new IllegalArgumentException("名称 " + localName(r.getURI()) + " 已被码值集合使用");
        }
    }

    static Resource ref(Model model, String name, String what) {
        requireName(name, what);
        Resource r = model.getResource(iri(name));
        if (!model.contains(r, RDF.type, (RDFNode) null)) {
            throw new IllegalArgumentException(what + " 引用的实体不存在: " + name);
        }
        return r;
    }

    static Resource valueSet(Model model, String name) {
        Resource r = model.getResource(iri(name));
        if (r.hasProperty(RDF.type, OWL2.Class) && !r.hasProperty(RDF.type, VALUE_SET)) {
            throw new IllegalArgumentException("名称 " + name + " 已被业务类使用，不能作为码值集合");
        }
        return r.addProperty(RDF.type, OWL2.Class).addProperty(RDF.type, VALUE_SET);
    }

    /**
     * 概念删掉后，以它为 domain 的数据属性没有归属，一并删除。
     * 派生概念的交集列表里直接引用这个概念，先删掉，避免推理时列表被拆断。
     * 这些属性独占的码值集合及其码值也删除；仍被其他概念的属性引用时保留。
     */
    static void dropOwnedAttributes(Model model, Resource concept) {
        if (!concept.hasProperty(RDF.type, OWL2.Class) || concept.hasProperty(RDF.type, VALUE_SET)) {
            return;
        }
        List<Resource> defined = model.listSubjectsWithProperty(RDFS.subClassOf, concept).toList().stream()
                .filter(child -> child.hasProperty(OWL2.equivalentClass))
                .toList();
        for (Resource child : defined) {
            removeDeep(model, child);
            model.removeAll(null, null, child);
        }
        List<Resource> attributes = model.listSubjectsWithProperty(RDFS.domain, concept).toList().stream()
                .filter(property -> property.hasProperty(RDF.type, OWL2.DatatypeProperty))
                .toList();
        List<Resource> codeLists = attributes.stream()
                .map(attribute -> attribute.getPropertyResourceValue(CODE_LIST))
                .filter(codeList -> codeList != null)
                .distinct()
                .toList();
        for (Resource attribute : attributes) {
            removeDeep(model, attribute);
            model.removeAll(null, null, attribute);
        }
        Set<Resource> relations = new java.util.LinkedHashSet<>();
        relations.addAll(model.listSubjectsWithProperty(RDFS.domain, concept).toList());
        relations.addAll(model.listSubjectsWithProperty(RDFS.range, concept).toList());
        for (Resource relation : relations) {
            if (!relation.hasProperty(RDF.type, OWL2.ObjectProperty)) {
                continue;
            }
            dropInverse(model, relation);
            removeDeep(model, relation);
            model.removeAll(null, null, relation);
        }
        for (Resource codeList : codeLists) {
            if (!model.listSubjectsWithProperty(CODE_LIST, codeList).toList().isEmpty()) {
                continue;
            }
            for (Resource code : model.listSubjectsWithProperty(RDF.type, codeList).toList()) {
                removeDeep(model, code);
                model.removeAll(null, null, code);
            }
            removeDeep(model, codeList);
            model.removeAll(null, null, codeList);
        }
    }

    /** 属性换了码值集合后，旧集合若没有别的属性使用：有新集合就把码值移过去，否则连同码值删除。 */
    static void releaseValueSet(Model model, Resource previous, Resource replacement) {
        if (!model.listSubjectsWithProperty(CODE_LIST, previous).toList().isEmpty()) {
            return;
        }
        for (Resource code : model.listSubjectsWithProperty(RDF.type, previous).toList()) {
            if (replacement == null) {
                removeDeep(model, code);
                model.removeAll(null, null, code);
            } else {
                model.remove(code, RDF.type, previous);
                code.addProperty(RDF.type, replacement);
            }
        }
        removeDeep(model, previous);
        model.removeAll(null, null, previous);
    }

    static void dropInverse(Model model, Resource relation) {
        model.listSubjectsWithProperty(OWL2.inverseOf, relation).toList().forEach(inverse -> {
            model.remove(inverse, OWL2.inverseOf, relation);
            if (isBare(inverse) && !inverse.hasProperty(OWL2.inverseOf)) {
                removeDeep(model, inverse);
            }
        });
    }

    private static boolean isInverseOnly(Resource r) {
        return r.hasProperty(OWL2.inverseOf) && isBare(r);
    }

    private static boolean isBare(Resource r) {
        return !r.hasProperty(RDFS.domain) && !r.hasProperty(RDFS.label) && !r.hasProperty(JOIN_FROM);
    }

    static void removeDeep(Model model, Resource r) {
        for (Statement statement : r.listProperties().toList()) {
            model.remove(statement);
            if (statement.getObject().isAnon()) {
                removeDeep(model, statement.getObject().asResource());
            }
        }
    }

    static void describe(Model model, Resource r, String labelZh, String labelEn, List<String> aliases, String comment) {
        if (notBlank(labelZh)) {
            r.addProperty(RDFS.label, model.createLiteral(labelZh.trim(), "zh"));
        }
        if (notBlank(labelEn)) {
            r.addProperty(RDFS.label, model.createLiteral(labelEn.trim(), "en"));
        }
        if (aliases != null) {
            aliases.stream().filter(OntologyRdfIO::notBlank).map(String::trim).distinct()
                    .forEach(alias -> r.addProperty(ALT_LABEL, model.createLiteral(alias, "zh")));
        }
        if (notBlank(comment)) {
            r.addProperty(RDFS.comment, model.createLiteral(comment.trim(), "zh"));
        }
    }

    static void addString(Resource r, Property property, String value) {
        if (notBlank(value)) {
            r.addProperty(property, value.trim());
        }
    }

    static String label(Resource r, String lang) {
        return r.listProperties(RDFS.label).toList().stream()
                .map(Statement::getObject)
                .filter(RDFNode::isLiteral)
                .map(RDFNode::asLiteral)
                .filter(literal -> lang.equalsIgnoreCase(literal.getLanguage()))
                .map(Literal::getLexicalForm)
                .findFirst()
                .orElse(null);
    }

    static List<String> aliases(Resource r) {
        return r.listProperties(ALT_LABEL).toList().stream()
                .map(statement -> lexical(statement.getObject()))
                .sorted()
                .toList();
    }

    private static String comment(Resource r) {
        return string(r, RDFS.comment);
    }

    static String string(Resource r, Property property) {
        Statement statement = r.getProperty(property);
        return statement == null ? null : lexical(statement.getObject());
    }

    static boolean bool(Resource r, Property property) {
        Statement statement = r.getProperty(property);
        return statement != null && statement.getObject().isLiteral() && statement.getBoolean();
    }

    static String objectName(Resource r, Property property) {
        Resource value = r.getPropertyResourceValue(property);
        return value == null || value.isAnon() ? null : localName(value.getURI());
    }

    static String lexical(RDFNode node) {
        return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

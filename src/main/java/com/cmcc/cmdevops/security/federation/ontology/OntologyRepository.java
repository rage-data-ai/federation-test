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

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

@Component
public class OntologyRepository {

    static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    public static final Set<String> DATATYPES = Set.of(
            "string", "integer", "long", "decimal", "double", "boolean", "date", "dateTime");

    private static final Map<String, String> FACETS = Map.of(
            ">=", "minInclusive",
            ">", "minExclusive",
            "<=", "maxInclusive",
            "<", "maxExclusive");

    private final OntologyStore store;

    public OntologyRepository(OntologyStore store) {
        this.store = store;
    }

    public Snapshot snapshot() {
        return store.read(OntologyRepository::snapshotOf);
    }

    /** 不分主题域的全部实体。实体名是全局唯一的，起名查重要用它。 */
    public Snapshot all() {
        return store.read(OntologyRepository::snapshotOf);
    }

    /**
     * 只看得到一个主题域的视图：{@link #snapshot()} 只返回这个主题域的实体，保存的概念自动归到这个主题域。
     * 起草时用它，模型和起草逻辑就看不到别的主题域。
     */
    public OntologyRepository within(String subject) {
        return new Scoped(store, normalizeSubject(subject));
    }

    private static final class Scoped extends OntologyRepository {

        private final String subject;

        private Scoped(OntologyStore store, String subject) {
            super(store);
            this.subject = subject;
        }

        @Override
        public Snapshot snapshot() {
            return scope(all(), subject);
        }

        @Override
        public void saveConcept(Concept concept) {
            super.saveConcept(concept.withSubject(subject));
        }
    }

    /** 只保留一个主题域的概念，以及跟随这些概念的属性、关系、码值和派生概念。 */
    public static Snapshot scope(Snapshot snapshot, String subject) {
        String wanted = normalizeSubject(subject);
        List<Concept> concepts = snapshot.concepts().stream()
                .filter(concept -> Objects.equals(normalizeSubject(concept.subject()), wanted))
                .toList();
        Set<String> names = new java.util.LinkedHashSet<>();
        concepts.forEach(concept -> names.add(concept.name()));
        List<Attribute> attributes = snapshot.attributes().stream()
                .filter(attribute -> names.contains(attribute.domain()))
                .toList();
        Set<String> lists = new java.util.LinkedHashSet<>();
        attributes.forEach(attribute -> {
            if (notBlank(attribute.codeList())) {
                lists.add(attribute.codeList());
            }
        });
        return new Snapshot(concepts, attributes,
                snapshot.relations().stream()
                        .filter(relation -> names.contains(relation.domain()) && names.contains(relation.range()))
                        .toList(),
                snapshot.codes().stream().filter(code -> lists.contains(code.codeList())).toList(),
                snapshot.definedConcepts().stream().filter(defined -> names.contains(defined.base())).toList());
    }

    /** 把一个主题域整体改名。目标名字已有概念时两个主题域合并。 */
    public void renameSubject(String from, String to) {
        String source = normalizeSubject(from);
        String target = normalizeSubject(to);
        if (source == null || target == null) {
            throw new IllegalArgumentException("主题域名称不能为空");
        }
        store.write(model -> {
            List<Resource> concepts = model.listSubjectsWithProperty(SUBJECT).toList().stream()
                    .filter(concept -> source.equals(string(concept, SUBJECT)))
                    .toList();
            if (concepts.isEmpty()) {
                throw new IllegalArgumentException("主题域不存在: " + source);
            }
            for (Resource concept : concepts) {
                concept.removeAll(SUBJECT);
                concept.addProperty(SUBJECT, target);
            }
        });
    }

    private static String normalizeSubject(String subject) {
        return notBlank(subject) ? subject.trim() : null;
    }

    /** 概念自己的主题域；派生概念跟随它的基类。 */
    private static String subjectOf(Resource concept) {
        if (concept.hasProperty(OWL2.equivalentClass)) {
            Resource base = concept.getPropertyResourceValue(RDFS.subClassOf);
            return base == null || base.equals(concept) ? null : subjectOf(base);
        }
        return normalizeSubject(string(concept, SUBJECT));
    }

    private static String subjectText(String subject) {
        return subject == null ? "未分主题域" : "主题域 " + subject;
    }

    /** 概念换主题域时，和它已有关联的概念必须在同一个主题域里。 */
    private static void requireSameSubject(Model model, Resource concept, String subject) {
        Set<Resource> linked = new java.util.LinkedHashSet<>();
        for (Resource relation : model.listSubjectsWithProperty(RDFS.domain, concept).toList()) {
            if (relation.hasProperty(RDF.type, OWL2.ObjectProperty) && relation.getPropertyResourceValue(RDFS.range) != null) {
                linked.add(relation.getPropertyResourceValue(RDFS.range));
            }
        }
        for (Resource relation : model.listSubjectsWithProperty(RDFS.range, concept).toList()) {
            if (relation.hasProperty(RDF.type, OWL2.ObjectProperty) && relation.getPropertyResourceValue(RDFS.domain) != null) {
                linked.add(relation.getPropertyResourceValue(RDFS.domain));
            }
        }
        for (Resource child : model.listSubjectsWithProperty(RDFS.subClassOf, concept).toList()) {
            if (!child.hasProperty(OWL2.equivalentClass)) {
                linked.add(child);
            }
        }
        for (Resource other : linked) {
            if (!other.equals(concept) && !other.isAnon() && !Objects.equals(subjectOf(other), subject)) {
                throw new IllegalArgumentException("概念 " + localName(concept.getURI()) + " 和 " + localName(other.getURI())
                        + "（" + subjectText(subjectOf(other)) + "）已有关联，不能单独改到" + subjectText(subject)
                        + "；只有同主题域的概念能关联");
            }
        }
    }

    public String turtle() {
        return store.read(model -> {
            Model copy = ModelFactory.createDefaultModel().add(model);
            copy.setNsPrefix("soc", OntologyVocabulary.SOC);
            copy.setNsPrefix("fed", OntologyVocabulary.FED);
            copy.setNsPrefix("skos", OntologyVocabulary.SKOS);
            copy.setNsPrefix("owl", OWL2.NS);
            copy.setNsPrefix("rdfs", RDFS.uri);
            copy.setNsPrefix("xsd", XSD);
            StringWriter out = new StringWriter();
            copy.write(out, "TTL");
            return out.toString();
        });
    }

    public void saveConcept(Concept concept) {
        requireName(concept.name(), "类");
        String subject = normalizeSubject(concept.subject());
        store.write(model -> {
            Resource current = model.getResource(iri(concept.name()));
            requireSameSubject(model, current, subject);
            if (notBlank(concept.parent())) {
                Resource parent = ref(model, concept.parent(), "父类");
                if (!parent.equals(current) && !Objects.equals(subjectOf(parent), subject)) {
                    throw new IllegalArgumentException("父类 " + concept.parent() + " 在" + subjectText(subjectOf(parent))
                            + "，概念 " + concept.name() + " 在" + subjectText(subject) + "；只有同主题域的概念能关联");
                }
            }
            Resource r = replace(model, concept.name(), OWL2.Class);
            describe(model, r, concept.labelZh(), concept.labelEn(), concept.aliases(), concept.comment());
            if (notBlank(concept.parent())) {
                r.addProperty(RDFS.subClassOf, ref(model, concept.parent(), "父类"));
            }
            addString(r, SUBJECT, subject);
            addString(r, MAPPED_TABLE, concept.table());
            addString(r, TIME_COLUMN, concept.timeColumn());
            if (concept.largeTable()) {
                r.addLiteral(LARGE_TABLE, true);
            }
        });
    }

    /**
     * 码值集合名只由属性映射的表和列决定，同一张表的同一列每次起草都得到同一个名字。
     * 例如 ck81.pangu.wls_day01 的 Status 列为 Ck81PanguWlsDay01_Status。
     */
    public static String codeListName(String table, String column) {
        String tablePart = pascal(table.trim().toLowerCase(Locale.ROOT));
        String columnPart = pascal(column.trim());
        if (columnPart.isEmpty()) {
            columnPart = "Col" + Integer.toUnsignedString(column.trim().hashCode(), 36);
        }
        String name = tablePart + "_" + columnPart;
        return Character.isLetter(name.charAt(0)) ? name : "T" + name;
    }

    /**
     * 概念名由映射表决定，例如 ck81.pangu.wls_day01 为 WlsDay01。
     * 表名与别的表的概念重名时依次改用 schema+表名、catalog+schema+表名。
     *
     * @param tableOf 已占用的概念名及其映射表
     */
    public static String conceptName(String table, Map<String, String> tableOf) {
        String[] parts = table.trim().toLowerCase(Locale.ROOT).split("\\.");
        List<String> candidates = new ArrayList<>();
        for (int from = parts.length - 1; from >= 0; from--) {
            String name = pascal(String.join("_", List.of(parts).subList(from, parts.length)));
            candidates.add(name.isEmpty() || !Character.isLetter(name.charAt(0)) ? "T" + name : name);
        }
        for (String candidate : candidates) {
            String owner = tableOf.get(candidate);
            if (owner == null || owner.equalsIgnoreCase(table.trim())) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 属性名由概念和列决定，例如概念 WlsDay01 的 LogonType 列为 wlsDay01_LogonType。
     * 首字母小写，不会与概念、码值集合、码值重名；列名不是合法标识符时改用 PascalCase。
     */
    public static String attributeName(String concept, String column) {
        String trimmed = column.trim();
        String columnPart = trimmed.matches("[A-Za-z0-9_]+") ? trimmed : pascal(trimmed);
        if (columnPart.isEmpty()) {
            columnPart = "Col" + Integer.toUnsignedString(trimmed.hashCode(), 36);
        }
        return Character.toLowerCase(concept.charAt(0)) + concept.substring(1) + "_" + columnPart;
    }

    /** 属性有映射的表和列时，码值集合名由表和列生成；否则沿用填写的名字。 */
    public String codeListFor(Attribute attribute) {
        if (!notBlank(attribute.codeList())) {
            return null;
        }
        String table = store.read(model -> notBlank(attribute.domain())
                ? string(model.getResource(iri(attribute.domain())), MAPPED_TABLE)
                : null);
        return resolveCodeList(table, attribute);
    }

    private static String resolveCodeList(String table, Attribute attribute) {
        if (notBlank(table) && notBlank(attribute.column())) {
            return codeListName(table, attribute.column());
        }
        return attribute.codeList().trim();
    }

    public void saveAttribute(Attribute attribute) {
        requireName(attribute.name(), "数据属性");
        String datatype = attribute.datatype() == null || attribute.datatype().isBlank() ? "string" : attribute.datatype();
        if (!DATATYPES.contains(datatype)) {
            throw new IllegalArgumentException("不支持的数据类型: " + datatype + "，可选 " + DATATYPES);
        }
        store.write(model -> {
            Resource existing = model.getResource(iri(attribute.name()));
            Resource previousList = existing.getPropertyResourceValue(CODE_LIST);
            String listName = null;
            if (notBlank(attribute.codeList())) {
                String table = notBlank(attribute.domain())
                        ? string(model.getResource(iri(attribute.domain())), MAPPED_TABLE)
                        : null;
                listName = resolveCodeList(table, attribute);
                requireName(listName, "码值集合");
                requireSingleOwner(model, model.getResource(iri(listName)), existing, attribute.domain());
            }

            Resource r = replace(model, attribute.name(), OWL2.DatatypeProperty);
            describe(model, r, attribute.labelZh(), attribute.labelEn(), attribute.aliases(), attribute.comment());
            if (notBlank(attribute.domain())) {
                r.addProperty(RDFS.domain, ref(model, attribute.domain(), "domain"));
            }
            r.addProperty(RDFS.range, model.createResource(XSD + datatype));
            addString(r, MAPPED_COLUMN, attribute.column());
            addString(r, UNIT, attribute.unit());
            if (attribute.identifier()) {
                r.addLiteral(IDENTIFIER, true);
            }
            Resource list = listName == null ? null : valueSet(model, listName);
            if (list != null) {
                r.addProperty(CODE_LIST, list);
            }
            if (previousList != null && !previousList.equals(list)) {
                releaseValueSet(model, previousList, list);
            }
        });
    }

    /** 一个码值集合只能属于一个概念，命中码值时才能唯一确定概念。 */
    private static void requireSingleOwner(Model model, Resource list, Resource attribute, String domain) {
        for (Resource other : model.listSubjectsWithProperty(CODE_LIST, list).toList()) {
            if (other.equals(attribute)) {
                continue;
            }
            String owner = objectName(other, RDFS.domain);
            if (!Objects.equals(owner, domain)) {
                throw new IllegalArgumentException("码值集合 " + localName(list.getURI()) + " 已属于概念 " + owner
                        + "（属性 " + localName(other.getURI()) + "），一个码值集合只能属于一个概念");
            }
        }
    }

    /** 属性换了码值集合后，旧集合若没有别的属性使用：有新集合就把码值移过去，否则连同码值删除。 */
    private static void releaseValueSet(Model model, Resource previous, Resource replacement) {
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

    public void saveRelation(Relation relation) {
        requireName(relation.name(), "关系");
        store.write(model -> {
            if (notBlank(relation.domain()) && notBlank(relation.range())) {
                Resource domain = ref(model, relation.domain(), "domain");
                Resource range = ref(model, relation.range(), "range");
                if (!Objects.equals(subjectOf(domain), subjectOf(range))) {
                    throw new IllegalArgumentException(relation.domain() + " 在" + subjectText(subjectOf(domain)) + "，"
                            + relation.range() + " 在" + subjectText(subjectOf(range)) + "；只有同主题域的概念能建立关系");
                }
            }
            Resource existing = model.getResource(iri(relation.name()));
            dropInverse(model, existing);
            Resource r = replace(model, relation.name(), OWL2.ObjectProperty);
            describe(model, r, relation.labelZh(), relation.labelEn(), relation.aliases(), relation.comment());
            if (notBlank(relation.domain())) {
                r.addProperty(RDFS.domain, ref(model, relation.domain(), "domain"));
            }
            if (notBlank(relation.range())) {
                r.addProperty(RDFS.range, ref(model, relation.range(), "range"));
            }
            addString(r, JOIN_FROM, relation.joinFrom());
            addString(r, JOIN_TO, relation.joinTo());
            if (relation.transitive()) {
                r.addProperty(RDF.type, OWL2.TransitiveProperty);
            }
            if (relation.functional()) {
                r.addProperty(RDF.type, OWL2.FunctionalProperty);
            }
            if (notBlank(relation.inverse())) {
                requireName(relation.inverse(), "逆关系");
                Resource inverse = model.getResource(iri(relation.inverse()));
                ensureKind(model, inverse, OWL2.ObjectProperty);
                inverse.addProperty(RDF.type, OWL2.ObjectProperty);
                inverse.addProperty(OWL2.inverseOf, r);
            }
        });
    }

    public void saveCode(Code code) {
        requireName(code.name(), "码值");
        requireName(code.codeList(), "码值集合");
        if (!notBlank(code.value())) {
            throw new IllegalArgumentException("码值 " + code.name() + " 缺少 value");
        }
        store.write(model -> {
            Resource list = model.getResource(iri(code.codeList()));
            if (model.listSubjectsWithProperty(CODE_LIST, list).toList().isEmpty()) {
                throw new IllegalArgumentException("码值集合 " + code.codeList()
                        + " 没有对应的属性，请先在属性上设置码表，码值才能归到概念上");
            }
            Resource r = replace(model, code.name(), OWL2.NamedIndividual);
            r.addProperty(RDF.type, valueSet(model, code.codeList()));
            describe(model, r, code.labelZh(), code.labelEn(), code.aliases(), code.comment());
            r.addProperty(CODE_VALUE, code.value());
        });
    }

    public void saveDefinedConcept(DefinedConcept defined) {
        requireName(defined.name(), "派生概念");
        requireName(defined.base(), "派生概念的基类");
        List<Condition> conditions = defined.conditions() == null ? List.of() : defined.conditions();
        store.write(model -> {
            Resource baseConcept = ref(model, defined.base(), "基类");
            for (Condition condition : conditions) {
                Resource attribute = model.getResource(iri(String.valueOf(condition.attribute())));
                Resource owner = attribute.getPropertyResourceValue(RDFS.domain);
                if (owner != null && !Objects.equals(subjectOf(owner), subjectOf(baseConcept))) {
                    throw new IllegalArgumentException("条件属性 " + condition.attribute() + " 属于 " + localName(owner.getURI())
                            + "（" + subjectText(subjectOf(owner)) + "），基类 " + defined.base() + " 在"
                            + subjectText(subjectOf(baseConcept)) + "；派生概念只能用同主题域的属性");
                }
            }
            Resource r = replace(model, defined.name(), OWL2.Class);
            describe(model, r, defined.labelZh(), defined.labelEn(), defined.aliases(), defined.comment());
            Resource base = ref(model, defined.base(), "基类");
            r.addProperty(RDFS.subClassOf, base);
            List<RDFNode> members = new ArrayList<>();
            members.add(base);
            for (Condition condition : conditions) {
                members.add(restriction(model, condition));
            }
            Resource intersection = model.createResource()
                    .addProperty(RDF.type, OWL2.Class)
                    .addProperty(OWL2.intersectionOf, model.createList(members.iterator()));
            r.addProperty(OWL2.equivalentClass, intersection);
            addString(r, DEFINITION_TEXT, defined.definitionText());
        });
    }

    public void delete(String name) {
        requireName(name, "实体");
        store.write(model -> {
            Resource r = model.getResource(iri(name));
            if (!model.contains(r, null, (RDFNode) null)) {
                throw new IllegalArgumentException("本体中不存在: " + name);
            }
            dropOwnedAttributes(model, r);
            dropInverse(model, r);
            removeDeep(model, r);
            model.removeAll(null, null, r);
        });
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

    private static Resource restriction(Model model, Condition condition) {
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

    private static String pascal(String text) {
        StringBuilder out = new StringBuilder();
        for (String part : text.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.toString();
    }

    private static Resource replace(Model model, String name, Resource kind) {
        Resource r = model.getResource(iri(name));
        ensureKind(model, r, kind);
        removeDeep(model, r);
        return r.addProperty(RDF.type, kind);
    }

    private static void ensureKind(Model model, Resource r, Resource kind) {
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

    private static Resource ref(Model model, String name, String what) {
        requireName(name, what);
        Resource r = model.getResource(iri(name));
        if (!model.contains(r, RDF.type, (RDFNode) null)) {
            throw new IllegalArgumentException(what + " 引用的实体不存在: " + name);
        }
        return r;
    }

    private static Resource valueSet(Model model, String name) {
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
    private static void dropOwnedAttributes(Model model, Resource concept) {
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

    private static void dropInverse(Model model, Resource relation) {
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

    private static void removeDeep(Model model, Resource r) {
        for (Statement statement : r.listProperties().toList()) {
            model.remove(statement);
            if (statement.getObject().isAnon()) {
                removeDeep(model, statement.getObject().asResource());
            }
        }
    }

    private static void describe(Model model, Resource r, String labelZh, String labelEn, List<String> aliases, String comment) {
        if (notBlank(labelZh)) {
            r.addProperty(RDFS.label, model.createLiteral(labelZh.trim(), "zh"));
        }
        if (notBlank(labelEn)) {
            r.addProperty(RDFS.label, model.createLiteral(labelEn.trim(), "en"));
        }
        if (aliases != null) {
            aliases.stream().filter(OntologyRepository::notBlank).map(String::trim).distinct()
                    .forEach(alias -> r.addProperty(ALT_LABEL, model.createLiteral(alias, "zh")));
        }
        if (notBlank(comment)) {
            r.addProperty(RDFS.comment, model.createLiteral(comment.trim(), "zh"));
        }
    }

    private static void addString(Resource r, Property property, String value) {
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

    private static boolean bool(Resource r, Property property) {
        Statement statement = r.getProperty(property);
        return statement != null && statement.getObject().isLiteral() && statement.getBoolean();
    }

    static String objectName(Resource r, Property property) {
        Resource value = r.getPropertyResourceValue(property);
        return value == null || value.isAnon() ? null : localName(value.getURI());
    }

    private static String lexical(RDFNode node) {
        return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

package com.cmcc.cmdevops.security.federation.ontology;

import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.XSD;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.addString;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.describe;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.dropInverse;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.dropOwnedAttributes;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.ensureKind;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.notBlank;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.objectName;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.ref;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.releaseValueSet;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.removeDeep;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.replace;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.restriction;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.string;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyRdfIO.valueSet;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.CODE_LIST;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.CODE_VALUE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.DEFINITION_TEXT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.SQL_EXPRESSION;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.IDENTIFIER;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.JOIN_FROM;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.JOIN_TO;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.LARGE_TABLE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.MAPPED_COLUMN;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.MAPPED_TABLE;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.SUBJECT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.TIME_COLUMN;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.UNIT;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.iri;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.localName;
import static com.cmcc.cmdevops.security.federation.ontology.OntologyVocabulary.requireName;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
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

/**
 * 本体仓储门面：保存 / 删除 / 主题域范围 / 快照。命名与 RDF 细节见 {@link OntologyNaming}、{@link OntologyRdfIO}。
 */
@Component
public class OntologyRepository {

    public static final Set<String> DATATYPES = Set.of(
            "string", "integer", "long", "decimal", "double", "boolean", "date", "dateTime");

    private final OntologyStore store;

    public OntologyRepository(OntologyStore store) {
        this.store = store;
    }

    public Snapshot snapshot() {
        return store.read(OntologyRdfIO::snapshotOf);
    }

    /** 不分主题域的全部实体。实体名是全局唯一的，起名查重要用它。 */
    public Snapshot all() {
        return store.read(OntologyRdfIO::snapshotOf);
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
        return OntologyNaming.codeListName(table, column);
    }

    /**
     * 概念名由映射表决定，例如 ck81.pangu.wls_day01 为 WlsDay01。
     * 表名与别的表的概念重名时依次改用 schema+表名、catalog+schema+表名。
     *
     * @param tableOf 已占用的概念名及其映射表
     */
    public static String conceptName(String table, Map<String, String> tableOf) {
        return OntologyNaming.conceptName(table, tableOf);
    }

    /**
     * 属性名由概念和列决定，例如概念 WlsDay01 的 LogonType 列为 wlsDay01_LogonType。
     * 首字母小写，不会与概念、码值集合、码值重名；列名不是合法标识符时改用 PascalCase。
     */
    public static String attributeName(String concept, String column) {
        return OntologyNaming.attributeName(concept, column);
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
            addString(r, SQL_EXPRESSION, defined.sqlExpression());
        });
    }


    /** 用快照整体替换当前本体（先清空再写入）。用于版本回滚。 */
    public void replaceSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不能为空");
        }
        store.clear();
        for (Concept concept : snapshot.concepts() == null ? List.<Concept>of() : snapshot.concepts()) {
            saveConcept(concept);
        }
        for (Attribute attribute : snapshot.attributes() == null ? List.<Attribute>of() : snapshot.attributes()) {
            saveAttribute(attribute);
        }
        for (Code code : snapshot.codes() == null ? List.<Code>of() : snapshot.codes()) {
            saveCode(code);
        }
        for (Relation relation : snapshot.relations() == null ? List.<Relation>of() : snapshot.relations()) {
            saveRelation(relation);
        }
        for (DefinedConcept defined : snapshot.definedConcepts() == null ? List.<DefinedConcept>of() : snapshot.definedConcepts()) {
            saveDefinedConcept(defined);
        }
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
}

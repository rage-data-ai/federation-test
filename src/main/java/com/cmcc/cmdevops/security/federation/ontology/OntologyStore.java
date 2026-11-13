package com.cmcc.cmdevops.security.federation.ontology;

import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.tdb2.TDB2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

import jakarta.annotation.PreDestroy;

/**
 * 本体存储。断言三元组在 TDB2 默认图；推理结果在命名图 {@link #INFERRED_GRAPH}。
 * <p>
 * 每次写入在同一个写事务里：先把断言图复制到内存，用 OWL Micro 规则推理，
 * 再把推理出的新三元组整体替换写回推理图，保证两张图始终一致。
 */
@Component
public class OntologyStore {

    public static final String INFERRED_GRAPH = "urn:fed:graph:inferred";

    private static final Logger log = LoggerFactory.getLogger(OntologyStore.class);

    private final Dataset dataset;
    private Model snapshot;

    @Autowired
    public OntologyStore(FederationProperties properties) {
        this(TDB2Factory.connectDataset(properties.getOntologyStore()));
    }

    OntologyStore(Dataset dataset) {
        this.dataset = dataset;
        boolean stale = dataset.calculateRead(() -> !dataset.getDefaultModel().isEmpty()
                && dataset.getNamedModel(INFERRED_GRAPH).isEmpty());
        if (stale) {
            materialize();
        }
    }

    public static OntologyStore inMemory() {
        return new OntologyStore(TDB2Factory.createDataset());
    }

    public <T> T read(Function<Model, T> action) {
        return dataset.calculateRead(() -> action.apply(dataset.getDefaultModel()));
    }

    public void write(Consumer<Model> action) {
        dataset.executeWrite(() -> {
            action.accept(dataset.getDefaultModel());
            reason();
        });
        invalidate();
    }

    /** 按当前断言重新推理并写回推理图。 */
    public void materialize() {
        dataset.executeWrite(this::reason);
        invalidate();
    }

    /** 清空断言图与推理图（用于版本回滚导入）。 */
    public void clear() {
        dataset.executeWrite(() -> {
            dataset.getDefaultModel().removeAll();
            dataset.getNamedModel(INFERRED_GRAPH).removeAll();
        });
        invalidate();
    }

    /** 推理图中的三元组数量（不含断言）。 */
    public long inferredSize() {
        return dataset.calculateRead(() -> dataset.getNamedModel(INFERRED_GRAPH).size());
    }

    /**
     * 断言图与推理图的并集，从 TDB2 读出后缓存为内存模型，下次写入前复用。
     * 可并发读，但不要修改；写入请走 {@link #write}。
     */
    public synchronized Model inferred() {
        if (snapshot == null) {
            snapshot = dataset.calculateRead(() -> ModelFactory.createDefaultModel()
                    .add(dataset.getDefaultModel())
                    .add(dataset.getNamedModel(INFERRED_GRAPH)));
        }
        return snapshot;
    }

    private void reason() {
        long started = System.currentTimeMillis();
        Model asserted = ModelFactory.createDefaultModel().add(dataset.getDefaultModel());
        InfModel reasoning = ModelFactory.createInfModel(ReasonerRegistry.getOWLMicroReasoner(), asserted);
        Model entailed = ModelFactory.createDefaultModel();
        reasoning.listStatements()
                .filterDrop(statement -> asserted.contains(statement) || isVocabulary(statement.getSubject()))
                .forEach(entailed::add);
        Model graph = dataset.getNamedModel(INFERRED_GRAPH);
        graph.removeAll();
        graph.add(entailed);
        log.debug("本体推理完成：断言 {} 条，推理新增 {} 条，耗时 {} ms",
                asserted.size(), entailed.size(), System.currentTimeMillis() - started);
    }

    /** RDF/RDFS/OWL/XSD 词汇自身的公理，与业务本体无关。 */
    private static boolean isVocabulary(Resource subject) {
        return subject.isURIResource() && subject.getURI().startsWith("http://www.w3.org/");
    }

    private synchronized void invalidate() {
        snapshot = null;
    }

    @PreDestroy
    public void close() {
        dataset.close();
    }
}

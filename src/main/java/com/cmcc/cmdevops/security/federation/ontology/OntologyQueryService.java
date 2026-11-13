package com.cmcc.cmdevops.security.federation.ontology;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

@Service
public class OntologyQueryService {

    private final OntologyStore store;
    private final int maxRows;

    @Autowired
    public OntologyQueryService(OntologyStore store, FederationProperties properties) {
        this(store, properties.getMaxRows());
    }

    public OntologyQueryService(OntologyStore store, int maxRows) {
        this.store = store;
        this.maxRows = maxRows;
    }

    public SparqlResult select(String sparql, boolean withInference) {
        Query query = parseSelect(sparql);
        if (withInference) {
            return execute(query, store.inferred());
        }
        return store.read(model -> execute(query, model));
    }

    private static Query parseSelect(String sparql) {
        Query query;
        try {
            query = QueryFactory.create(sparql);
        } catch (QueryParseException e) {
            throw new IllegalArgumentException("SPARQL 语法错误: " + e.getMessage(), e);
        }
        if (!query.isSelectType()) {
            throw new IllegalArgumentException("只支持 SELECT 查询");
        }
        return query;
    }

    private SparqlResult execute(Query query, Model model) {
        try (QueryExecution execution = QueryExecution.model(model).query(query).build()) {
            ResultSet results = execution.execSelect();
            List<String> columns = results.getResultVars();
            List<List<String>> rows = new ArrayList<>();
            boolean truncated = false;
            while (results.hasNext()) {
                QuerySolution solution = results.next();
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>(columns.size());
                for (String column : columns) {
                    row.add(render(solution.get(column)));
                }
                rows.add(row);
            }
            return new SparqlResult(columns, rows, truncated);
        }
    }

    private static String render(RDFNode node) {
        if (node == null) {
            return null;
        }
        if (node.isURIResource()) {
            return node.asResource().getURI();
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        return "_:" + node.asResource().getId().getLabelString();
    }

    public record SparqlResult(List<String> columns, List<List<String>> rows, boolean truncated) {
    }
}

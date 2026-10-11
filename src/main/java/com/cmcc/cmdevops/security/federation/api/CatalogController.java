package com.cmcc.cmdevops.security.federation.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.catalog.CatalogRecord;
import com.cmcc.cmdevops.security.federation.catalog.CatalogRegistry;
import com.cmcc.cmdevops.security.federation.catalog.CatalogView;
import com.cmcc.cmdevops.security.federation.service.QueryService;
import com.cmcc.cmdevops.security.federation.sql.DemoSqlLoader;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final QueryService queryService;
    private final CatalogRegistry catalogRegistry;
    private final DemoSqlLoader demoSqlLoader;

    public CatalogController(QueryService queryService, CatalogRegistry catalogRegistry, DemoSqlLoader demoSqlLoader) {
        this.queryService = queryService;
        this.catalogRegistry = catalogRegistry;
        this.demoSqlLoader = demoSqlLoader;
    }

    @GetMapping("/catalogs")
    public List<CatalogView> catalogs() {
        return queryService.listCatalogs();
    }

    @PostMapping("/catalogs")
    public CatalogView create(@RequestBody CatalogRecord record) {
        return CatalogView.from(catalogRegistry.save(record, false));
    }

    @PutMapping("/catalogs/{name}")
    public CatalogView update(@PathVariable String name, @RequestBody CatalogRecord record) {
        record.setName(name);
        return CatalogView.from(catalogRegistry.save(record, true));
    }

    @DeleteMapping("/catalogs/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        catalogRegistry.delete(name);
        return Map.of("success", true);
    }

    @PostMapping("/catalogs/test")
    public Map<String, Object> test(@RequestBody CatalogRecord record) {
        return catalogRegistry.test(record);
    }

    @GetMapping("/examples")
    public List<Map<String, String>> examples() {
        return demoSqlLoader.list();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

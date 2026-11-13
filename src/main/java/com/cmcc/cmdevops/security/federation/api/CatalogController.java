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
import com.cmcc.cmdevops.security.federation.metadata.CollectRequest;
import com.cmcc.cmdevops.security.federation.metadata.MetadataService;
import com.cmcc.cmdevops.security.federation.metadata.TableSnapshot;
import com.cmcc.cmdevops.security.federation.security.CatalogAclService;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;
import com.cmcc.cmdevops.security.federation.service.QueryService;
import com.cmcc.cmdevops.security.federation.sql.DemoSqlLoader;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final QueryService queryService;
    private final CatalogRegistry catalogRegistry;
    private final DemoSqlLoader demoSqlLoader;
    private final MetadataService metadataService;
    private final CatalogAclService catalogAcl;

    public CatalogController(
            QueryService queryService,
            CatalogRegistry catalogRegistry,
            DemoSqlLoader demoSqlLoader,
            MetadataService metadataService,
            CatalogAclService catalogAcl) {
        this.queryService = queryService;
        this.catalogRegistry = catalogRegistry;
        this.demoSqlLoader = demoSqlLoader;
        this.metadataService = metadataService;
        this.catalogAcl = catalogAcl;
    }

    @GetMapping("/catalogs")
    public List<CatalogView> catalogs() {
        return queryService.listCatalogs();
    }

    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/catalogs")
    public CatalogView create(@RequestBody CatalogRecord record) {
        return CatalogView.from(catalogRegistry.save(record, false));
    }

    @RequireRole(GatewayRole.ADMIN)
    @PutMapping("/catalogs/{name}")
    public CatalogView update(@PathVariable("name") String name, @RequestBody CatalogRecord record) {
        record.setName(name);
        return CatalogView.from(catalogRegistry.save(record, true));
    }

    @RequireRole(GatewayRole.ADMIN)
    @DeleteMapping("/catalogs/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name) {
        catalogRegistry.delete(name);
        return Map.of("success", true);
    }

    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/catalogs/test")
    public Map<String, Object> test(@RequestBody CatalogRecord record) {
        return catalogRegistry.test(record);
    }

    @GetMapping("/metadata")
    public List<TableSnapshot> metadata() {
        return catalogAcl.filter(metadataService.list(), TableSnapshot::getCatalog);
    }

    @RequireRole(GatewayRole.ADMIN)
    @PostMapping("/metadata/collect")
    public List<TableSnapshot> collect(@RequestBody CollectRequest request) {
        return metadataService.collect(request);
    }

    @RequireRole(GatewayRole.ADMIN)
    @DeleteMapping("/metadata/{catalog}/{schema}/{table}")
    public Map<String, Object> deleteMetadata(
            @PathVariable("catalog") String catalog,
            @PathVariable("schema") String schema,
            @PathVariable("table") String table) {
        if (!metadataService.delete(catalog, schema, table)) {
            throw new IllegalArgumentException("元数据中没有表 " + catalog + "." + schema + "." + table);
        }
        return Map.of("success", true);
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

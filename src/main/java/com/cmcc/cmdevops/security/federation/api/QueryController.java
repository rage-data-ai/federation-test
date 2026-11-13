package com.cmcc.cmdevops.security.federation.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.model.QueryRequest;
import com.cmcc.cmdevops.security.federation.model.QueryResponse;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.RequireRole;
import com.cmcc.cmdevops.security.federation.service.QueryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @RequireRole(GatewayRole.QUERY)
    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest body) {
        return queryService.execute(body);
    }

    @RequireRole(GatewayRole.QUERY)
    @PostMapping("/explain")
    public QueryResponse explain(@Valid @RequestBody QueryRequest body) {
        body.setExplain(true);
        return queryService.execute(body);
    }
}

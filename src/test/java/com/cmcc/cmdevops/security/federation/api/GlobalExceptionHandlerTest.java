package com.cmcc.cmdevops.security.federation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void masksUnexpectedFailures() {
        ResponseEntity<QueryResponse> response = handler.failed(new RuntimeException("jdbc password=secret leaked"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().success());
        assertEquals("内部错误，请查看服务日志", response.getBody().message());
    }

    @Test
    void keepsClientFacingIllegalArgument() {
        ResponseEntity<QueryResponse> response = handler.badRequest(new IllegalArgumentException("未注册的数据源: x"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("未注册的数据源: x", response.getBody().message());
    }
}

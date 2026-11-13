package com.cmcc.cmdevops.security.federation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void masksUnexpectedFailuresAsProblemDetails() {
        ResponseEntity<ProblemDetail> response = handler.failed(new RuntimeException("jdbc password=secret leaked"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("内部错误，请查看服务日志", response.getBody().getDetail());
        assertEquals("内部错误，请查看服务日志", response.getBody().getProperties().get("message"));
        assertFalse((Boolean) response.getBody().getProperties().get("success"));
    }

    @Test
    void keepsClientFacingIllegalArgument() {
        ResponseEntity<ProblemDetail> response = handler.badRequest(new IllegalArgumentException("未注册的数据源: x"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("未注册的数据源: x", response.getBody().getDetail());
    }
}

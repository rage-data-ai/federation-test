package com.cmcc.cmdevops.security.federation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void mapsStateUnsupportedAndStatusExceptions() {
        ResponseEntity<ProblemDetail> conflict = handler.conflict(new IllegalStateException("冲突"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("冲突", conflict.getBody().getDetail());

        ResponseEntity<ProblemDetail> blank = handler.conflict(new IllegalStateException("  "));
        assertEquals("请求无法完成", blank.getBody().getDetail());

        ResponseEntity<ProblemDetail> longMsg = handler.conflict(new IllegalStateException("x".repeat(520)));
        assertTrue(longMsg.getBody().getDetail().endsWith("..."));
        assertEquals(503, longMsg.getBody().getDetail().length());

        ResponseEntity<ProblemDetail> unimplemented = handler.notImplemented(new UnsupportedOperationException("nope"));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, unimplemented.getStatusCode());

        ResponseEntity<ProblemDetail> status = handler.status(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "quota"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status.getStatusCode());
        assertEquals("quota", status.getBody().getDetail());
    }
}

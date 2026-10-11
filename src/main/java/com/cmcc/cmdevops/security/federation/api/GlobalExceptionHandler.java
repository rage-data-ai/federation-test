package com.cmcc.cmdevops.security.federation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<QueryResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(QueryResponse.fail(e.getMessage(), 0));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<QueryResponse> notImplemented(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(QueryResponse.fail(e.getMessage(), 0));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<QueryResponse> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status).body(QueryResponse.fail(e.getReason(), 0));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<QueryResponse> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(QueryResponse.fail("请求参数不合法", 0));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<QueryResponse> failed(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(QueryResponse.fail(e.getMessage(), 0));
    }
}

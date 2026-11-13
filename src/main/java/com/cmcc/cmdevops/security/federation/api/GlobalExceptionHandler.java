package com.cmcc.cmdevops.security.federation.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一以 RFC 7807 Problem Details 返回错误；额外附带 {@code message} 字段以兼容现有前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", safeClientMessage(e));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ProblemDetail> notImplemented(UnsupportedOperationException e) {
        return problem(HttpStatus.NOT_IMPLEMENTED, "Not Implemented", e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return problem(status, status.getReasonPhrase(), e.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> failed(Exception e) {
        log.error("未处理异常", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "内部错误，请查看服务日志");
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        body.setTitle(title);
        body.setProperty("message", detail == null ? title : detail);
        body.setProperty("success", false);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(body);
    }

    private static String safeClientMessage(IllegalStateException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "请求无法完成";
        }
        if (message.length() > 500) {
            return message.substring(0, 500) + "...";
        }
        return message;
    }
}

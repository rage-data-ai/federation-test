package com.cmcc.cmdevops.security.federation.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.model.QueryResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<QueryResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(QueryResponse.fail(e.getMessage(), 0));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<QueryResponse> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(QueryResponse.fail(safeClientMessage(e), 0));
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
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(QueryResponse.fail("内部错误，请查看服务日志", 0));
    }

    private static String safeClientMessage(IllegalStateException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "请求无法完成";
        }
        // SQL 执行失败等业务态消息可以回传；避免堆栈与内部细节
        if (message.length() > 500) {
            return message.substring(0, 500) + "...";
        }
        return message;
    }
}

package com.pingan.rag;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Map;

@RestControllerAdvice
final class ApiErrors {
    @ExceptionHandler(ModelException.class)
    ResponseEntity<Map<String, Object>> model(ModelException e) { return response(502, e.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) { return response(422, e.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> large(Exception e) { return response(413, "文件超过 10 MB 或请求超过 11 MB 限制"); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception e) { return response(400, "请求格式无效或缺少必要字段"); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) { return response(e.getStatusCode().value(), e.getReason()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("请求处理失败，类型：{}", e.getClass().getSimpleName());
        return response(500, "服务内部错误，请检查服务日志");
    }
    private ResponseEntity<Map<String, Object>> response(int status, String detail) {
        return ResponseEntity.status(status).body(Json.map("detail", detail));
    }
}

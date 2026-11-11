package com.campagent.common;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.security.access.AccessDeniedException;
@RestControllerAdvice
public class Errors {
    private final Logger log=LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) { var response=ResponseEntity.status(e.status); if(e.status==HttpStatus.TOO_MANY_REQUESTS)response.header("Retry-After",String.valueOf(e.retryAfter)); return response.body(Map.of("message",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message","输入无效，请检查必填字段和格式")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large() { return ResponseEntity.status(413).body(Map.of("message","文件不能超过 10MB")); }
    @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("message","没有操作权限")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e) {
        String id=java.util.Objects.toString(org.slf4j.MDC.get("requestId"),java.util.UUID.randomUUID().toString()); log.error("requestFailure id={} type={}",id,e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("message","服务暂时无法完成请求","requestId",id));
    }
}


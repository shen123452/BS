package com.shen.hospital.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> fileSize(Exception ex) {
        return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("DOCUMENT_TOO_LARGE", "资料超过10MB，请缩小文件后重试"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
        String message = error.map(e -> e.getField() + "：" + e.getDefaultMessage()).orElse("参数不合法");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("INVALID_ARGUMENT", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ex) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("INVALID_ARGUMENT", "参数格式或取值不合法"));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedContentType(Exception ex) {
        return ResponseEntity.status(415).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("UNSUPPORTED_MEDIA_TYPE", "请使用application/json提交请求正文"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMethod(Exception ex) {
        return ResponseEntity.status(405).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("METHOD_NOT_ALLOWED", "当前接口不支持该请求方法"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("DUPLICATE_RESOURCE", "账号或编码已存在"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(DataAccessException ex) {
        log.warn("Database operation failed, traceId={}, type={}", MDC.get("traceId"), ex.getClass().getSimpleName());
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("DATABASE_UNAVAILABLE", "数据服务暂不可用，请稍后重试"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("FORBIDDEN", "没有操作权限"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing(NoResourceFoundException ex) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("RESOURCE_NOT_FOUND", "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception ex) {
        log.error("Request failed, traceId={}, type={}", MDC.get("traceId"), ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.error("INTERNAL_ERROR", "服务处理失败"));
    }
}

package com.glucose.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.MDC;

@Data
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data, MDC.get("traceId"));
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message != null ? message : errorCode.getDefaultMessage(), null, MDC.get("traceId"));
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }
}

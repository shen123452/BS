package com.glucose.common.api;

import lombok.Getter;

/**
 * 开发文档 附录 A:错误码与状态。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),
    PARAM_ERROR(40001, 400, "参数或格式错误"),
    DUPLICATE_RECORD(40002, 400, "有效记录重复"),
    VALUE_OUT_OF_RANGE(40003, 400, "数值超接收范围或精度不符"),
    FILE_INVALID(40004, 400, "文件类型、大小、行数或模板不符"),
    BATCH_NOT_CONFIRMABLE(40005, 400, "批次预检错误或冲突,不能确认"),
    UNAUTHORIZED(40101, 401, "未登录或登录已失效"),
    FORBIDDEN(40301, 403, "无权限访问该资源"),
    NOT_FOUND(40401, 404, "资源不存在"),
    VERSION_CONFLICT(40901, 409, "数据版本已变化,请刷新后重试"),
    IMPORT_REPREVIEW_REQUIRED(40902, 409, "导入确认条件变化,需重新预检"),
    RATE_LIMITED(42901, 429, "请求过于频繁或超出额度限制"),
    INTERNAL_ERROR(50001, 500, "服务内部异常"),
    UPSTREAM_UNAVAILABLE(50301, 503, "外部依赖不可用且无法降级");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}

package com.glucose.common.enums;

/**
 * 开发文档 附录 B:枚举。存储为字符串的字段统一使用本常量类,避免拼写漂移。
 */
public final class Codes {

    private Codes() {
    }

    // 角色
    public static final String ROLE_PATIENT = "PATIENT";
    public static final String ROLE_DOCTOR = "DOCTOR";
    public static final String ROLE_ADMIN = "ADMIN";

    // data_source
    public static final String SOURCE_SMBG = "SMBG";
    public static final String SOURCE_CGM = "CGM";

    // ingest_method
    public static final String INGEST_MANUAL = "MANUAL";
    public static final String INGEST_CSV = "CSV";
    public static final String INGEST_GENERATOR = "GENERATOR";

    // data_kind
    public static final String KIND_REAL = "REAL";
    public static final String KIND_DEMO = "DEMO";

    // alert_type
    public static final String ALERT_LOW = "LOW";
    public static final String ALERT_HIGH = "HIGH";

    // event_status
    public static final String EVENT_OPEN = "OPEN";
    public static final String EVENT_ENDED = "ENDED";
    public static final String EVENT_END_UNKNOWN = "END_UNKNOWN";
    public static final String EVENT_INVALIDATED = "INVALIDATED";

    // handle_status
    public static final String HANDLE_PENDING = "PENDING";
    public static final String HANDLE_HANDLED = "HANDLED";

    // answer_mode
    public static final String ANSWER_RAG = "RAG";
    public static final String ANSWER_RETRIEVAL_ONLY = "RETRIEVAL_ONLY";
    public static final String ANSWER_NO_COVERAGE = "NO_COVERAGE";
    public static final String ANSWER_FIXED_NOTICE = "FIXED_NOTICE";

    // 批次状态
    public static final String BATCH_READY = "READY";
    public static final String BATCH_REJECTED = "REJECTED";
    public static final String BATCH_CONFIRMED = "CONFIRMED";
    public static final String BATCH_NO_CHANGE = "NO_CHANGE";
    public static final String BATCH_EXPIRED = "EXPIRED";
    public static final String BATCH_REVOKED = "REVOKED";

    // metric_status
    public static final String METRIC_QUALIFIED = "QUALIFIED";
    public static final String METRIC_DESCRIPTIVE_ONLY = "DESCRIPTIVE_ONLY";
    public static final String METRIC_INSUFFICIENT_CGM = "INSUFFICIENT_CGM";
    public static final String METRIC_NO_DATA = "NO_DATA";

    // reason_codes
    public static final String REASON_SMBG_NOT_TIME_BASED = "SMBG_NOT_TIME_BASED";
    public static final String REASON_TARGET_NOT_CONFIGURED = "TARGET_NOT_CONFIGURED";
    public static final String REASON_SHORT_WINDOW = "SHORT_WINDOW";
    public static final String REASON_LOW_COVERAGE = "LOW_COVERAGE";
    public static final String REASON_INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES";
    public static final String REASON_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    public static final String ALGORITHM_VERSION = "glucose-v1.2.0";
}

package com.glucose.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long patientId;
    private String dataSource;
    private String dataKind;
    private String alertType;
    /** 2 警告,3 严重;取事件内最高级别 */
    private Integer level;
    private Long firstRecordId;
    private Long lastRecordId;
    private LocalDateTime startAt;
    private LocalDateTime lastAbnormalAt;
    private LocalDateTime endAt;
    private BigDecimal minGlucose;
    private BigDecimal maxGlucose;
    private Integer sampleCount;
    private String eventStatus;
    private String handleStatus;
    private Integer isHistorical;
    private String message;
    private String ruleSnapshot;
    /** 仅 OPEN 时有值;关闭后置 NULL */
    private String activeKey;
    private Long version;
    private Long noticeVersion;
    private Long patientSeenNoticeVersion;
    private LocalDateTime lastNotifiedAt;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

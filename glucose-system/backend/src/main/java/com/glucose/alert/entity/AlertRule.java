package com.glucose.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("alert_rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0 为全局基础规则 */
    private Long patientId;
    private String ruleType;
    private BigDecimal warningThreshold;
    private BigDecimal severeThreshold;
    private Integer enabled;
    private Long version;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}

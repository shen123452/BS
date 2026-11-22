package com.glucose.alert.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucose.alert.entity.AlertRecord;
import com.glucose.alert.entity.AlertRule;
import com.glucose.alert.mapper.AlertRecordMapper;
import com.glucose.alert.mapper.AlertRuleMapper;
import com.glucose.common.enums.Codes;
import com.glucose.common.enums.RiskFlag;
import com.glucose.glucose.entity.GlucoseRecord;
import com.glucose.glucose.mapper.GlucoseRecordMapper;
import com.glucose.system.service.AuditService;
import com.glucose.patient.mapper.PatientProfileMapper;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 基础提醒与事件合并(开发文档 §7.6):
 * - 规则:LOW g<3.9(其中 g<3.0 严重);HIGH g>13.9(其中 g>16.7 严重);严重优先。
 * - 合并键:患者+来源+数据性质+方向;相邻异常点间隔 ≤15 分钟才合并,正常恢复点结束事件。
 * - 患者级锁 + active_key 唯一键防并发重复;OPEN 之外状态 active_key 置空。
 * - 修改/删除/撤销后全量重算该来源事件;失去依据的事件置 INVALIDATED,不抹掉处理历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final long MERGE_GAP_MINUTES = 15;

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper alertMapper;
    private final GlucoseRecordMapper recordMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final PatientProfileMapper profileMapper;

    public record Classification(RiskFlag flag, String alertType, Integer level) {
    }

    /** 按全局基础规则对单个读数分级;严重判断优先,同方向只命中一条。 */
    public Classification classify(BigDecimal glucose, List<AlertRule> rules) {
        for (AlertRule rule : rules) {
            if (!Codes.ALERT_LOW.equals(rule.getRuleType()) || rule.getEnabled() != 1) {
                continue;
            }
            if (glucose.compareTo(rule.getSevereThreshold()) < 0) {
                return new Classification(RiskFlag.LOW_SEVERE, Codes.ALERT_LOW, 3);
            }
            if (glucose.compareTo(rule.getWarningThreshold()) < 0) {
                return new Classification(RiskFlag.LOW, Codes.ALERT_LOW, 2);
            }
        }
        for (AlertRule rule : rules) {
            if (!Codes.ALERT_HIGH.equals(rule.getRuleType()) || rule.getEnabled() != 1) {
                continue;
            }
            if (glucose.compareTo(rule.getSevereThreshold()) > 0) {
                return new Classification(RiskFlag.HIGH_SEVERE, Codes.ALERT_HIGH, 3);
            }
            if (glucose.compareTo(rule.getWarningThreshold()) > 0) {
                return new Classification(RiskFlag.HIGH, Codes.ALERT_HIGH, 2);
            }
        }
        return new Classification(RiskFlag.NONE, null, null);
    }

    public List<AlertRule> loadGlobalRules() {
        return ruleMapper.selectList(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getPatientId, 0L).eq(AlertRule::getEnabled, 1));
    }

    /**
     * 数据变化后按同来源重算事件。调用方写入前也必须先锁患者档案行。
     */
    @Transactional
    public void recomputeAfterChange(long patientId, String dataSource, String dataKind, boolean historical) {
        profileMapper.requireLock(patientId);
        doRecompute(patientId, dataSource, dataKind, historical);
    }

    private void doRecompute(long patientId, String dataSource, String dataKind, boolean historical) {
        List<AlertRule> rules = loadGlobalRules();
        List<GlucoseRecord> records = recordMapper.selectList(Wrappers.<GlucoseRecord>lambdaQuery()
                .eq(GlucoseRecord::getPatientId, patientId)
                .eq(GlucoseRecord::getDataSource, dataSource)
                .eq(GlucoseRecord::getDataKind, dataKind)
                .orderByAsc(GlucoseRecord::getMeasuredAt));
        List<AlertRecord> events = alertMapper.selectList(Wrappers.<AlertRecord>lambdaQuery()
                .eq(AlertRecord::getPatientId, patientId)
                .eq(AlertRecord::getDataSource, dataSource)
                .eq(AlertRecord::getDataKind, dataKind)
                .ne(AlertRecord::getEventStatus, Codes.EVENT_INVALIDATED));

        List<Segment> segments = buildSegments(records, rules);
        LocalDateTime now = LocalDateTime.now();
        // 先释放已失效/已结束事件的唯一键，再创建末段，避免旧键挡住新事件。
        for (AlertRecord event : events) {
            if (segments.stream().noneMatch(s -> s.alertType().equals(event.getAlertType()) && s.startAt().equals(event.getStartAt()))) {
                invalidateEvent(event);
            }
        }
        for (Segment seg : segments.stream().sorted(Comparator.comparing(Segment::open)).toList()) {
            AlertRecord match = events.stream()
                    .filter(e -> seg.alertType.equals(e.getAlertType()))
                    .filter(e -> seg.startAt.equals(e.getStartAt()))
                    .findFirst().orElse(null);
            if (match == null) {
                createEvent(patientId, dataSource, dataKind, seg, rules, historical, now);
            } else {
                updateEvent(match, seg, rules, historical, now);
            }
        }
    }

    private record Segment(String alertType, int level, LocalDateTime startAt, LocalDateTime lastAbnormalAt,
                           LocalDateTime endAt, BigDecimal min, BigDecimal max, int sampleCount,
                           Long firstRecordId, Long lastRecordId, boolean open) {
    }

    /**
     * 把有效记录序列切成异常连续段:同方向且间隔 ≤15 分钟合并;正常点或方向变化或超间隔结束前段。
     */
    private List<Segment> buildSegments(List<GlucoseRecord> records, List<AlertRule> rules) {
        List<Segment> segments = new ArrayList<>();
        Segment current = null;
        for (GlucoseRecord record : records) {
            Classification cls = classify(record.getGlucoseMmol(), rules);
            if (cls.flag() == RiskFlag.NONE) {
                if (current != null) {
                    current = Duration.between(current.lastAbnormalAt(), record.getMeasuredAt()).compareTo(Duration.ofMinutes(MERGE_GAP_MINUTES)) <= 0
                            ? closeWithRecovery(current, record.getMeasuredAt()) : toOpenUnknown(current);
                    segments.add(current);
                    current = null;
                }
                continue;
            }
            if (current != null && cls.alertType().equals(current.alertType())) {
                Duration gap = Duration.between(current.lastAbnormalAt(), record.getMeasuredAt());
                if (gap.compareTo(Duration.ofMinutes(MERGE_GAP_MINUTES)) <= 0) {
                    current = extend(current, record, cls);
                    continue;
                }
                segments.add(toOpenUnknown(current));
                current = null;
            } else if (current != null) {
                segments.add(toOpenUnknown(current));
                current = null;
            }
            current = new Segment(cls.alertType(), cls.level(), record.getMeasuredAt(), record.getMeasuredAt(),
                    null, record.getGlucoseMmol(), record.getGlucoseMmol(), 1,
                    record.getId(), record.getId(), true);
        }
        if (current != null) {
            segments.add(current);
        }
        return segments;
    }

    private Segment extend(Segment seg, GlucoseRecord record, Classification cls) {
        return new Segment(seg.alertType(), Math.max(seg.level(), cls.level()), seg.startAt(),
                record.getMeasuredAt(), null,
                seg.min().min(record.getGlucoseMmol()), seg.max().max(record.getGlucoseMmol()),
                seg.sampleCount() + 1, seg.firstRecordId(), record.getId(), true);
    }

    /** 后续正常测量可结束合并事件。 */
    private Segment closeWithRecovery(Segment seg, LocalDateTime recoveryAt) {
        return new Segment(seg.alertType(), seg.level(), seg.startAt(), seg.lastAbnormalAt(), recoveryAt,
                seg.min(), seg.max(), seg.sampleCount(), seg.firstRecordId(), seg.lastRecordId(), false);
    }

    /** 超过 15 分钟无有效后续点:前一事件 END_UNKNOWN,end_at 为空。 */
    private Segment toOpenUnknown(Segment seg) {
        if (seg.endAt() != null || !seg.open()) {
            return seg;
        }
        return new Segment(seg.alertType(), seg.level(), seg.startAt(), seg.lastAbnormalAt(), null,
                seg.min(), seg.max(), seg.sampleCount(), seg.firstRecordId(), seg.lastRecordId(), false);
    }

    private static String status(Segment seg, boolean historical, LocalDateTime now) {
        if (seg.endAt() != null) return Codes.EVENT_ENDED;
        return seg.open() && !historical && !seg.lastAbnormalAt().isBefore(now.minusMinutes(MERGE_GAP_MINUTES))
                ? Codes.EVENT_OPEN : Codes.EVENT_END_UNKNOWN;
    }

    private void createEvent(long patientId, String dataSource, String dataKind, Segment seg,
                             List<AlertRule> rules, boolean historical, LocalDateTime now) {
        AlertRecord event = new AlertRecord();
        event.setPatientId(patientId);
        event.setDataSource(dataSource);
        event.setDataKind(dataKind);
        event.setAlertType(seg.alertType());
        event.setLevel(seg.level());
        event.setFirstRecordId(seg.firstRecordId());
        event.setLastRecordId(seg.lastRecordId());
        event.setStartAt(seg.startAt());
        event.setLastAbnormalAt(seg.lastAbnormalAt());
        event.setEndAt(seg.endAt());
        event.setMinGlucose(seg.min());
        event.setMaxGlucose(seg.max());
        event.setSampleCount(seg.sampleCount());
        event.setEventStatus(status(seg, historical, now));
        event.setHandleStatus(Codes.HANDLE_PENDING);
        boolean isHistorical = historical || seg.lastAbnormalAt().isBefore(now.minusMinutes(MERGE_GAP_MINUTES));
        event.setIsHistorical(isHistorical ? 1 : 0);
        event.setMessage(buildMessage(seg));
        event.setRuleSnapshot(snapshot(rules));
        event.setActiveKey(Codes.EVENT_OPEN.equals(event.getEventStatus()) ? activeKey(patientId, dataSource, dataKind, seg.alertType()) : null);
        event.setVersion(1L);
        event.setNoticeVersion(1L);
        event.setPatientSeenNoticeVersion(0L);
        alertMapper.insert(event);
    }

    private void updateEvent(AlertRecord event, Segment seg, List<AlertRule> rules, boolean historical, LocalDateTime now) {
        boolean changed = false;
        boolean upgraded = seg.level() > event.getLevel();
        if (upgraded && event.getHandleStatus().equals(Codes.HANDLE_HANDLED)) {
            // 升级:原处理信息留审计,重新待处理
            auditService.record(event.getHandledBy(), "alert.reopen", "alert_record",
                    String.valueOf(event.getId()),
                    "事件升级(原处理人备注:" + nullToEmpty(event.getHandleNote()) + "),重新待处理", null);
            event.setHandleStatus(Codes.HANDLE_PENDING);
            event.setHandledBy(null);
            event.setHandledAt(null);
            event.setHandleNote(null);
        }
        if (event.getLevel() != seg.level()) {
            event.setLevel(seg.level());
            changed = true;
        }
        if (!Objects.equals(event.getFirstRecordId(), seg.firstRecordId())) {
            event.setFirstRecordId(seg.firstRecordId());
            changed = true;
        }
        if (!Objects.equals(event.getLastRecordId(), seg.lastRecordId())) {
            event.setLastRecordId(seg.lastRecordId());
            changed = true;
        }
        if (!seg.startAt().equals(event.getStartAt())) {
            event.setStartAt(seg.startAt());
            changed = true;
        }
        if (!seg.lastAbnormalAt().equals(event.getLastAbnormalAt())) {
            event.setLastAbnormalAt(seg.lastAbnormalAt());
            changed = true;
        }
        if (!Objects.equals(event.getEndAt(), seg.endAt())) {
            event.setEndAt(seg.endAt());
            changed = true;
        }
        if (event.getMinGlucose().compareTo(seg.min()) != 0) {
            event.setMinGlucose(seg.min());
            changed = true;
        }
        if (event.getMaxGlucose().compareTo(seg.max()) != 0) {
            event.setMaxGlucose(seg.max());
            changed = true;
        }
        if (event.getSampleCount() != seg.sampleCount()) {
            event.setSampleCount(seg.sampleCount());
            changed = true;
        }
        String newStatus = status(seg, historical || Integer.valueOf(1).equals(event.getIsHistorical()), now);
        if (!newStatus.equals(event.getEventStatus())) {
            event.setEventStatus(newStatus);
            changed = true;
        }
        String newKey = Codes.EVENT_OPEN.equals(newStatus) ? activeKey(event.getPatientId(), event.getDataSource(),
                event.getDataKind(), seg.alertType()) : null;
        if (!Objects.equals(event.getActiveKey(), newKey)) {
            event.setActiveKey(newKey);
            changed = true;
        }
        if (!event.getMessage().equals(buildMessage(seg))) {
            event.setMessage(buildMessage(seg));
            changed = true;
        }
        if (changed) {
            event.setVersion(event.getVersion() + 1);
            // 新建或严重度升级才递增 notice_version;普通采样不重复制造未读
            if (upgraded) {
                event.setNoticeVersion(event.getNoticeVersion() + 1);
            }
            persistEvent(event);
        }
    }

    private void invalidateEvent(AlertRecord event) {
        event.setEventStatus(Codes.EVENT_INVALIDATED);
        event.setActiveKey(null);
        event.setVersion(event.getVersion() + 1);
        persistEvent(event);
        auditService.record(null, "alert.invalidate", "alert_record", String.valueOf(event.getId()),
                "事件失去数据依据,置为 INVALIDATED(保留处理历史)", null);
    }

    private void persistEvent(AlertRecord event) {
        // 显式写 NULL；不回写患者已读版本，避免覆盖同时发生的已读操作。
        int updated = alertMapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getId, event.getId()).eq(AlertRecord::getVersion, event.getVersion() - 1)
                .set(AlertRecord::getLevel, event.getLevel())
                .set(AlertRecord::getFirstRecordId, event.getFirstRecordId()).set(AlertRecord::getLastRecordId, event.getLastRecordId())
                .set(AlertRecord::getStartAt, event.getStartAt()).set(AlertRecord::getLastAbnormalAt, event.getLastAbnormalAt())
                .set(AlertRecord::getEndAt, event.getEndAt()).set(AlertRecord::getActiveKey, event.getActiveKey())
                .set(AlertRecord::getMinGlucose, event.getMinGlucose()).set(AlertRecord::getMaxGlucose, event.getMaxGlucose())
                .set(AlertRecord::getSampleCount, event.getSampleCount()).set(AlertRecord::getEventStatus, event.getEventStatus())
                .set(AlertRecord::getMessage, event.getMessage()).set(AlertRecord::getVersion, event.getVersion())
                .set(AlertRecord::getNoticeVersion, event.getNoticeVersion())
                .set(AlertRecord::getHandleStatus, event.getHandleStatus()).set(AlertRecord::getHandledBy, event.getHandledBy())
                .set(AlertRecord::getHandledAt, event.getHandledAt()).set(AlertRecord::getHandleNote, event.getHandleNote()));
        if (updated != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "预警已变化，请重试本次操作");
    }

    @Scheduled(fixedDelay = 60000)
    public void expireOpenEvents() {
        alertMapper.update(null, Wrappers.<AlertRecord>lambdaUpdate()
                .eq(AlertRecord::getEventStatus, Codes.EVENT_OPEN)
                .lt(AlertRecord::getLastAbnormalAt, LocalDateTime.now().minusMinutes(MERGE_GAP_MINUTES))
                .set(AlertRecord::getEventStatus, Codes.EVENT_END_UNKNOWN)
                .set(AlertRecord::getEndAt, null).set(AlertRecord::getActiveKey, null)
                .setSql("version = version + 1"));
    }

    private static String activeKey(long patientId, String source, String kind, String type) {
        return patientId + ":" + source + ":" + kind + ":" + type;
    }

    private static String buildMessage(Segment seg) {
        if (Codes.ALERT_LOW.equals(seg.alertType())) {
            return seg.level() == 3
                    ? "读数明显异常,请及时寻求专业医疗帮助(最低 " + seg.min() + " mmol/L)"
                    : "低读数提醒:最低 " + seg.min() + " mmol/L,请关注";
        }
        return seg.level() == 3
                ? "读数明显异常,请及时寻求专业医疗帮助(最高 " + seg.max() + " mmol/L)"
                : "高读数提醒:最高 " + seg.max() + " mmol/L,请关注";
    }

    private static String snapshot(List<AlertRule> rules) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            for (AlertRule rule : rules) {
                snap.put(rule.getRuleType(), Map.of(
                        "warning", rule.getWarningThreshold(),
                        "severe", rule.getSevereThreshold(),
                        "version", rule.getVersion()));
            }
            return new ObjectMapper().writeValueAsString(snap);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

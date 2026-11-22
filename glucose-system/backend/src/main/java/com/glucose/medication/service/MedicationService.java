package com.glucose.medication.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.medication.entity.*;
import com.glucose.medication.mapper.*;
import com.glucose.patient.mapper.PatientProfileMapper;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MedicationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final PatientProfileMapper profiles;
    private final MedicationPlanMapper plans;
    private final MedicationLogMapper logs;

    public record PlanReq(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId,
                          @NotBlank @Size(max=100) String drugName, @NotBlank @Size(max=50) String dosage,
                          @NotBlank @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String scheduledTime,
                          @NotNull LocalDate startDate, LocalDate endDate, @Size(max=200) String instruction) {}
    public record StatusReq(@NotBlank @Pattern(regexp="PENDING|TAKEN|SKIPPED") String status,
                            @NotNull @Positive Long version) {}
    public record PlanVO(Long id, String drugName, String dosage, String scheduledTime, LocalDate startDate,
                         LocalDate endDate, String instruction, boolean active, LocalDate stoppedDate, Long version) {}
    public record LogVO(Long id, Long planId, LocalDate recordDate, String drugName, String dosage,
                        String scheduledTime, String instruction, String status, String takenAt, Long version) {}
    public record DayVO(LocalDate date, boolean editable, long pending, long taken, long skipped, long cancelled,
                        List<LogVO> records) {}

    public List<PlanVO> listPlans(long patientId) {
        var ongoing = plans.selectList(Wrappers.<MedicationPlan>lambdaQuery().eq(MedicationPlan::getPatientId, patientId)
                .eq(MedicationPlan::getActive, 1).and(w -> w.isNull(MedicationPlan::getEndDate).or().ge(MedicationPlan::getEndDate, today()))
                .orderByAsc(MedicationPlan::getScheduledTime).orderByDesc(MedicationPlan::getId));
        var closed = plans.selectList(Wrappers.<MedicationPlan>lambdaQuery().eq(MedicationPlan::getPatientId, patientId)
                .and(w -> w.eq(MedicationPlan::getActive, 0).or().lt(MedicationPlan::getEndDate, today()))
                .orderByDesc(MedicationPlan::getId).last("LIMIT 200"));
        return java.util.stream.Stream.concat(ongoing.stream(), closed.stream()).map(this::planVO).toList();
    }

    @Transactional
    public PlanVO create(long patientId, PlanReq req) {
        profiles.requireLock(patientId);
        // Check idempotency before current-date validation, so a timed-out request can replay on a later day.
        var old = plans.selectOne(Wrappers.<MedicationPlan>lambdaQuery().eq(MedicationPlan::getPatientId, patientId)
                .eq(MedicationPlan::getRequestId, req.requestId()));
        LocalTime time = LocalTime.parse(req.scheduledTime(), TIME);
        String name = req.drugName().trim(), dose = req.dosage().trim(), instruction = clean(req.instruction());
        if (old != null) {
            if (!Objects.equals(old.getDrugName(), name) || !Objects.equals(old.getDosage(), dose)
                    || !Objects.equals(old.getScheduledTime(), time) || !Objects.equals(old.getStartDate(), req.startDate())
                    || !Objects.equals(old.getEndDate(), req.endDate()) || !Objects.equals(clean(old.getInstruction()), instruction)) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, "本次提交内容已变化，请重新添加计划");
            }
            return planVO(old);
        }
        LocalDate today = today();
        if (name.isBlank() || dose.isBlank() || req.startDate().isBefore(today) || req.startDate().isAfter(today.plusYears(1))
                || (req.endDate() != null && (req.endDate().isBefore(req.startDate()) || req.endDate().isAfter(req.startDate().plusYears(2))))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始日期须在今天至一年内，结束日期须在开始日期至两年内");
        }
        long count = plans.selectCount(Wrappers.<MedicationPlan>lambdaQuery().eq(MedicationPlan::getPatientId, patientId)
                .eq(MedicationPlan::getActive, 1).and(w -> w.isNull(MedicationPlan::getEndDate).or().ge(MedicationPlan::getEndDate, today)));
        if (count >= 50) throw new BusinessException(ErrorCode.PARAM_ERROR, "进行中的记录计划最多50项，请先停用不再使用的计划");
        var plan = new MedicationPlan();
        plan.setPatientId(patientId); plan.setRequestId(req.requestId()); plan.setDrugName(name); plan.setDosage(dose);
        plan.setScheduledTime(time); plan.setStartDate(req.startDate()); plan.setEndDate(req.endDate());
        plan.setInstruction(instruction); plan.setActive(1); plan.setVersion(1L);
        plans.insert(plan);
        logs.materialize(patientId, today);
        return planVO(plan);
    }

    @Transactional
    public DayVO day(long patientId, LocalDate date) {
        LocalDate day = date == null ? today() : date;
        if (day.isAfter(today()) || day.isBefore(today().minusDays(89))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "可查看今天及之前89天的记录");
        }
        profiles.requireLock(patientId);
        logs.materialize(patientId, day);
        var records = logs.selectList(Wrappers.<MedicationLog>lambdaQuery().eq(MedicationLog::getPatientId, patientId)
                .eq(MedicationLog::getRecordDate, day).orderByAsc(MedicationLog::getScheduledTime).orderByAsc(MedicationLog::getId))
                .stream().map(this::logVO).toList();
        return new DayVO(day, day.equals(today()), count(records, "PENDING"), count(records, "TAKEN"),
                count(records, "SKIPPED"), count(records, "CANCELLED"), records);
    }

    @Transactional
    public PlanVO stop(long patientId, long id, long version) {
        profiles.requireLock(patientId);
        var plan = ownedPlan(patientId, id);
        if (plan.getActive() == 0) return planVO(plan); // Repeated stop is harmless.
        if (plan.getVersion() != version) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        LocalDate today = today();
        logs.materialize(patientId, today);
        int changed = plans.update(null, Wrappers.<MedicationPlan>lambdaUpdate().eq(MedicationPlan::getId, id)
                .eq(MedicationPlan::getPatientId, patientId).eq(MedicationPlan::getVersion, version)
                .set(MedicationPlan::getActive, 0).set(MedicationPlan::getStoppedDate, today).setSql("version=version+1"));
        if (changed != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        logs.cancelPending(patientId, id, today);
        return planVO(ownedPlan(patientId, id));
    }

    @Transactional
    public LogVO setStatus(long patientId, long id, StatusReq req) {
        profiles.requireLock(patientId);
        var log = logs.selectById(id);
        if (log == null || !Objects.equals(log.getPatientId(), patientId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!log.getRecordDate().equals(today())) throw new BusinessException(ErrorCode.PARAM_ERROR, "只能修改今天的记录");
        // A replay must not stamp a later time or increase the version.
        if (Objects.equals(log.getStatus(), req.status())) return logVO(log);
        if (!Objects.equals(log.getVersion(), req.version())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if ("CANCELLED".equals(log.getStatus())) throw new BusinessException(ErrorCode.PARAM_ERROR, "此记录计划已停用");
        // Undoing a finished entry remains possible after stopping a plan, without reopening a cancelled task.
        String status = req.status();
        if ("PENDING".equals(status) && log.getPlanId() != null && ownedPlan(patientId, log.getPlanId()).getActive() == 0) {
            status = "CANCELLED";
        }
        LocalDateTime takenAt = "TAKEN".equals(status) ? LocalDateTime.now(ZONE).withNano(0) : null;
        int changed = logs.update(null, Wrappers.<MedicationLog>lambdaUpdate().eq(MedicationLog::getId, id)
                .eq(MedicationLog::getPatientId, patientId).eq(MedicationLog::getVersion, req.version())
                .set(MedicationLog::getStatus, status).set(MedicationLog::getTakenAt, takenAt)
                .set(MedicationLog::getTaken, "TAKEN".equals(status) ? 1 : 0).setSql("version=version+1"));
        if (changed != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return logVO(logs.selectById(id));
    }

    private MedicationPlan ownedPlan(long patientId, long id) {
        var plan = plans.selectById(id);
        if (plan == null || !Objects.equals(plan.getPatientId(), patientId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        return plan;
    }
    private long count(List<LogVO> records, String status) { return records.stream().filter(r -> status.equals(r.status())).count(); }
    private String clean(String s) { return s == null ? "" : s.trim(); }
    private LocalDate today() { return LocalDate.now(ZONE); }
    private PlanVO planVO(MedicationPlan p) {
        return new PlanVO(p.getId(), p.getDrugName(), p.getDosage(), p.getScheduledTime().format(TIME), p.getStartDate(),
                p.getEndDate(), p.getInstruction(), p.getActive() == 1, p.getStoppedDate(), p.getVersion());
    }
    private LogVO logVO(MedicationLog l) {
        return new LogVO(l.getId(), l.getPlanId(), l.getRecordDate(), l.getDrugName(), l.getDosage(),
                l.getScheduledTime() == null ? "" : l.getScheduledTime().format(TIME), l.getInstruction(), l.getStatus(),
                l.getTakenAt() == null ? "" : l.getTakenAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), l.getVersion());
    }
}

package com.shen.hospital.outpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.*;
import com.shen.hospital.auth.domain.Role;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.masterdata.dto.*;
import com.shen.hospital.masterdata.mapper.*;
import com.shen.hospital.masterdata.service.*;
import com.shen.hospital.record.mapper.MedicalRecordMapper;
import com.shen.hospital.record.service.MedicalRecordService;
import com.shen.hospital.registration.domain.Registration;
import com.shen.hospital.registration.dto.*;
import com.shen.hospital.registration.mapper.RegistrationMapper;
import com.shen.hospital.registration.service.RegistrationService;
import com.shen.hospital.schedule.dto.*;
import com.shen.hospital.schedule.mapper.ScheduleMapper;
import com.shen.hospital.schedule.service.ScheduleService;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long", "hospital.bootstrap.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@Import(OutpatientIntegrationTest.FixedTime.class)
class OutpatientIntegrationTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atTime(9, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    MasterdataService data;
    @Autowired
    PatientService profiles;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    RegistrationService booking;
    @Autowired
    MedicalRecordService recordService;
    @Autowired
    ScheduleMapper schedules;
    @Autowired
    PatientProfileMapper patients;
    @Autowired
    DoctorMapper doctors;
    @Autowired
    JwtService jwt;
    @MockitoSpyBean
    RegistrationMapper registrations;
    @Autowired
    MedicalRecordMapper records;
    @Autowired
    @Qualifier("mysqlDataSource")
    DataSource mysql;
    @Autowired
    @Qualifier("mysqlTransactionManager")
    PlatformTransactionManager manager;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    String prefix, adminToken, patientToken, doctorToken, otherDoctorToken;
    Long departmentId, doctorId, otherDoctorId, doctorUserId, otherDoctorUserId, patientUserId, patientId;
    List<Long> createdUsers, createdPatients, createdDepartments, createdDoctors;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(mysql);
        tx = new TransactionTemplate(manager);
        createdUsers = new ArrayList<>();
        createdPatients = new ArrayList<>();
        createdDepartments = new ArrayList<>();
        createdDoctors = new ArrayList<>();
        prefix = "op_" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            var admin = auth.createUser(prefix + "_admin", "TestPassword123!", "测试管理员", Role.ADMIN);
            createdUsers.add(admin.getId());
            adminToken = jwt.issue(admin.getId());
            var department = data.createDepartment(new DepartmentRequest(prefix, "测试内科", null, "测试楼", false, "ENABLED"));
            departmentId = Long.valueOf(department.id());
            createdDepartments.add(departmentId);
            var doc = createDoctor("_doc", departmentId);
            doctorId = Long.valueOf(doc.id());
            doctorUserId = Long.valueOf(doc.userId());
            doctorToken = jwt.issue(doctorUserId);
            var other = createDoctor("_other_doc", departmentId);
            otherDoctorId = Long.valueOf(other.id());
            otherDoctorUserId = Long.valueOf(other.userId());
            otherDoctorToken = jwt.issue(otherDoctorUserId);
        });
        patientUserId = createPatient("_patient", true);
        patientId = patients.findIdByUserId(patientUserId);
        patientToken = jwt.issue(patientUserId);
    }

    @AfterEach
    void cleanup() {
        // 只清理本测试明确创建的ID；不删除管理员或其他业务数据。
        tx.executeWithoutResult(s -> {
            for (var id : createdPatients) {
                jdbc.update("DELETE mr FROM medical_record mr JOIN registration r ON mr.registration_id=r.id WHERE r.patient_id=?", id);
                jdbc.update("DELETE FROM registration WHERE patient_id=?", id);
                jdbc.update("UPDATE consultation_session SET latest_result_id=NULL WHERE patient_id=?", id);
                jdbc.update("DELETE FROM triage_result WHERE patient_id=?", id);
                jdbc.update("DELETE FROM agent_run WHERE patient_id=?", id);
                jdbc.update("DELETE FROM consultation_session WHERE patient_id=?", id);
            }
            for (var id : createdDoctors) jdbc.update("DELETE FROM doctor_schedule WHERE doctor_id=?", id);
            for (var id : createdDoctors) jdbc.update("DELETE FROM doctor WHERE id=?", id);
            for (var id : createdPatients) jdbc.update("DELETE FROM patient_profile WHERE id=?", id);
            for (var id : createdUsers) jdbc.update("DELETE FROM sys_user WHERE id=?", id);
            for (var id : createdDepartments) jdbc.update("DELETE FROM department WHERE id=?", id);
        });
    }

    DoctorView createDoctor(String suffix, Long department) {
        var doc = data.createDoctor(new DoctorRequest(prefix + suffix, "TestPassword123!", department, "测试医生" + suffix, "主治医师", "工程测试"));
        createdDoctors.add(Long.valueOf(doc.id()));
        createdUsers.add(Long.valueOf(doc.userId()));
        return doc;
    }

    Long createPatient(String suffix, boolean named) {
        return tx.execute(s -> {
            var user = auth.register(new com.shen.hospital.auth.dto.RegisterRequest(prefix + suffix, "TestPassword123!", "测试患者"));
            Long id = Long.valueOf(user.id());
            createdUsers.add(id);
            createdPatients.add(Long.valueOf(user.patientId()));
            if (named)
                profiles.update(id, new ProfileRequest("测试患者" + suffix, "UNKNOWN", 30, "NONE", null, "NONE", null));
            return id;
        });
    }

    ScheduleRequest request(Long doc, LocalDate date, String slot, int total) {
        return new ScheduleRequest(doc, date, slot, null, total, new BigDecimal("20.00"), "OPEN");
    }

    ScheduleView schedule(Long doc, LocalDate date, String slot, int total) {
        return scheduleService.create(request(doc, date, slot, total));
    }

    RegistrationRequest bookingRequest(ScheduleView schedule, String key) {
        return new RegistrationRequest(Long.valueOf(schedule.id()), key, null, "测试症状");
    }

    JsonNode response(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    void code(String expected, Runnable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action::run).code());
    }

    @Test
    void sevenDaySchedulesValidateUniquenessAvailabilityAndOwnership() throws Exception {
        for (int offset = 0; offset < 7; offset++) schedule(doctorId, TODAY.plusDays(offset), "PM", 10);
        var outside = schedule(doctorId, TODAY.plusDays(7), "PM", 10);
        mvc.perform(get("/api/schedules").param("doctorId", doctorId.toString()).header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.items[0].id").isString()).andExpect(jsonPath("$.data.items[0].bookable").value(true));
        mvc.perform(get("/api/schedules/" + outside.id()).header("Authorization", "Bearer " + patientToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/schedules").param("date", TODAY.plusDays(7).toString()).header("Authorization", "Bearer " + patientToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/doctor/schedules").header("Authorization", "Bearer " + otherDoctorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        code("DUPLICATE_SCHEDULE", () -> schedule(doctorId, TODAY, "PM", 10));
        code("INVALID_ARGUMENT", () -> schedule(doctorId, TODAY.minusDays(1), "PM", 10));
        mvc.perform(post("/api/admin/schedules").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("doctorId", doctorId, "visitDate", TODAY.plusDays(1), "timeSlot", "AM", "totalCount", 0, "fee", -1))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/schedules").header("Authorization", "Bearer " + patientToken).contentType(MediaType.APPLICATION_JSON)
                .content(body(request(doctorId, TODAY.plusDays(1), "AM", 10)))).andExpect(status().isForbidden());
        data.updateDoctor(doctorId, new DoctorUpdateRequest(departmentId, "测试医生", null, null, "DISABLED"));
        assertEquals(0, scheduleService.available(null, doctorId, null, 1, 20).total());
        code("DOCTOR_UNAVAILABLE", () -> booking.book(patientUserId, bookingRequest(outside, "disabled")));
    }

    @Test
    void bookingReplaySnapshotsCapacityHistoryAndCancellation() throws Exception {
        var s = schedule(doctorId, TODAY.plusDays(1), "AM", 2);
        var req = bookingRequest(s, "same-request");
        var created = response(mvc.perform(post("/api/patient/registrations").header("Authorization", "Bearer " + patientToken)
                .contentType(MediaType.APPLICATION_JSON).content(body(req))).andExpect(status().isCreated()).andReturn()).path("data");
        var id = Long.valueOf(created.path("id").asText());
        mvc.perform(post("/api/patient/registrations").header("Authorization", "Bearer " + patientToken).contentType(MediaType.APPLICATION_JSON)
                .content(body(req))).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()));
        assertEquals(1, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        code("REQUEST_ID_CONFLICT", () -> booking.book(patientUserId, new RegistrationRequest(Long.valueOf(s.id()), "same-request", null, "修改症状")));
        code("DUPLICATE_ACTIVE_SLOT", () -> booking.book(patientUserId, bookingRequest(s, "different-request")));
        code("SCHEDULE_IN_USE", () -> scheduleService.close(Long.valueOf(s.id())));
        code("SCHEDULE_HAS_HISTORY", () -> scheduleService.update(Long.valueOf(s.id()), request(doctorId, TODAY.plusDays(2), "AM", 2)));
        code("SCHEDULE_CAPACITY_TOO_SMALL", () -> scheduleService.update(Long.valueOf(s.id()), request(doctorId, TODAY.plusDays(1), "AM", 0)));
        var grown = scheduleService.update(Long.valueOf(s.id()), request(doctorId, TODAY.plusDays(1), "AM", 4));
        assertEquals(3, grown.remainingCount());
        data.updateDoctor(doctorId, new DoctorUpdateRequest(departmentId, "更名医生", null, null, "ENABLED"));
        data.updateDepartment(departmentId, new DepartmentRequest(prefix, "更名科室", null, null, false, "ENABLED"));
        assertEquals("测试医生_doc", booking.patientEntity(patientUserId, id).getDoctorNameSnapshot());
        assertEquals("测试内科", booking.patientEntity(patientUserId, id).getDepartmentNameSnapshot());
        mvc.perform(post("/api/patient/registrations/" + id + "/cancel").header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        booking.cancel(patientUserId, id, "重复取消");
        assertEquals(4, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        assertEquals("CANCELLED", booking.book(patientUserId, req).registration().status());
        assertEquals(4, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        assertNotEquals(id.toString(), booking.book(patientUserId, bookingRequest(s, "new-request")).registration().id());
        mvc.perform(post("/api/patient/registrations").header("Authorization", "Bearer " + patientToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("scheduleId", s.id(), "clientRequestId", "spoof", "patientId", "1", "fee", "0"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/patient/registrations").header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("scheduleId=" + s.id()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void fullEncounterKeepsDraftPrivateEnforcesVersionsAndLocksSubmittedRecord() throws Exception {
        var s = schedule(doctorId, TODAY, "PM", 2);
        var reg = booking.book(patientUserId, bookingRequest(s, "visit")).registration();
        String id = reg.id();
        Long otherPatient = createPatient("_other_patient", true);
        mvc.perform(get("/api/patient/registrations/" + id).header("Authorization", "Bearer " + jwt.issue(otherPatient))).andExpect(status().isNotFound());
        mvc.perform(post("/api/doctor/registrations/" + id + "/start").header("Authorization", "Bearer " + otherDoctorToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/doctor/registrations/" + id + "/start").header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        recordService.start(doctorUserId, Long.valueOf(id));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE registration_id=?", Long.class, id));
        mvc.perform(get("/api/patient/registrations/" + id + "/medical-record").header("Authorization", "Bearer " + patientToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/doctor/registrations/" + id + "/complete").header("Authorization", "Bearer " + doctorToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isUnprocessableEntity());
        Map<String, Object> content = new HashMap<>();
        content.put("version", 0);
        for (var field : List.of("chiefComplaint", "presentIllness", "pastHistory", "physicalExam", "auxiliaryExam", "preliminaryDiagnosis", "treatmentPlan"))
            content.put(field, "工程测试内容");
        String path = "/api/doctor/registrations/" + id + "/medical-record";
        mvc.perform(put(path).header("Authorization", "Bearer " + doctorToken).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put(path).header("Authorization", "Bearer " + doctorToken).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_VERSION_CONFLICT"));
        mvc.perform(post("/api/doctor/registrations/" + id + "/complete").header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        assertEquals("COMPLETED", booking.patientEntity(patientUserId, Long.valueOf(id)).getStatus());
        assertEquals("SUBMITTED", recordService.complete(doctorUserId, Long.valueOf(id), 1).status());
        mvc.perform(put(path).header("Authorization", "Bearer " + doctorToken).contentType(MediaType.APPLICATION_JSON).content(body(content)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/patient/registrations/" + id + "/medical-record").header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.chiefComplaint").value("工程测试内容"));
        mvc.perform(get("/api/patient/registrations/" + id + "/medical-record").header("Authorization", "Bearer " + jwt.issue(otherPatient))).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", "Bearer " + otherDoctorToken)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
        code("REGISTRATION_NOT_CANCELLABLE", () -> booking.cancel(patientUserId, Long.valueOf(id), null));
    }

    @Test
    void deadlinesAndMissingProfileBlockWritesWithoutTakingStock() {
        Long unnamed = createPatient("_unnamed", false);
        var s = schedule(doctorId, TODAY.plusDays(1), "PM", 2);
        code("PROFILE_INCOMPLETE", () -> booking.book(unnamed, bookingRequest(s, "missing-profile")));
        var reg = booking.book(patientUserId, bookingRequest(s, "future")).registration();
        code("VISIT_DATE_MISMATCH", () -> recordService.start(doctorUserId, Long.valueOf(reg.id())));
        jdbc.update("UPDATE doctor_schedule SET booking_deadline=? WHERE id=?", TODAY.atTime(9, 0), s.id());
        code("BOOKING_EXPIRED", () -> booking.cancel(patientUserId, Long.valueOf(reg.id()), null));
        Long other = createPatient("_late", true);
        code("BOOKING_EXPIRED", () -> booking.book(other, bookingRequest(s, "late")));
        assertEquals(1, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
    }

    @Test
    void lastSlotWithTwelvePatientsHasExactlyOneWinner() throws Exception {
        var s = schedule(doctorId, TODAY.plusDays(1), "AM", 1);
        var users = new ArrayList<Long>();
        users.add(patientUserId);
        for (int i = 1; i < 12; i++) users.add(createPatient("_race_" + i, true));
        var tasks = users.stream().<Supplier<Object>>map(user -> () -> booking.book(user, bookingRequest(s, "last-slot"))).toList();
        var results = concurrent(tasks);
        assertEquals(1, results.stream().filter(RegistrationService.BookingResult.class::isInstance).count());
        for (var result : results) if (result instanceof BusinessException e) assertEquals("SCHEDULE_FULL", e.code());
        assertEquals(0, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        assertEquals(1L, registrations.selectCount(new LambdaQueryWrapper<Registration>().eq(Registration::getScheduleId, Long.valueOf(s.id()))));
    }

    @Test
    void concurrentSameRequestOnlyTakesStockOnce() throws Exception {
        var s = schedule(doctorId, TODAY.plusDays(1), "AM", 8);
        var tasks = new ArrayList<Supplier<Object>>();
        for (int i = 0; i < 8; i++) tasks.add(() -> booking.book(patientUserId, bookingRequest(s, "same-key")));
        var results = concurrent(tasks);
        var bookings = results.stream().map(RegistrationService.BookingResult.class::cast).toList();
        assertEquals(1, bookings.stream().filter(RegistrationService.BookingResult::created).count());
        assertEquals(1, bookings.stream().map(b -> b.registration().id()).distinct().count());
        assertEquals(7, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
    }

    @Test
    void concurrentSamePatientDifferentDoctorsAllowsOnlyOneActiveSlot() throws Exception {
        var a = schedule(doctorId, TODAY.plusDays(1), "AM", 2);
        var b = schedule(otherDoctorId, TODAY.plusDays(1), "AM", 2);
        var results = concurrent(List.of(() -> booking.book(patientUserId, bookingRequest(a, "a")),
                () -> booking.book(patientUserId, bookingRequest(b, "b"))));
        assertEquals(1, results.stream().filter(RegistrationService.BookingResult.class::isInstance).count());
        assertEquals("DUPLICATE_ACTIVE_SLOT", results.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast).findFirst().orElseThrow().code());
        assertEquals(3, schedules.selectById(Long.valueOf(a.id())).getRemainingCount() + schedules.selectById(Long.valueOf(b.id())).getRemainingCount());
    }

    @Test
    void concurrentDoubleCancelAndCancelVersusStartKeepOneStatePath() throws Exception {
        var s = schedule(doctorId, TODAY, "PM", 2);
        Long first = Long.valueOf(booking.book(patientUserId, bookingRequest(s, "cancel-twice")).registration().id());
        concurrent(List.of(() -> booking.cancel(patientUserId, first, "one"), () -> booking.cancel(patientUserId, first, "two")));
        assertEquals(2, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        Long second = Long.valueOf(booking.book(patientUserId, bookingRequest(s, "cancel-start")).registration().id());
        var results = concurrent(List.of(() -> booking.cancel(patientUserId, second, null), () -> recordService.start(doctorUserId, second)));
        String state = registrations.selectById(second).getStatus();
        assertTrue(Set.of("CANCELLED", "IN_PROGRESS").contains(state));
        assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
        assertEquals("CANCELLED".equals(state) ? 2 : 1, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        assertEquals("CANCELLED".equals(state) ? 0L : 1L, jdbc.queryForObject("SELECT COUNT(*) FROM medical_record WHERE registration_id=?", Long.class, second));
    }

    @Test
    void concurrentRecordSaveSameVersionHasOneWinner() throws Exception {
        var s = schedule(doctorId, TODAY, "PM", 1);
        Long id = Long.valueOf(booking.book(patientUserId, bookingRequest(s, "save")).registration().id());
        recordService.start(doctorUserId, id);
        var request = new com.shen.hospital.record.dto.RecordRequest(0, "主诉", "现病史", "既往史", "查体", "检查", "初步诊断", "计划");
        var results = concurrent(List.of(() -> recordService.save(doctorUserId, id, request), () -> recordService.save(doctorUserId, id, request)));
        assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
        assertEquals(1, records.byRegistration(id).getVersion());
        assertEquals("RECORD_VERSION_CONFLICT", results.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast).findFirst().orElseThrow().code());
    }

    @Test
    void bookingInsertFailureAndRecordCompletionFailureRollBackTogether() {
        var s = schedule(doctorId, TODAY, "PM", 2);
        doThrow(new DataIntegrityViolationException("injected insert failure")).when(registrations).insert(any(Registration.class));
        assertThrows(DataIntegrityViolationException.class, () -> booking.book(patientUserId, bookingRequest(s, "failure")));
        assertEquals(2, schedules.selectById(Long.valueOf(s.id())).getRemainingCount());
        assertNull(registrations.selectOne(new LambdaQueryWrapper<Registration>().eq(Registration::getPatientId, patientId)));
        reset(registrations);
        Long id = Long.valueOf(booking.book(patientUserId, bookingRequest(s, "complete-failure")).registration().id());
        recordService.start(doctorUserId, id);
        recordService.save(doctorUserId, id, new com.shen.hospital.record.dto.RecordRequest(0, "主诉", "现病史", "既往史", "查体", "检查", "诊断", "计划"));
        doReturn(0).when(registrations).complete(eq(id), eq(doctorId), any(LocalDateTime.class));
        code("RECORD_VERSION_CONFLICT", () -> recordService.complete(doctorUserId, id, 1));
        assertEquals("IN_PROGRESS", registrations.selectById(id).getStatus());
        var record = records.byRegistration(id);
        assertEquals("DRAFT", record.getStatus());
        assertEquals(1, record.getVersion());
        assertNull(record.getSubmittedAt());
    }

    @Test
    void doctorTransferRequiresHandlingFutureBookingsAndSchedules() {
        Long target = tx.execute(s -> {
            var dep = data.createDepartment(new DepartmentRequest(prefix + "_target", "目标科室", null, null, false, "ENABLED"));
            var id = Long.valueOf(dep.id());
            createdDepartments.add(id);
            return id;
        });
        var s = schedule(doctorId, TODAY.plusDays(1), "PM", 2);
        var update = new DoctorUpdateRequest(target, "转科医生", null, null, "ENABLED");
        code("DOCTOR_IN_USE", () -> data.updateDoctor(doctorId, update));
        var reg = booking.book(patientUserId, bookingRequest(s, "transfer")).registration();
        code("DOCTOR_IN_USE", () -> data.updateDoctor(doctorId, update));
        booking.cancel(patientUserId, Long.valueOf(reg.id()), null);
        scheduleService.close(Long.valueOf(s.id()));
        assertEquals(target.toString(), data.updateDoctor(doctorId, update).departmentId());
        assertEquals(departmentId, schedules.selectById(Long.valueOf(s.id())).getDepartmentId());
        assertEquals("测试内科", booking.patientEntity(patientUserId, Long.valueOf(reg.id())).getDepartmentNameSnapshot());
    }

    @Test
    void triageAssociationChecksOwnershipExpirySummaryRiskAndRecommendedDepartments() {
        var primarySchedule = schedule(doctorId, TODAY.plusDays(1), "PM", 3);
        Long resultId = tx.execute(s -> {
            // 仅为关联规则构造固定结果，不调用模型，也不声称业务Agent已实现。
            jdbc.update("""
                    INSERT INTO consultation_session(patient_id,title,summary_json,summary_version,summary_confirmed_at)
                    VALUES (?,?,'{}',1,?)
                    """, patientId, prefix, TODAY.atTime(8, 0));
            Long sessionId = jdbc.queryForObject("SELECT id FROM consultation_session WHERE patient_id=? AND title=?", Long.class, patientId, prefix);
            jdbc.update("""
                    INSERT INTO agent_run(run_no,patient_id,session_id,client_request_id,request_hash,summary_version,input_snapshot,
                      model_name,config_fingerprint,prompt_hash,tool_names,safety_rule_version,status,deadline_at,trace_id)
                    VALUES (?,?,?,? ,REPEAT('a',64),1,'{}','test-only',REPEAT('b',64),REPEAT('c',64),'[]','test-v1','SUCCEEDED',?,?)
                    """, prefix, patientId, sessionId, prefix, TODAY.plusDays(1).atStartOfDay(), prefix);
            Long runId = jdbc.queryForObject("SELECT id FROM agent_run WHERE patient_id=? AND client_request_id=?", Long.class, patientId, prefix);
            jdbc.update("""
                    INSERT INTO triage_result(run_id,patient_id,session_id,summary_version,department_id,alternative_department_ids,
                      risk_level,validation_status,reason,evidence_refs,doctor_options_snapshot,missing_information,expires_at)
                    VALUES (?,?,?,1,?,'[]','ROUTINE','VALID','仅工程测试','[]','[]','[]',?)
                    """, runId, patientId, sessionId, departmentId, TODAY.plusDays(1).atTime(9, 0));
            Long id = jdbc.queryForObject("SELECT id FROM triage_result WHERE run_id=?", Long.class, runId);
            jdbc.update("UPDATE consultation_session SET latest_result_id=? WHERE id=?", id, sessionId);
            return id;
        });
        Long otherPatient = createPatient("_triage_other", true);
        var request = new RegistrationRequest(Long.valueOf(primarySchedule.id()), "triage", resultId, "测试症状");
        code("RESOURCE_NOT_FOUND", () -> booking.book(otherPatient, request));
        jdbc.update("UPDATE triage_result SET risk_level='EMERGENCY' WHERE id=?", resultId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE triage_result SET risk_level='ROUTINE',expires_at=? WHERE id=?", TODAY.atTime(9, 0), resultId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE triage_result SET expires_at=? WHERE id=?", TODAY.plusDays(1).atTime(9, 0), resultId);
        jdbc.update("UPDATE consultation_session SET summary_version=2 WHERE patient_id=?", patientId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE consultation_session SET summary_version=1,summary_confirmed_at=NULL WHERE patient_id=?", patientId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE consultation_session SET summary_confirmed_at=?,latest_result_id=NULL WHERE patient_id=?", TODAY.atTime(8, 0), patientId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE consultation_session SET latest_result_id=?,deleted_at=? WHERE patient_id=?", resultId, TODAY.atTime(8, 0), patientId);
        code("TRIAGE_RESULT_UNAVAILABLE", () -> booking.book(patientUserId, request));
        jdbc.update("UPDATE consultation_session SET deleted_at=NULL WHERE patient_id=?", patientId);
        assertEquals(3, schedules.selectById(Long.valueOf(primarySchedule.id())).getRemainingCount());
        Long alternateDoctorId = tx.execute(s -> {
            var dep = data.createDepartment(new DepartmentRequest(prefix + "_alt", "备选科室", null, null, false, "ENABLED"));
            createdDepartments.add(Long.valueOf(dep.id()));
            return Long.valueOf(createDoctor("_alt_doc", Long.valueOf(dep.id())).id());
        });
        var alternative = schedule(alternateDoctorId, TODAY.plusDays(1), "AM", 2);
        var alternateRequest = new RegistrationRequest(Long.valueOf(alternative.id()), "alternate", resultId, "测试症状");
        code("TRIAGE_DEPARTMENT_MISMATCH", () -> booking.book(patientUserId, alternateRequest));
        jdbc.update("UPDATE triage_result SET alternative_department_ids=? WHERE id=?", "[\"" + alternative.departmentId() + "\"]", resultId);
        assertEquals(resultId.toString(), booking.book(patientUserId, alternateRequest).registration().triageResultId());
        var booked = booking.book(patientUserId, request);
        assertEquals(resultId.toString(), booked.registration().triageResultId());
        jdbc.update("UPDATE consultation_session SET summary_version=2 WHERE patient_id=?", patientId);
        assertEquals(booked.registration().id(), booking.book(patientUserId, request).registration().id());
        assertEquals(2, schedules.selectById(Long.valueOf(primarySchedule.id())).getRemainingCount());
    }

    List<Object> concurrent(List<Supplier<Object>> tasks) throws Exception {
        var barrier = new CyclicBarrier(tasks.size());
        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            var futures = new ArrayList<Future<Object>>();
            for (var task : tasks)
                futures.add(executor.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    try {
                        return task.get();
                    } catch (BusinessException ex) {
                        return ex;
                    }
                }));
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get(40, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}

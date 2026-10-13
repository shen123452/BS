package com.shen.hospital.auth;

import com.fasterxml.jackson.databind.*;
import com.shen.hospital.auth.domain.*;
import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.auth.service.AuthService;
import com.shen.hospital.masterdata.mapper.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"hospital.jwt.secret=test-only-signing-key-at-least-32-bytes-long", "hospital.bootstrap.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@Transactional(transactionManager = "mysqlTransactionManager")
class AuthIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AuthService auth;
    @Autowired
    UserMapper users;
    @Autowired
    PatientProfileMapper patients;
    @Autowired
    DoctorMapper doctors;
    @Autowired
    JwtService jwt;
    String prefix, adminToken;
    SysUser admin;

    @BeforeEach
    void init() {
        prefix = "test_" + UUID.randomUUID().toString().substring(0, 8);
        admin = auth.createUser(prefix + "_admin", "TestPassword123!", "测试管理员", Role.ADMIN);
        adminToken = jwt.issue(admin.getId());
    }

    JsonNode register(String name) throws Exception {
        return response(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "TestPassword123!", "displayName", "测试患者"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("PATIENT"))
                .andExpect(jsonPath("$.data.id").isString()).andExpect(jsonPath("$.data.patientId").isString())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn());
    }

    String login(String name) throws Exception {
        return response(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "TestPassword123!"))))
                .andExpect(status().isOk()).andReturn()).at("/data/token").asText();
    }

    JsonNode response(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void patientRegistrationLoginOwnershipAndRoleChecks() throws Exception {
        var registered = register(prefix + "_patient");
        assertNotNull(patients.findIdByUserId(Long.parseLong(registered.at("/data/id").asText())));
        String token = login(prefix + "_patient");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("PATIENT"));
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/doctor/profile").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/patient/profile").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"患者甲\",\"gender\":\"UNKNOWN\",\"age\":25,\"chronicStatus\":\"NONE\",\"allergyStatus\":\"NONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("患者甲"));
        String other = prefix + "_other";
        register(other);
        mvc.perform(get("/api/patient/profile").header("Authorization", "Bearer " + login(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").isEmpty());
    }

    @Test
    void registrationCannotAssignRoleOrOtherIdentity() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix + "\",\"password\":\"TestPassword123!\",\"displayName\":\"测试\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        assertNull(auth.findByUsername(prefix));
        register(prefix);
        String token = login(prefix);
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"name\":\"测试\",\"gender\":\"UNKNOWN\",\"chronicStatus\":\"NONE\",\"allergyStatus\":\"NONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usernameNormalizationAndIncorrectCredentials() throws Exception {
        register(prefix);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix.toUpperCase(Locale.ROOT) + "\",\"password\":\"TestPassword123!\",\"displayName\":\"测试\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertFalse(login(prefix.toUpperCase(Locale.ROOT)).isBlank());
    }

    @Test
    void disabledAccountInvalidatesExistingToken() throws Exception {
        var registered = register(prefix);
        String token = login(prefix);
        mvc.perform(put("/api/admin/users/" + registered.at("/data/id").asText() + "/status")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix + "\",\"password\":\"TestPassword123!\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void anonymousAndInvalidTokenHaveJsonErrors() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void profileValidationAndClearingHistory() throws Exception {
        register(prefix);
        String token = login(prefix);
        String body = "{\"name\":\"测试\",\"gender\":\"UNKNOWN\",\"age\":121,\"chronicStatus\":\"KNOWN\",\"allergyStatus\":\"NONE\"}";
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body.replace("121", "25")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HISTORY_REQUIRED"));
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试\",\"gender\":\"UNKNOWN\",\"chronicStatus\":\"KNOWN\",\"chronicHistory\":\"测试慢病\",\"allergyStatus\":\"NONE\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/patient/profile").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试\",\"gender\":\"UNKNOWN\",\"chronicStatus\":\"NONE\",\"allergyStatus\":\"NONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.chronicHistory").isEmpty()).andExpect(jsonPath("$.data.age").isEmpty());
    }

    @Test
    void adminCreatesDoctorAndCannotDisableSelf() throws Exception {
        var department = response(mvc.perform(post("/api/admin/departments").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + prefix + "\",\"name\":\"测试科室\",\"isEmergency\":false,\"status\":\"ENABLED\"}"))
                .andExpect(status().isCreated()).andReturn());
        String departmentId = department.at("/data/id").asText();
        mvc.perform(post("/api/admin/doctors").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix + "_doc\",\"password\":\"TestPassword123!\",\"departmentId\":\"" + departmentId + "\",\"name\":\"测试医生\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").isString());
        String doctorToken = login(prefix + "_doc");
        mvc.perform(get("/api/doctor/profile").header("Authorization", "Bearer " + doctorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("测试医生"));
        mvc.perform(get("/api/patient/profile").header("Authorization", "Bearer " + doctorToken)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/departments/" + departmentId).header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + prefix + "\",\"name\":\"测试科室\",\"isEmergency\":false,\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/users/" + admin.getId() + "/status").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/users?page=1&pageSize=2").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/admin/users?pageSize=101").header("Authorization", "Bearer " + adminToken)).andExpect(status().isBadRequest());
    }

    @Test
    void doctorCreationRollsBackWhenUsernameConflicts() throws Exception {
        register(prefix);
        var department = response(mvc.perform(post("/api/admin/departments").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + prefix + "\",\"name\":\"测试科室\",\"isEmergency\":false,\"status\":\"ENABLED\"}"))
                .andExpect(status().isCreated()).andReturn());
        Long before = doctors.selectCount(null);
        mvc.perform(post("/api/admin/doctors").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + prefix + "\",\"password\":\"TestPassword123!\",\"departmentId\":\"" + department.at("/data/id").asText() + "\",\"name\":\"测试医生\"}"))
                .andExpect(status().isConflict());
        assertEquals(before, doctors.selectCount(null));
    }
}

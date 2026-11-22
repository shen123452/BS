package com.glucose;

import com.glucose.config.SecurityConfig;
import com.glucose.security.*;
import com.glucose.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest
@ContextConfiguration(classes = { SecurityConfig.class, JwtAuthFilter.class, SecurityBoundaryTest.ProbeController.class })
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockBean JwtService jwtService;
    @MockBean AuthStateService authState;
    @MockBean SysUserMapper users;

    @RestController
    static class ProbeController {
        @GetMapping("/api/test/protected") String protectedResource() { return "ok"; }
        @GetMapping("/api/common/captcha") String publicResource() { return "captcha"; }
        @PostMapping("/api/admin/auth/login") String login() { return "login"; }
        @PostMapping("/api/app/auth/wx-login") String wxLogin() { return "wx-login"; }
        @GetMapping("/api/test/patient") @PreAuthorize("hasRole('PATIENT')") String patientResource() { return "patient"; }
    }

    @Test void anonymousProtectedGetRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/test/protected")).andExpect(status().isUnauthorized());
    }
    @Test void anonymousProtectedPostRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/test/protected")).andExpect(status().isUnauthorized());
    }
    @Test void configuredPublicEndpointStaysPublic() throws Exception {
        mvc.perform(get("/api/common/captcha")).andExpect(status().isOk());
    }
    @Test void publicAuthenticationIgnoresStaleBearerToken() throws Exception {
        when(jwtService.parse("expired")).thenThrow(new IllegalArgumentException("expired"));
        mvc.perform(get("/api/common/captcha").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(content().string("captcha"));
        mvc.perform(get("/glucose/api/common/captcha").contextPath("/glucose").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(content().string("captcha"));
        mvc.perform(post("/api/admin/auth/login").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(content().string("login"));
        mvc.perform(post("/api/app/auth/wx-login").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(content().string("wx-login"));
        verifyNoInteractions(jwtService, authState, users);
    }
    @Test void staleBearerStillCannotAccessProtectedEndpoint() throws Exception {
        when(jwtService.parse("expired")).thenThrow(new IllegalArgumentException("expired"));
        mvc.perform(get("/api/test/protected").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40101));
    }
    @Test void optionsIsAllowedWithoutOpeningGetAndPost() throws Exception {
        mvc.perform(options("/api/test/protected")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles = "DOCTOR") void wrongRoleCannotAccessPatientEndpoint() throws Exception {
        mvc.perform(get("/api/test/patient")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles = "PATIENT") void correctRoleCanAccessPatientEndpoint() throws Exception {
        mvc.perform(get("/api/test/patient")).andExpect(status().isOk());
    }
}

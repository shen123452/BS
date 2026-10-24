package com.shen.hospital.consultation;

import com.shen.hospital.auth.mapper.UserMapper;
import com.shen.hospital.auth.security.CurrentUser;
import com.shen.hospital.auth.security.JwtService;
import com.shen.hospital.common.*;
import com.shen.hospital.config.SecurityConfig;
import com.shen.hospital.consultation.controller.PatientConsultationController;
import com.shen.hospital.consultation.service.*;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PatientConsultationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceFilter.class})
class ConsultationHttpErrorTest {
    @Autowired MockMvc mvc;
    @MockitoBean ConsultationService consultations;
    @MockitoBean ConsultationStreamService streams;
    @MockitoBean JwtService jwt;
    @MockitoBean UserMapper users;
    static final String URL = "/api/patient/consultations/20/messages";
    static final String BODY = "{\"clientMessageId\":\"test-stream-error\",\"content\":\"虚构工程测试\"}";

    UsernamePasswordAuthenticationToken principal(String role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser(10L, "test-only", role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test void missingModelConfigReturnsActual503WithStreamOnlyAccept() throws Exception {
        when(streams.open(eq(10L), eq(20L), any(), anyString()))
                .thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "请先配置百炼 DASHSCOPE_API_KEY"));
        mvc.perform(post(URL).with(authentication(principal("PATIENT"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test void busyReturns429AndOriginalErrorCode() throws Exception {
        when(streams.open(eq(10L), eq(20L), any(), anyString()))
                .thenThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "AI_BUSY", "问诊服务繁忙"));
        mvc.perform(post(URL).with(authentication(principal("PATIENT"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AI_BUSY"));
    }

    @Test void validationErrorRemains400ForStreamOnlyClient() throws Exception {
        mvc.perform(post(URL).with(authentication(principal("PATIENT"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(streams);
    }

    @Test void malformedBodyRemains400ForStreamOnlyClient() throws Exception {
        mvc.perform(post(URL).with(authentication(principal("PATIENT"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test void unauthenticatedRequestStillReturns401() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(streams);
    }

    @Test void wrongRoleStillReturns403() throws Exception {
        mvc.perform(post(URL).with(authentication(principal("DOCTOR"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(streams);
    }

    @Test void normalResponseRemainsEventStream() throws Exception {
        var emitter = new SseEmitter();
        emitter.send(SseEmitter.event().name("done").data(java.util.Map.of("content", "虚构测试回复")));
        emitter.complete();
        when(streams.open(eq(10L), eq(20L), any(), anyString())).thenReturn(emitter);
        var result = mvc.perform(post(URL).with(authentication(principal("PATIENT"))).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test void errorDispatchDoesNotReplaceAnErrorWithLoginFailure() throws Exception {
        mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; }))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(401, result.getResponse().getStatus()));
        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
    }
}

package com.glucose.qa.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.api.PageResult;
import com.glucose.common.api.Result;
import com.glucose.common.exception.BusinessException;
import com.glucose.patient.service.PatientAccessService;
import com.glucose.qa.entity.QaMessage;
import com.glucose.qa.entity.QaSession;
import com.glucose.qa.mapper.QaMessageMapper;
import com.glucose.qa.mapper.QaSessionMapper;
import com.glucose.qa.service.QaService;
import com.glucose.security.LoginContext;
import com.glucose.security.LoginUser;
import com.glucose.system.service.AuditService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答接口:患者端会话/提问(同步与 SSE 流式)/来源;管理端按授权查看。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;
    private final QaSessionMapper sessionMapper;
    private final QaMessageMapper messageMapper;
    private final PatientAccessService accessService;
    private final AuditService auditService;
    private final com.glucose.patient.mapper.PatientProfileMapper profileMapper;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "qa-sse-worker");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }

    public record ReplyReq(@NotBlank String requestId, @NotBlank @Size(max = 2000) String content) {}

    /* ================= 患者端 ================= */

    @GetMapping("/api/app/qa/availability")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<QaService.AvailabilityVO> availability() {
        return Result.ok(qaService.availability());
    }

    @PostMapping("/api/app/qa/sessions")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, String> body) {
        QaSession session = qaService.createSession(LoginContext.current(),
                body == null ? null : body.get("title"));
        return Result.ok(Map.of("sessionId", String.valueOf(session.getId()), "title", session.getTitle()));
    }

    @GetMapping("/api/app/qa/sessions")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<List<Map<String, Object>>> listSessions() {
        return Result.ok(qaService.listSessions(LoginContext.current()).stream()
                .map(s -> Map.<String, Object>of(
                        "sessionId", String.valueOf(s.getId()),
                        "title", s.getTitle(),
                        "updatedAt", s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString()))
                .toList());
    }

    @GetMapping("/api/app/qa/sessions/{id}/messages")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<List<Map<String, Object>>> messages(@PathVariable long id) {
        return Result.ok(qaService.messages(LoginContext.current(), id).stream()
                .map(this::toMessageVO).toList());
    }

    @PostMapping("/api/app/qa/ask")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<QaService.AskVO> ask(@RequestBody QaService.AskReq req) {
        return Result.ok(qaService.ask(LoginContext.current(), req));
    }

    @PostMapping(value = "/api/app/qa/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('PATIENT')")
    public SseEmitter askStream(@RequestBody QaService.AskReq req, HttpServletResponse response) {
        LoginUser user = LoginContext.current();
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(30_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        streamExecutor.submit(() -> {
            QaService.StreamSink sink = new QaService.StreamSink() {
                @Override
                public void onDelta(String text) {
                    sendSse(emitter, closed, "delta", Map.of("text", text));
                }

                @Override
                public void onReplace(String fullText) {
                    sendSse(emitter, closed, "replace", Map.of("text", fullText));
                }
            };
            try {
                QaService.AskVO result = qaService.ask(user, req, sink);
                sendSse(emitter, closed, "done", result);
                if (!closed.get()) emitter.complete();
            } catch (BusinessException e) {
                sendSse(emitter, closed, "error", Map.of("code", e.getErrorCode().getCode(), "message", e.getMessage()));
                if (!closed.get()) emitter.complete();
            } catch (Exception e) {
                log.warn("qa sse stream failed: {}", e.getMessage());
                sendSse(emitter, closed, "error", Map.of("code", ErrorCode.UPSTREAM_UNAVAILABLE.getCode(), "message", "回答生成失败,请重试"));
                if (!closed.get()) emitter.complete();
            }
        });
        return emitter;
    }

    private static void sendSse(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
        }
    }

    @GetMapping("/api/app/qa/messages/{id}/sources")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<List<QaService.SourceVO>> sources(@PathVariable long id) {
        return Result.ok(qaService.sourcesOf(LoginContext.current(), id));
    }

    @DeleteMapping("/api/app/qa/sessions/{id}")
    @PreAuthorize("hasRole('PATIENT')")
    public Result<Void> deleteSession(@PathVariable long id) {
        qaService.deleteSession(LoginContext.current(), id);
        return Result.ok();
    }

    /* ================= 管理端 ================= */

    @GetMapping("/api/admin/qa/sessions")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<PageResult<Map<String, Object>>> adminSessions(
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        LoginUser user = LoginContext.current();
        var query = Wrappers.<QaSession>lambdaQuery().orderByDesc(QaSession::getUpdatedAt);
        if (patientId != null) {
            accessService.requireReadable(user, patientId);
            query.eq(QaSession::getPatientId, patientId);
        } else if ("DOCTOR".equals(user.role())) {
            List<Long> boundIds = boundPatientIds(user);
            if (boundIds.isEmpty()) {
                return Result.ok(PageResult.of(0, pageNum, List.of()));
            }
            query.in(QaSession::getPatientId, boundIds);
        }
        Page<QaSession> page = sessionMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), query);
        if ("ADMIN".equals(user.role())) {
            auditService.record(user.userId(), "admin.view.qa", "qa_session", "", "管理员查看问答会话", null);
        }
        return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(),
                page.getRecords().stream().map(s -> Map.<String, Object>of(
                        "sessionId", String.valueOf(s.getId()),
                        "patientId", String.valueOf(s.getPatientId()),
                        "title", s.getTitle(),
                        "createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().toString(),
                        "updatedAt", s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString())).toList()));
    }

    @GetMapping("/api/admin/qa/sessions/{id}/messages")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<List<Map<String, Object>>> adminMessages(@PathVariable long id) {
        QaSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new com.glucose.common.exception.BusinessException(com.glucose.common.api.ErrorCode.NOT_FOUND);
        }
        accessService.requireReadable(LoginContext.current(), session.getPatientId());
        return Result.ok(messageMapper.selectList(Wrappers.<QaMessage>lambdaQuery()
                        .eq(QaMessage::getSessionId, id).orderByAsc(QaMessage::getId)).stream()
                .map(this::toMessageVO).toList());
    }

    @PostMapping("/api/admin/qa/sessions/{id}/reply")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Result<Map<String, Object>> reply(@PathVariable long id, @Valid @RequestBody ReplyReq req) {
        LoginUser user = LoginContext.current();
        QaSession session = sessionMapper.selectById(id);
        if (session == null) throw new com.glucose.common.exception.BusinessException(com.glucose.common.api.ErrorCode.NOT_FOUND);
        accessService.requireReadable(user, session.getPatientId());
        QaMessage message = qaService.reply(user, id, req.requestId(), req.content());
        if ("ADMIN".equals(user.role())) auditService.record(user.userId(), "admin.qa.reply", "qa_session",
                String.valueOf(id), "管理员发送人工回复", null);
        return Result.ok(toMessageVO(message));
    }

    private List<Long> boundPatientIds(LoginUser doctor) {
        return profileMapper.selectList(Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaQuery()
                        .eq(com.glucose.patient.entity.PatientProfile::getDoctorId, doctor.userId()))
                .stream().map(com.glucose.patient.entity.PatientProfile::getUserId).toList();
    }

    private Map<String, Object> toMessageVO(QaMessage m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("messageId", String.valueOf(m.getId()));
        out.put("sessionId", String.valueOf(m.getSessionId()));
        out.put("role", m.getRole());
        out.put("content", m.getContent());
        out.put("status", m.getStatus());
        out.put("answerMode", m.getAnswerMode() == null ? "" : m.getAnswerMode());
        out.put("degraded", m.getDegraded() != null && m.getDegraded() == 1);
        out.put("hasSources", qaService.hasSources(m));
        out.put("latencyMs", m.getLatencyMs() == null ? 0 : m.getLatencyMs());
        out.put("failureReason", m.getFailureReason() == null ? "" : m.getFailureReason());
        out.put("createdAt", m.getCreatedAt() == null ? ""
                : m.getCreatedAt().format(com.glucose.config.JacksonConfig.DATETIME));
        return out;
    }
}

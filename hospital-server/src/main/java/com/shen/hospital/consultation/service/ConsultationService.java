package com.shen.hospital.consultation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shen.hospital.ai.ModelFactory;
import com.shen.hospital.common.BusinessException;
import com.shen.hospital.common.BusinessTime;
import com.shen.hospital.common.PageResult;
import com.shen.hospital.consultation.domain.ConsultationMessage;
import com.shen.hospital.consultation.domain.ConsultationSession;
import com.shen.hospital.consultation.dto.MessageView;
import com.shen.hospital.consultation.dto.SessionView;
import com.shen.hospital.consultation.dto.SendMessageRequest;
import com.shen.hospital.consultation.dto.ConfirmedSummary;
import com.shen.hospital.consultation.mapper.ConsultationMessageMapper;
import com.shen.hospital.consultation.mapper.ConsultationSessionMapper;
import com.shen.hospital.masterdata.mapper.PatientProfileMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationService {
    private static final String SYSTEM_PROMPT = "你是医院的线上就诊信息助手，负责了解患者描述并提供一般性就医信息。"
            + "你不是医生，不作疾病诊断、不代替医生、不提供处方或具体用药剂量；信息不足时先提出简短问题。"
            + "如用户描述胸痛、严重呼吸困难、意识不清、大出血、突发偏瘫等紧急危险情况，立即建议拨打120或就近急诊，不要继续普通问诊。"
            + "回答用简体中文、表达清楚，不夸大确定性，并提醒用户预约和诊疗以医院及医生安排为准。";
    private final ConsultationSessionMapper sessions;
    private final ConsultationMessageMapper messages;
    private final PatientProfileMapper patients;
    private final ModelFactory models;
    private final BusinessTime time;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public ConsultationService(ConsultationSessionMapper sessions, ConsultationMessageMapper messages,
                               PatientProfileMapper patients, ModelFactory models, BusinessTime time) {
        this.sessions = sessions;
        this.messages = messages;
        this.patients = patients;
        this.models = models;
        this.time = time;
    }

    public record Prepared(String operationId, String sessionId, String assistantMessageId,
                           String state, String replayText, ChatRequest request) {
        public boolean replay() {
            return request == null;
        }
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public SessionView create(Long userId, String requestedTitle) {
        Long patientId = patientId(userId);
        String title = requestedTitle == null || requestedTitle.isBlank() ? "新问诊" : requestedTitle.strip();
        var session = new ConsultationSession();
        session.setPatientId(patientId);
        session.setTitle(title);
        session.setSummaryVersion(0);
        sessions.insert(session);
        return SessionView.of(session);
    }

    public PageResult<SessionView> list(Long userId, long page, long pageSize) {
        Long patientId = patientId(userId);
        var result = sessions.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ConsultationSession>().eq(ConsultationSession::getPatientId, patientId)
                        .isNull(ConsultationSession::getDeletedAt)
                        .orderByDesc(ConsultationSession::getUpdatedAt, ConsultationSession::getId));
        return PageResult.from(result, SessionView::of);
    }

    public SessionView get(Long userId, Long sessionId) {
        var session = owned(userId, sessionId);
        return SessionView.of(session);
    }

    public List<MessageView> messages(Long userId, Long sessionId) {
        owned(userId, sessionId);
        return messages.listBySession(sessionId).stream().map(MessageView::of).toList();
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public SessionView updateSummary(Long userId, Long sessionId, int expectedVersion, ConfirmedSummary summary, boolean confirm) {
        Long patientId = patientId(userId);
        var session = sessions.lockById(sessionId);
        if (session == null || !patientId.equals(session.getPatientId())) throw BusinessException.missing("问诊会话");
        if (summary == null)
            throw BusinessException.invalid("请先填写需要导诊的症状摘要");
        summary.validateRisks();
        if (session.getActiveOperationId() != null) throw BusinessException.conflict("SESSION_BUSY", "当前会话有任务正在处理，请稍候");
        String value = json.valueToTree(summary).toString();
        LocalDateTime confirmedAt = confirm ? time.now() : null;
        if (sessions.updateSummary(sessionId, patientId, expectedVersion, value, confirmedAt) != 1)
            throw BusinessException.conflict("SUMMARY_VERSION_CONFLICT", "摘要已变化，请重新载入后确认");
        return get(userId, sessionId);
    }

    @Transactional(transactionManager = "mysqlTransactionManager", isolation = Isolation.READ_COMMITTED)
    public Prepared begin(Long userId, Long sessionId, SendMessageRequest request) {
        Long patientId = patientId(userId);
        var session = sessions.lockById(sessionId);
        if (session == null || !patientId.equals(session.getPatientId())) throw BusinessException.missing("问诊会话");
        LocalDateTime now = time.now();
        String content = request.content().strip();
        var priorUser = messages.findUserByClient(sessionId, request.clientMessageId());
        if (priorUser != null) {
            if (!priorUser.getContent().equals(content))
                throw BusinessException.conflict("MESSAGE_ID_CONFLICT", "该消息编号已用于其他内容，请刷新会话");
            var priorAssistant = messages.findAssistantByRequest(sessionId, priorUser.getRequestId());
            if (priorAssistant == null) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MESSAGE_STATE_UNAVAILABLE", "消息状态暂不可用，请刷新会话");
            if ("STREAMING".equals(priorAssistant.getStatus())) {
                if (priorUser.getRequestId().equals(session.getActiveOperationId())
                        && session.getOperationDeadline() != null && session.getOperationDeadline().isAfter(now))
                    throw BusinessException.conflict("MESSAGE_IN_PROGRESS", "这条消息仍在处理中，请稍候刷新会话");
                messages.finish(priorAssistant.getId(), "FAILED", "AI_TIMEOUT");
                sessions.finishOperation(sessionId, priorUser.getRequestId());
                return new Prepared(priorUser.getRequestId(), sessionId.toString(), priorAssistant.getId().toString(),
                        "FAILED", priorAssistant.getContent(), null);
            } else {
                return new Prepared(priorUser.getRequestId(), sessionId.toString(), priorAssistant.getId().toString(),
                        priorAssistant.getStatus(), priorAssistant.getContent(), null);
            }
        }
        models.requireConfigured();
        if (session.getActiveOperationId() != null) {
            if (session.getOperationDeadline() != null && session.getOperationDeadline().isAfter(now))
                throw BusinessException.conflict("SESSION_BUSY", "当前会话有一条消息正在处理，请稍候");
            var stale = messages.findAssistantByRequest(sessionId, session.getActiveOperationId());
            if (stale != null) messages.finish(stale.getId(), "FAILED", "AI_TIMEOUT");
            sessions.finishOperation(sessionId, session.getActiveOperationId());
        }

        String operationId = UUID.randomUUID().toString();
        int deadlineSeconds = Math.min(models.config().timeoutSeconds() + 20, 150);
        sessions.startOperation(sessionId, operationId, "CHAT", now.plusSeconds(deadlineSeconds));

        var userMessage = new ConsultationMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole("USER");
        userMessage.setClientMessageId(request.clientMessageId());
        userMessage.setRequestId(operationId);
        userMessage.setContent(content);
        userMessage.setStatus("COMPLETED");
        messages.insert(userMessage);

        var assistantMessage = new ConsultationMessage();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setClientMessageId(request.clientMessageId());
        assistantMessage.setRequestId(operationId);
        assistantMessage.setContent("");
        assistantMessage.setStatus("STREAMING");
        messages.insert(assistantMessage);
        sessions.touch(sessionId);

        if ("新问诊".equals(session.getTitle())) {
            int end = content.offsetByCodePoints(0, Math.min(32, content.codePointCount(0, content.length())));
            session.setTitle(content.substring(0, end));
            sessions.rename(sessionId, session.getTitle());
        }
        var history = messages.recentCompletedBefore(sessionId, userMessage.getId(), 20);
        Collections.reverse(history);
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(SystemMessage.from(SYSTEM_PROMPT));
        for (var item : history) {
            if ("USER".equals(item.getRole())) chatMessages.add(UserMessage.from(item.getContent()));
            else if ("ASSISTANT".equals(item.getRole())) chatMessages.add(AiMessage.from(item.getContent()));
        }
        chatMessages.add(UserMessage.from(content));
        var chatRequest = ChatRequest.builder().messages(chatMessages).maxOutputTokens(1024).build();
        return new Prepared(operationId, sessionId.toString(), assistantMessage.getId().toString(), "STREAMING", null, chatRequest);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public boolean append(String operationId, String assistantMessageId, String delta) {
        if (delta == null || delta.isEmpty()) return true;
        return messages.append(Long.valueOf(assistantMessageId), operationId, delta) == 1;
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public String finishSuccess(Prepared prepared, String content) {
        Long sessionId = Long.valueOf(prepared.sessionId());
        var session = sessions.lockById(sessionId);
        if (session == null || !prepared.operationId().equals(session.getActiveOperationId())) return null;
        var message = messages.findAssistantByRequest(sessionId, prepared.operationId());
        if (content == null || content.isBlank()) throw new IllegalStateException("模型未返回有效正文");
        if (message == null || messages.complete(message.getId(), content) != 1) return null;
        sessions.finishOperation(sessionId, prepared.operationId());
        return content;
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public void finishFailure(Prepared prepared, boolean timeout) {
        Long sessionId = Long.valueOf(prepared.sessionId());
        var session = sessions.lockById(sessionId);
        if (session == null || !prepared.operationId().equals(session.getActiveOperationId())) return;
        var message = messages.findAssistantByRequest(sessionId, prepared.operationId());
        if (message != null) messages.finish(message.getId(), "FAILED", timeout ? "AI_TIMEOUT" : "AI_CALL_FAILED");
        sessions.finishOperation(sessionId, prepared.operationId());
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public boolean cancel(Long userId, Long sessionId, String operationId) {
        Long patientId = patientId(userId);
        var session = sessions.lockById(sessionId);
        if (session == null || !patientId.equals(session.getPatientId())) throw BusinessException.missing("问诊会话");
        if (operationId == null || !operationId.equals(session.getActiveOperationId())) return false;
        var message = messages.findAssistantByRequest(sessionId, operationId);
        boolean changed = message != null && messages.finish(message.getId(), "CANCELLED", "CLIENT_CANCELLED") == 1;
        sessions.finishOperation(sessionId, operationId);
        return changed;
    }

    private Long patientId(Long userId) {
        Long id = patients.findIdByUserId(userId);
        if (id == null) throw BusinessException.missing("患者档案");
        return id;
    }

    private ConsultationSession owned(Long userId, Long sessionId) {
        Long patientId = patientId(userId);
        var session = sessions.findOwned(sessionId, patientId);
        if (session == null) throw BusinessException.missing("问诊会话");
        return session;
    }
}

package com.glucose.qa.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.glucose.common.api.ErrorCode;
import com.glucose.common.exception.BusinessException;
import com.glucose.qa.entity.*;
import com.glucose.qa.mapper.*;
import com.glucose.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
import java.util.Objects;

/** Short transaction only; no HTTP/model calls inside the reservation. */
@Service
@RequiredArgsConstructor
public class QaRequestStore {
    private final QaSessionMapper sessionMapper;
    private final QaMessageMapper messageMapper;
    private final com.glucose.patient.mapper.PatientProfileMapper profileMapper;
    public record Reservation(QaSession session, QaMessage assistant, boolean generate) { }

    @Transactional
    public QaSession create(LoginUser user, String title) {
        profileMapper.requireLock(user.userId());
        var session = new QaSession(); session.setPatientId(user.userId()); session.setTitle(title == null || title.isBlank() ? "新的对话" : title);
        sessionMapper.insert(session);
        return session;
    }

    @Transactional
    public void delete(LoginUser user, long id) {
        profileMapper.requireLock(user.userId());
        var session = sessionMapper.lockById(id);
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!Objects.equals(session.getPatientId(), user.userId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        messageMapper.delete(Wrappers.<QaMessage>lambdaQuery().eq(QaMessage::getSessionId, id));
        sessionMapper.deleteById(id);
    }

    public QaMessage lookup(LoginUser user, QaService.AskReq req) {
        QaSession session = sessionMapper.selectById(req.sessionId());
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!Objects.equals(session.getPatientId(), user.userId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        var profile = profileMapper.selectOne(Wrappers.<com.glucose.patient.entity.PatientProfile>lambdaQuery()
                .eq(com.glucose.patient.entity.PatientProfile::getUserId, user.userId()));
        if (profile == null || !"privacy-v1".equals(profile.getPrivacyVersion())) throw new BusinessException(ErrorCode.FORBIDDEN, "请先在「我的」阅读并同意隐私说明");
        QaMessage question = messageMapper.selectOne(Wrappers.<QaMessage>lambdaQuery()
                .eq(QaMessage::getSessionId, req.sessionId()).eq(QaMessage::getRequestId, req.requestId()).eq(QaMessage::getRole, "user"));
        if (question != null && !Objects.equals(question.getContent(), req.question())) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "同一请求编号不能用于不同问题");
        return messageMapper.selectOne(Wrappers.<QaMessage>lambdaQuery()
                .eq(QaMessage::getSessionId, req.sessionId()).eq(QaMessage::getRequestId, req.requestId()).eq(QaMessage::getRole, "assistant"));
    }

    @Transactional
    public Reservation reserve(LoginUser user, QaService.AskReq req) {
        var profile = profileMapper.requireLock(user.userId());
        if (profile == null || !"privacy-v1".equals(profile.getPrivacyVersion())) throw new BusinessException(ErrorCode.FORBIDDEN, "请先同意隐私说明");
        QaSession session = sessionMapper.lockById(req.sessionId());
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        if (!Objects.equals(session.getPatientId(), user.userId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        QaMessage assistant = messageMapper.selectOne(Wrappers.<QaMessage>lambdaQuery()
                .eq(QaMessage::getSessionId, session.getId()).eq(QaMessage::getRequestId, req.requestId()).eq(QaMessage::getRole, "assistant"));
        QaMessage question = messageMapper.selectOne(Wrappers.<QaMessage>lambdaQuery()
                .eq(QaMessage::getSessionId, session.getId()).eq(QaMessage::getRequestId, req.requestId()).eq(QaMessage::getRole, "user"));
        if (question != null && !Objects.equals(question.getContent(), req.question())) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "同一请求编号不能用于不同问题");
        if (assistant != null) {
            if (!"FAILED".equals(assistant.getStatus())) return new Reservation(session, assistant, false);
            messageMapper.update(null, Wrappers.<QaMessage>lambdaUpdate().eq(QaMessage::getId, assistant.getId())
                    .eq(QaMessage::getStatus, "FAILED").set(QaMessage::getStatus, "PROCESSING")
                    .set(QaMessage::getFailureReason, null).set(QaMessage::getUpdatedAt, LocalDateTime.now()));
            assistant.setStatus("PROCESSING");
            return new Reservation(session, assistant, true);
        }
        // Repairs legacy partial writes as well as inserting both new messages atomically.
        if (question == null) {
            question = new QaMessage(); question.setSessionId(session.getId()); question.setRequestId(req.requestId());
            question.setRole("user"); question.setContent(req.question()); question.setStatus("COMPLETED"); messageMapper.insert(question);
        }
        assistant = new QaMessage(); assistant.setSessionId(session.getId()); assistant.setRequestId(req.requestId());
        assistant.setRole("assistant"); assistant.setContent(""); assistant.setStatus("PROCESSING"); messageMapper.insert(assistant);
        return new Reservation(session, assistant, true);
    }

    @Scheduled(fixedDelay = 60000)
    public void recoverInterruptedRequests() {
        messageMapper.update(null, Wrappers.<QaMessage>lambdaUpdate().eq(QaMessage::getRole, "assistant")
                .eq(QaMessage::getStatus, "PROCESSING").lt(QaMessage::getUpdatedAt, LocalDateTime.now().minusMinutes(2))
                .set(QaMessage::getStatus, "FAILED").set(QaMessage::getFailureReason, "INTERRUPTED"));
    }
}

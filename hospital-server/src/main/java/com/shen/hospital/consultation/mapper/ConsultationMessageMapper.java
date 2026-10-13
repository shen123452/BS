package com.shen.hospital.consultation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.consultation.domain.ConsultationMessage;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ConsultationMessageMapper extends BaseMapper<ConsultationMessage> {
    @Select("SELECT * FROM (SELECT * FROM consultation_message WHERE session_id=#{sessionId} ORDER BY id DESC LIMIT 200) recent ORDER BY id ASC")
    List<ConsultationMessage> listBySession(Long sessionId);

    @Select("SELECT * FROM consultation_message WHERE session_id=#{sessionId} AND role='USER' AND client_message_id=#{clientMessageId} LIMIT 1")
    ConsultationMessage findUserByClient(@Param("sessionId") Long sessionId, @Param("clientMessageId") String clientMessageId);

    @Select("SELECT * FROM consultation_message WHERE session_id=#{sessionId} AND role='ASSISTANT' AND request_id=#{requestId} LIMIT 1")
    ConsultationMessage findAssistantByRequest(@Param("sessionId") Long sessionId, @Param("requestId") String requestId);

    @Select("SELECT m.* FROM consultation_message m WHERE m.session_id=#{sessionId} AND m.id < #{beforeId} AND m.status='COMPLETED' AND (m.role='ASSISTANT' OR EXISTS (SELECT 1 FROM consultation_message a WHERE a.session_id=m.session_id AND a.role='ASSISTANT' AND a.request_id=m.request_id AND a.status='COMPLETED')) ORDER BY m.id DESC LIMIT #{limit}")
    List<ConsultationMessage> recentCompletedBefore(@Param("sessionId") Long sessionId,
                                                    @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Update("UPDATE consultation_message SET content=CONCAT(content,#{delta}), updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND request_id=#{operationId} AND status='STREAMING'")
    int append(@Param("id") Long id, @Param("operationId") String operationId, @Param("delta") String delta);

    @Update("UPDATE consultation_message SET status=#{status}, error_code=#{errorCode}, updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='STREAMING'")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("errorCode") String errorCode);

    @Update("UPDATE consultation_message SET content=#{content},status='COMPLETED',error_code=NULL WHERE id=#{id} AND status='STREAMING'")
    int complete(@Param("id") Long id, @Param("content") String content);
}

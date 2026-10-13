package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface AgentRunMapper extends BaseMapper<AgentRun> {
    @Select("SELECT * FROM agent_run WHERE patient_id=#{patientId} AND client_request_id=#{clientRequestId} FOR UPDATE")
    AgentRun byClient(@Param("patientId") Long patientId, @Param("clientRequestId") String clientRequestId);
    @Select("SELECT * FROM agent_run WHERE id=#{id} AND patient_id=#{patientId}")
    AgentRun owned(@Param("id") Long id, @Param("patientId") Long patientId);
    @Select("SELECT * FROM agent_run WHERE id=#{id} FOR UPDATE")
    AgentRun lockById(Long id);
    @Select("SELECT * FROM agent_run WHERE status IN ('QUEUED','RUNNING') AND deadline_at<=#{now}")
    List<AgentRun> expired(LocalDateTime now);
    @Select("SELECT * FROM agent_run WHERE status IN ('QUEUED','RUNNING')")
    List<AgentRun> interrupted();
    @Update("UPDATE agent_run SET actual_rounds=#{rounds},actual_tool_calls=#{tools} WHERE id=#{id} AND status='RUNNING'")
    int progress(@Param("id") Long id, @Param("rounds") int rounds, @Param("tools") int tools);
    @Update("UPDATE agent_run SET status='RUNNING',started_at=#{now} WHERE id=#{id} AND status='QUEUED'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);
    @Update("UPDATE agent_run SET status=#{status},actual_rounds=#{rounds},actual_tool_calls=#{tools},finished_at=#{now},error_code=#{code},error_message=#{message} WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("rounds") int rounds, @Param("tools") int tools, @Param("now") LocalDateTime now, @Param("code") String code, @Param("message") String message);
    @Update("UPDATE agent_run SET status='CANCELLED',finished_at=#{now},error_code='CLIENT_CANCELLED',error_message='用户停止了本次导诊' WHERE id=#{id} AND patient_id=#{patientId} AND status IN ('QUEUED','RUNNING')")
    int cancel(@Param("id") Long id, @Param("patientId") Long patientId, @Param("now") LocalDateTime now);
}

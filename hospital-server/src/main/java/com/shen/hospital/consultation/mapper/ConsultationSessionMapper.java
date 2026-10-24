package com.shen.hospital.consultation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.consultation.domain.ConsultationSession;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

public interface ConsultationSessionMapper extends BaseMapper<ConsultationSession> {
    @Select("SELECT * FROM consultation_session WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    ConsultationSession lockById(Long id);

    @Select("SELECT * FROM consultation_session WHERE patient_id=#{patientId} AND deleted_at IS NULL ORDER BY updated_at DESC, id DESC LIMIT 100")
    List<ConsultationSession> listByPatient(Long patientId);

    @Select("SELECT * FROM consultation_session WHERE id=#{id} AND patient_id=#{patientId} AND deleted_at IS NULL")
    ConsultationSession findOwned(@Param("id") Long id, @Param("patientId") Long patientId);

    @Update("UPDATE consultation_session SET deleted_at=#{now},updated_at=#{now} WHERE id=#{id} AND patient_id=#{patientId} AND deleted_at IS NULL AND active_operation_id IS NULL")
    int softDelete(@Param("id") Long id, @Param("patientId") Long patientId, @Param("now") LocalDateTime now);

    @Update("UPDATE consultation_session SET updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND deleted_at IS NULL")
    int touch(Long id);

    @Update("UPDATE consultation_session SET active_operation_id=#{operationId}, active_operation_type=#{type}, operation_deadline=#{deadline}, summary_version=summary_version+1, summary_confirmed_at=NULL, summary_source_message_id=NULL, latest_result_id=NULL WHERE id=#{id}")
    int startOperation(@Param("id") Long id, @Param("operationId") String operationId,
                       @Param("type") String type, @Param("deadline") LocalDateTime deadline);

    @Update("UPDATE consultation_session SET active_operation_id=NULL, active_operation_type=NULL, operation_deadline=NULL WHERE id=#{id} AND active_operation_id=#{operationId}")
    int finishOperation(@Param("id") Long id, @Param("operationId") String operationId);

    @Update("UPDATE consultation_session SET title=#{title} WHERE id=#{id}")
    int rename(@Param("id") Long id, @Param("title") String title);

    @Update("UPDATE consultation_session SET summary_json=#{summary}, summary_version=summary_version+1, summary_confirmed_at=#{confirmedAt}, summary_source_message_id=NULL, latest_result_id=NULL, updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND patient_id=#{patientId} AND summary_version=#{expectedVersion} AND deleted_at IS NULL")
    int updateSummary(@Param("id") Long id, @Param("patientId") Long patientId, @Param("expectedVersion") int expectedVersion,
                      @Param("summary") String summary, @Param("confirmedAt") LocalDateTime confirmedAt);

    @Update("UPDATE consultation_session SET active_operation_id=#{operationId}, active_operation_type='TRIAGE', operation_deadline=#{deadline}, latest_result_id=NULL WHERE id=#{id} AND patient_id=#{patientId} AND summary_version=#{version} AND summary_confirmed_at IS NOT NULL AND active_operation_id IS NULL AND deleted_at IS NULL")
    int startTriage(@Param("id") Long id, @Param("patientId") Long patientId, @Param("version") int version,
                    @Param("operationId") String operationId, @Param("deadline") LocalDateTime deadline);

    @Update("UPDATE consultation_session SET active_operation_id=NULL,active_operation_type=NULL,operation_deadline=NULL,latest_result_id=#{resultId} WHERE id=#{id} AND active_operation_id=#{operationId}")
    int finishTriage(@Param("id") Long id, @Param("operationId") String operationId, @Param("resultId") Long resultId);

    @Update("UPDATE consultation_session SET active_operation_id=NULL,active_operation_type=NULL,operation_deadline=NULL WHERE id=#{id} AND active_operation_id=#{operationId}")
    int cancelTriage(@Param("id") Long id, @Param("operationId") String operationId);
}

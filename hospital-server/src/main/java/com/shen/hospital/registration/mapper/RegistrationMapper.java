package com.shen.hospital.registration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.registration.domain.Registration;
import com.shen.hospital.registration.dto.TriageBookingState;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.apache.ibatis.annotations.*;

public interface RegistrationMapper extends BaseMapper<Registration> {
    @Select("SELECT * FROM registration WHERE id=#{id} FOR UPDATE")
    Registration lockById(Long id);

    @Select("SELECT * FROM registration WHERE patient_id=#{patientId} AND client_request_id=#{key} FOR UPDATE")
    Registration byRequest(@Param("patientId") Long patientId, @Param("key") String key);

    @Select("""
            SELECT id FROM registration WHERE patient_id=#{patientId} AND visit_date=#{date}
              AND time_slot=#{slot} AND status IN ('WAITING','IN_PROGRESS') LIMIT 1 FOR UPDATE
            """)
    Long activeSlot(@Param("patientId") Long patientId, @Param("date") LocalDate date, @Param("slot") String slot);

    @Select("SELECT id FROM registration WHERE schedule_id=#{id} LIMIT 1 FOR UPDATE")
    Long anyHistory(Long id);

    @Select("SELECT id FROM registration WHERE schedule_id=#{id} AND status IN ('WAITING','IN_PROGRESS') LIMIT 1 FOR UPDATE")
    Long activeOnSchedule(Long id);

    @Select("""
            SELECT id FROM registration WHERE doctor_id=#{id} AND visit_date>=#{today}
              AND status IN ('WAITING','IN_PROGRESS') LIMIT 1 FOR UPDATE
            """)
    Long futureActiveForDoctor(@Param("id") Long id, @Param("today") LocalDate today);

    @Update("""
            UPDATE registration SET status='CANCELLED', cancelled_at=#{now}, cancel_reason=#{reason}
            WHERE id=#{id} AND patient_id=#{patientId} AND status='WAITING'
            """)
    int cancel(@Param("id") Long id, @Param("patientId") Long patientId,
               @Param("now") LocalDateTime now, @Param("reason") String reason);

    @Update("UPDATE registration SET status='IN_PROGRESS',started_at=#{now} WHERE id=#{id} AND doctor_id=#{doctorId} AND status='WAITING'")
    int start(@Param("id") Long id, @Param("doctorId") Long doctorId, @Param("now") LocalDateTime now);

    @Update("UPDATE registration SET status='COMPLETED',completed_at=#{now} WHERE id=#{id} AND doctor_id=#{doctorId} AND status='IN_PROGRESS'")
    int complete(@Param("id") Long id, @Param("doctorId") Long doctorId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT r.id,r.department_id,r.alternative_department_ids,r.risk_level,r.validation_status,
              r.summary_version,s.summary_version AS current_summary_version,s.latest_result_id,
              s.summary_confirmed_at,s.deleted_at,r.expires_at
            FROM triage_result r JOIN consultation_session s ON s.id=r.session_id
            WHERE r.id=#{id} AND r.patient_id=#{patientId} AND s.patient_id=#{patientId} FOR UPDATE
            """)
    TriageBookingState triageForBooking(@Param("id") Long id, @Param("patientId") Long patientId);
}

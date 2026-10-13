package com.shen.hospital.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shen.hospital.record.domain.MedicalRecord;
import com.shen.hospital.record.dto.RecordRequest;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.*;

public interface MedicalRecordMapper extends BaseMapper<MedicalRecord> {
    @Select("SELECT * FROM medical_record WHERE registration_id=#{registrationId}")
    MedicalRecord byRegistration(Long registrationId);

    @Select("SELECT * FROM medical_record WHERE registration_id=#{registrationId} FOR UPDATE")
    MedicalRecord lockByRegistration(Long registrationId);

    @Update("""
            UPDATE medical_record SET chief_complaint=#{r.chiefComplaint},present_illness=#{r.presentIllness},
              past_history=#{r.pastHistory},physical_exam=#{r.physicalExam},auxiliary_exam=#{r.auxiliaryExam},
              preliminary_diagnosis=#{r.preliminaryDiagnosis},treatment_plan=#{r.treatmentPlan},version=version+1
            WHERE registration_id=#{registrationId} AND doctor_id=#{doctorId} AND status='DRAFT' AND version=#{r.version}
            """)
    int save(@Param("registrationId") Long registrationId, @Param("doctorId") Long doctorId, @Param("r") RecordRequest request);

    @Update("""
            UPDATE medical_record SET status='SUBMITTED',submitted_at=#{now},version=version+1
            WHERE registration_id=#{registrationId} AND doctor_id=#{doctorId} AND status='DRAFT' AND version=#{version}
            """)
    int submit(@Param("registrationId") Long registrationId, @Param("doctorId") Long doctorId,
               @Param("version") int version, @Param("now") LocalDateTime now);
}

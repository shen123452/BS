package com.glucose.medication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.glucose.medication.entity.MedicationLog;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;

public interface MedicationLogMapper extends BaseMapper<MedicationLog> {
    // Immutable plan fields are copied once. Lazy materialization also reconstructs unrecorded past days.
    @Insert("""
        INSERT INTO medication_log(patient_id,plan_id,record_date,drug_name,dosage,scheduled_time,instruction,status,taken,version)
        SELECT p.patient_id,p.id,#{date},p.drug_name,p.dosage,p.scheduled_time,p.instruction,'PENDING',0,1
        FROM medication_plan p
        WHERE p.patient_id=#{patientId} AND p.start_date<=#{date}
          AND (p.end_date IS NULL OR p.end_date>=#{date})
          AND (p.stopped_date IS NULL OR p.stopped_date>=#{date})
          AND NOT EXISTS (SELECT 1 FROM medication_log l WHERE l.plan_id=p.id AND l.record_date=#{date})
        """)
    int materialize(@Param("patientId") long patientId, @Param("date") LocalDate date);

    @Update("""
        UPDATE medication_log SET status='CANCELLED',version=version+1
        WHERE patient_id=#{patientId} AND plan_id=#{planId} AND record_date=#{date}
          AND status='PENDING' AND deleted=0
        """)
    int cancelPending(@Param("patientId") long patientId, @Param("planId") long planId, @Param("date") LocalDate date);
}

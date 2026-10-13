package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

public interface TriageResultMapper extends BaseMapper<TriageResult> {
    @Select("SELECT * FROM triage_result WHERE id=#{id} AND patient_id=#{patientId}")
    TriageResult owned(@Param("id") long id, @Param("patientId") long patientId);
    @Select("SELECT * FROM triage_result WHERE run_id=#{runId}")
    TriageResult byRun(long runId);
}

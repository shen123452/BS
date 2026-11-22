package com.glucose.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.glucose.patient.entity.PatientProfile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

public interface PatientProfileMapper extends BaseMapper<PatientProfile> {

    /** All patient data writes acquire this row first; the lock lasts until transaction commit. */
    @Select("SELECT * FROM patient_profile WHERE user_id = #{userId} AND deleted = 0 FOR UPDATE")
    PatientProfile lockByUserId(@Param("userId") long userId);

    default PatientProfile requireLock(long userId) {
        PatientProfile profile = lockByUserId(userId);
        if (profile == null) throw new com.glucose.common.exception.BusinessException(com.glucose.common.api.ErrorCode.NOT_FOUND, "患者档案不存在");
        return profile;
    }

    @Update("UPDATE patient_profile SET data_revision = data_revision + 1 WHERE user_id = #{userId}")
    int bumpDataRevision(@Param("userId") long userId);
}

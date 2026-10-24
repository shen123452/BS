package com.shen.hospital.ai.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {
    @Select("SELECT * FROM ai_model_config WHERE model_type=#{type} AND status='ENABLED' LIMIT 1")
    AiModelConfig enabledByType(@Param("type") String type);

    @Select("SELECT * FROM ai_model_config WHERE code=#{code}")
    AiModelConfig byCode(@Param("code") String code);

    @Select("SELECT * FROM ai_model_config ORDER BY model_type,id")
    java.util.List<AiModelConfig> all();

    @Update("UPDATE ai_model_config SET status='DISABLED' WHERE model_type=#{type} AND status='ENABLED' AND id<>#{keepId}")
    int disableOthers(@Param("type") String type, @Param("keepId") Long keepId);
}

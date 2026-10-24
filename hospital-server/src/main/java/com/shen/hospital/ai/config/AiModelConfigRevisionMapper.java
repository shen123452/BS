package com.shen.hospital.ai.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiModelConfigRevisionMapper extends BaseMapper<AiModelConfigRevision> {
    @Select("SELECT * FROM ai_model_config_revision WHERE config_id=#{configId} ORDER BY revision DESC")
    List<AiModelConfigRevision> byConfig(@Param("configId") Long configId);

    @Select("SELECT * FROM ai_model_config_revision WHERE config_id=#{configId} AND revision=#{revision}")
    AiModelConfigRevision byNumber(@Param("configId") Long configId, @Param("revision") int revision);
}

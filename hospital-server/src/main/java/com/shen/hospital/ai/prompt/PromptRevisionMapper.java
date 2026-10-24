package com.shen.hospital.ai.prompt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PromptRevisionMapper extends BaseMapper<PromptRevision> {
    @Select("SELECT * FROM prompt_revision WHERE template_id=#{templateId} ORDER BY revision DESC")
    List<PromptRevision> byTemplate(@Param("templateId") Long templateId);

    @Select("SELECT * FROM prompt_revision WHERE template_id=#{templateId} AND revision=#{revision}")
    PromptRevision byNumber(@Param("templateId") Long templateId, @Param("revision") int revision);
}

package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface AgentStepMapper extends BaseMapper<AgentStep> {
    @Select("SELECT * FROM agent_step WHERE run_id=#{runId} ORDER BY sequence_no")
    List<AgentStep> listByRun(Long runId);
}

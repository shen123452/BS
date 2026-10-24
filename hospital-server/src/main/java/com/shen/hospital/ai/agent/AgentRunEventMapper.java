package com.shen.hospital.ai.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRunEventMapper extends BaseMapper<AgentRunEvent> {
    @Select("SELECT COALESCE(MAX(sequence_no),0) FROM agent_run_event WHERE run_id=#{runId}")
    int maxSequence(@Param("runId") Long runId);

    @Select("SELECT * FROM agent_run_event WHERE run_id=#{runId} AND visibility=#{visibility} "
            + "AND sequence_no>#{cursor} ORDER BY sequence_no ASC LIMIT #{limit}")
    List<AgentRunEvent> after(@Param("runId") Long runId, @Param("cursor") int cursor,
                              @Param("visibility") String visibility, @Param("limit") int limit);

    @Select("SELECT * FROM agent_run_event WHERE run_id=#{runId} AND visibility=#{visibility} ORDER BY sequence_no ASC")
    List<AgentRunEvent> all(@Param("runId") Long runId, @Param("visibility") String visibility);
}

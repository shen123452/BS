package com.shen.hospital.ai.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ToolCallLogMapper extends BaseMapper<ToolCallLog> {
    @Select("SELECT * FROM mcp_tool_call_log WHERE tool_code_snapshot=#{code} ORDER BY id DESC LIMIT #{limit}")
    List<ToolCallLog> recentByCode(@Param("code") String code, @Param("limit") int limit);

    @Select("<script>SELECT * FROM mcp_tool_call_log"
            + "<where><if test='code!=null'>AND tool_code_snapshot=#{code}</if>"
            + "<if test='status!=null'>AND status=#{status}</if>"
            + "<if test='runId!=null'>AND run_id=#{runId}</if></where>"
            + " ORDER BY id DESC</script>")
    List<ToolCallLog> filtered(@Param("code") String code, @Param("status") String status, @Param("runId") Long runId);

    @Update("UPDATE mcp_tool_call_log SET status=#{status},finished_at=#{finishedAt},duration_ms=#{durationMs},"
            + "output_summary=#{output},error_code=#{code} WHERE id=#{id} AND status='RUNNING'")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("finishedAt") java.time.LocalDateTime finishedAt,
               @Param("durationMs") long durationMs, @Param("output") String output, @Param("code") String errorCode);

    @Update("UPDATE mcp_tool_call_log SET status=#{status},finished_at=#{finishedAt},error_code=#{code} WHERE run_id=#{runId} AND status='RUNNING'")
    int closeRunningByRun(@Param("runId") Long runId, @Param("status") String status,
                          @Param("finishedAt") java.time.LocalDateTime finishedAt, @Param("code") String errorCode);
}

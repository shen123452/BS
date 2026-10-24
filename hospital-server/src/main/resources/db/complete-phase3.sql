-- 完整版阶段三：导诊运行持久事件表（事实时间线与 cursor 重订阅）。
-- 幂等增量迁移；不重建既有业务表。

CREATE TABLE IF NOT EXISTS agent_run_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  sequence_no INT NOT NULL COMMENT '运行级持久事件号，SSE/重订阅使用该序号作为游标',
  event_type VARCHAR(24) NOT NULL COMMENT 'run_started/step_started/step_finished/validation/result_ready/run_failed/run_cancelled',
  payload JSON NOT NULL,
  visibility VARCHAR(16) NOT NULL DEFAULT 'PATIENT' COMMENT 'PATIENT 仅公开摘要，ADMIN 含错误码等排障字段',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_event_seq (run_id, sequence_no),
  KEY idx_run_event_visibility (run_id, visibility, sequence_no)
) ENGINE=InnoDB COMMENT='导诊运行公开事件，可按 cursor 重放，不保存隐藏思维链';

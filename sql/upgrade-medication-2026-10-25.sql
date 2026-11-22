-- 仅用于已有 P0/V1.1 结构的本地测试库；空库直接使用 schema.sql。
-- 应先备份并确认目标库。本脚本不切换数据库、不删除或截断业务表。
-- 仅执行一次；遇错即停，不要使用 --force。DDL 不保证整体回滚。
CREATE TABLE medication_plan (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  drug_name VARCHAR(100) NOT NULL,
  dosage VARCHAR(50) NOT NULL,
  scheduled_time TIME NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NULL,
  instruction VARCHAR(200) NOT NULL DEFAULT '',
  active TINYINT NOT NULL DEFAULT 1,
  stopped_date DATE NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_request (patient_id, request_id),
  KEY idx_patient_active (patient_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

ALTER TABLE medication_log
  ADD COLUMN plan_id BIGINT UNSIGNED NULL,
  ADD COLUMN scheduled_time TIME NULL,
  ADD COLUMN instruction VARCHAR(200) NOT NULL DEFAULT '',
  ADD COLUMN status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
  ADD UNIQUE KEY uk_plan_date (plan_id, record_date);

UPDATE medication_log SET status=CASE WHEN taken=1 THEN 'TAKEN' ELSE 'PENDING' END;

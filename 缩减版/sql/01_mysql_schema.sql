-- 医院智能导诊系统 · 缩减版 V2.2 · MySQL 5.7.25+
-- 在新建的独立演示库中执行；16张表，不包含账号密码或业务种子。
-- 本文件是一次性初始化，不用IF NOT EXISTS掩盖旧表结构差异，不删除已有表。
-- 所有ID统一使用正有符号BIGINT，兼容Java Long和PostgreSQL BIGINT。
-- MySQL 5.7 会忽略 CHECK 声明；状态、范围和跨字段规则必须由后端 Service 校验。
CREATE DATABASE IF NOT EXISTS hospital_triage
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hospital_triage;
SET NAMES utf8mb4;
SET SESSION time_zone = '+08:00';

CREATE TABLE ai_provider_credential (
  provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  api_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端专用凭据，不对接口回传',
  updated_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (provider)
) ENGINE=InnoDB COMMENT='模型供应商凭据';

CREATE TABLE sys_user (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号ID',
  username VARCHAR(64) NOT NULL COMMENT '应用规范化后的登录名',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt哈希，禁止存明文',
  display_name VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'PATIENT',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  KEY idx_user_role_status (role, status, id),
  CONSTRAINT ck_user_role CHECK (role IN ('PATIENT','DOCTOR','ADMIN')),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB COMMENT='三角色账号';

CREATE TABLE department (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定科室编码',
  name VARCHAR(80) NOT NULL,
  description TEXT NULL,
  location VARCHAR(120) NULL,
  is_emergency TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_department_code (code),
  KEY idx_department_status (status, id),
  CONSTRAINT ck_department_emergency CHECK (is_emergency IN (0,1)),
  CONSTRAINT ck_department_status CHECK (status IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB COMMENT='科室';

CREATE TABLE doctor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  title VARCHAR(40) NULL,
  specialty TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_doctor_user (user_id),
  KEY idx_doctor_department (department_id, status, id),
  CONSTRAINT fk_doctor_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_doctor_department FOREIGN KEY (department_id) REFERENCES department(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_doctor_status CHECK (status IN ('ENABLED','DISABLED'))
) ENGINE=InnoDB COMMENT='医生及账号绑定';

CREATE TABLE patient_profile (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(64) NULL COMMENT '注册时可为空，预约前服务层要求完整',
  gender VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  age INT NULL,
  chronic_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  chronic_history TEXT NULL,
  allergy_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  allergy_history TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_user (user_id),
  CONSTRAINT fk_patient_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_patient_gender CHECK (gender IN ('MALE','FEMALE','UNKNOWN')),
  CONSTRAINT ck_patient_age CHECK (age IS NULL OR age BETWEEN 0 AND 120),
  CONSTRAINT ck_patient_chronic CHECK (chronic_status IN ('UNKNOWN','NONE','KNOWN')),
  CONSTRAINT ck_patient_allergy CHECK (allergy_status IN ('UNKNOWN','NONE','KNOWN')),
  CONSTRAINT ck_patient_chronic_text CHECK (chronic_status <> 'KNOWN' OR (chronic_history IS NOT NULL AND CHAR_LENGTH(TRIM(chronic_history)) > 0)),
  CONSTRAINT ck_patient_allergy_text CHECK (allergy_status <> 'KNOWN' OR (allergy_history IS NOT NULL AND CHAR_LENGTH(TRIM(allergy_history)) > 0))
) ENGINE=InnoDB COMMENT='患者档案，预约串行锁对象';

CREATE TABLE doctor_schedule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  doctor_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  visit_date DATE NOT NULL,
  time_slot VARCHAR(8) NOT NULL,
  booking_deadline DATETIME(3) NOT NULL,
  total_count INT UNSIGNED NOT NULL,
  remaining_count INT UNSIGNED NOT NULL,
  fee DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_doctor_date_slot (doctor_id, visit_date, time_slot),
  KEY idx_schedule_department_date (department_id, visit_date, status, id),
  CONSTRAINT fk_schedule_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_schedule_department FOREIGN KEY (department_id) REFERENCES department(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_schedule_slot CHECK (time_slot IN ('AM','PM')),
  CONSTRAINT ck_schedule_stock CHECK (total_count > 0 AND remaining_count <= total_count),
  CONSTRAINT ck_schedule_fee CHECK (fee >= 0),
  CONSTRAINT ck_schedule_status CHECK (status IN ('OPEN','CLOSED'))
) ENGINE=InnoDB COMMENT='医生排班及号源，条件更新扣减';

CREATE TABLE consultation_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL DEFAULT '新问诊',
  summary_json JSON NULL,
  summary_version INT NOT NULL DEFAULT 0,
  summary_confirmed_at DATETIME(3) NULL,
  summary_source_message_id BIGINT NULL,
  latest_result_id BIGINT NULL,
  active_operation_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  active_operation_type VARCHAR(16) NULL,
  operation_deadline DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_session_patient (patient_id, deleted_at, updated_at, id),
  KEY idx_session_operation_deadline (operation_deadline),
  CONSTRAINT fk_session_patient FOREIGN KEY (patient_id) REFERENCES patient_profile(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_session_version CHECK (summary_version >= 0),
  CONSTRAINT ck_session_confirmed CHECK (summary_confirmed_at IS NULL OR summary_json IS NOT NULL),
  CONSTRAINT ck_session_operation CHECK (
    (active_operation_id IS NULL AND active_operation_type IS NULL AND operation_deadline IS NULL)
    OR (active_operation_id IS NOT NULL AND active_operation_type IS NOT NULL
        AND active_operation_type IN ('CHAT','TRIAGE') AND operation_deadline IS NOT NULL)
  )
) ENGINE=InnoDB COMMENT='问诊会话、确认摘要及活动操作';

CREATE TABLE consultation_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  client_message_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_client (session_id, role, client_message_id),
  KEY idx_message_session (session_id, id),
  KEY idx_message_request (request_id),
  CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES consultation_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_message_role CHECK (role IN ('USER','ASSISTANT')),
  CONSTRAINT ck_message_status CHECK (status IN ('STREAMING','COMPLETED','FAILED','CANCELLED')),
  CONSTRAINT ck_message_user_status CHECK (role <> 'USER' OR status = 'COMPLETED')
) ENGINE=InnoDB COMMENT='问诊消息，增量保存及请求幂等';

CREATE TABLE knowledge_document (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(200) NOT NULL,
  file_key VARCHAR(255) NOT NULL,
  file_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  mime_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  source_description VARCHAR(1000) NOT NULL,
  document_version VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  task_kind VARCHAR(16) NOT NULL DEFAULT 'INGEST',
  target_space_key VARCHAR(128) COLLATE utf8mb4_bin NULL,
  task_operation_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  task_deadline DATETIME(3) NULL,
  total_chunks INT NOT NULL DEFAULT 0,
  embedded_chunks INT NOT NULL DEFAULT 0,
  attempt_no INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(1000) NULL,
  uploaded_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_file_version (file_sha256, document_version),
  KEY idx_document_enabled_status (enabled, status, id),
  KEY idx_document_space (target_space_key, enabled, status, id),
  KEY idx_document_task (task_kind, status, task_deadline),
  CONSTRAINT fk_document_uploader FOREIGN KEY (uploaded_by) REFERENCES sys_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_document_enabled CHECK (enabled IN (0,1)),
  CONSTRAINT ck_document_status CHECK (status IN ('QUEUED','PARSING','CHUNKING','EMBEDDING','READY','FAILED')),
  CONSTRAINT ck_document_task_kind CHECK (task_kind IN ('INGEST','REBUILD')),
  CONSTRAINT ck_document_size CHECK (file_size > 0),
  CONSTRAINT ck_document_source CHECK (CHAR_LENGTH(TRIM(source_description)) > 0 AND CHAR_LENGTH(TRIM(document_version)) > 0),
  CONSTRAINT ck_document_progress CHECK (total_chunks >= 0 AND embedded_chunks BETWEEN 0 AND total_chunks AND attempt_no >= 0),
  CONSTRAINT ck_document_ready CHECK (status <> 'READY' OR
    (total_chunks > 0 AND embedded_chunks = total_chunks AND target_space_key IS NOT NULL))
) ENGINE=InnoDB COMMENT='知识资料及跨库向量任务状态';

CREATE TABLE knowledge_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  chunk_no INT NOT NULL,
  split_version VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  content MEDIUMTEXT NOT NULL,
  content_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  title_path VARCHAR(500) NULL,
  page_start INT NULL,
  page_end INT NULL,
  paragraph_start INT NULL,
  paragraph_end INT NULL,
  char_count INT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk_document_split_no (document_id, split_version, chunk_no),
  KEY idx_chunk_document (document_id, id),
  CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_chunk_size CHECK (chunk_no >= 1 AND char_count > 0 AND CHAR_LENGTH(TRIM(content)) > 0),
  CONSTRAINT ck_chunk_pages CHECK (
    (page_start IS NULL AND page_end IS NULL) OR
    (page_start IS NOT NULL AND page_end IS NOT NULL AND page_start >= 1 AND page_end >= page_start)),
  CONSTRAINT ck_chunk_paragraphs CHECK (
    (paragraph_start IS NULL AND paragraph_end IS NULL) OR
    (paragraph_start IS NOT NULL AND paragraph_end IS NOT NULL AND paragraph_start >= 1 AND paragraph_end >= paragraph_start))
) ENGINE=InnoDB COMMENT='不可变知识片段及真实来源位置';

CREATE TABLE agent_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  patient_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  client_request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  summary_version INT NOT NULL,
  input_snapshot JSON NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  config_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  prompt_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  tool_names JSON NOT NULL,
  knowledge_snapshot JSON NULL COMMENT '急诊规则引导可无知识快照',
  safety_rule_version VARCHAR(40) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  max_rounds INT NOT NULL DEFAULT 4,
  max_tool_calls INT NOT NULL DEFAULT 6,
  actual_rounds INT NOT NULL DEFAULT 0,
  actual_tool_calls INT NOT NULL DEFAULT 0,
  deadline_at DATETIME(3) NOT NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(500) NULL,
  trace_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_no (run_no),
  UNIQUE KEY uk_run_patient_request (patient_id, client_request_id),
  KEY idx_run_session (session_id, created_at, id),
  KEY idx_run_deadline (status, deadline_at),
  KEY idx_run_trace (trace_id),
  CONSTRAINT fk_run_patient FOREIGN KEY (patient_id) REFERENCES patient_profile(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_run_session FOREIGN KEY (session_id) REFERENCES consultation_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_run_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','DEGRADED','FAILED','CANCELLED')),
  CONSTRAINT ck_run_summary CHECK (summary_version >= 0),
  CONSTRAINT ck_run_budget CHECK (max_rounds BETWEEN 1 AND 4 AND max_tool_calls BETWEEN 0 AND 6
    AND actual_rounds BETWEEN 0 AND max_rounds AND actual_tool_calls BETWEEN 0 AND max_tool_calls)
) ENGINE=InnoDB COMMENT='受控导诊智能体运行及不可变输入快照';

CREATE TABLE agent_step (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  round_no INT NOT NULL DEFAULT 0 COMMENT '非模型阶段可为0',
  step_type VARCHAR(24) NOT NULL,
  tool_name VARCHAR(100) NULL,
  tool_call_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  input_redacted JSON NULL,
  output_summary JSON NULL,
  evidence_chunk_ids JSON NULL,
  public_label VARCHAR(200) NOT NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  duration_ms BIGINT NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_step_run_sequence (run_id, sequence_no),
  CONSTRAINT fk_step_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_step_numbers CHECK (sequence_no >= 1 AND round_no BETWEEN 0 AND 4),
  CONSTRAINT ck_step_type CHECK (step_type IN ('MODEL','TOOL','SAFETY','VALIDATION','FINAL')),
  CONSTRAINT ck_step_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','TIMEOUT')),
  CONSTRAINT ck_step_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE=InnoDB COMMENT='实际模型工具及校验步骤，不保存隐藏思维链';

CREATE TABLE triage_result (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  patient_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  summary_version INT NOT NULL,
  department_id BIGINT NULL,
  alternative_department_ids JSON NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  validation_status VARCHAR(24) NOT NULL,
  reason TEXT NOT NULL,
  evidence_refs JSON NOT NULL,
  doctor_options_snapshot JSON NOT NULL,
  missing_information JSON NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_result_run (run_id),
  KEY idx_result_patient (patient_id, created_at, id),
  KEY idx_result_department (department_id, created_at),
  KEY idx_result_session (session_id),
  CONSTRAINT fk_result_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_result_patient FOREIGN KEY (patient_id) REFERENCES patient_profile(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_result_session FOREIGN KEY (session_id) REFERENCES consultation_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_result_department FOREIGN KEY (department_id) REFERENCES department(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_result_summary CHECK (summary_version >= 0),
  CONSTRAINT ck_result_risk CHECK (risk_level IN ('ROUTINE','ATTENTION','EMERGENCY')),
  CONSTRAINT ck_result_validation CHECK (validation_status IN ('VALID','LIMITED','NEED_MORE_INFO')),
  CONSTRAINT ck_result_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB COMMENT='不可变导诊结果及证据快照';

CREATE TABLE registration (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  patient_id BIGINT NOT NULL,
  schedule_id BIGINT NOT NULL,
  doctor_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  client_request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  triage_result_id BIGINT NULL,
  visit_date DATE NOT NULL,
  time_slot VARCHAR(8) NOT NULL,
  patient_name_snapshot VARCHAR(64) NOT NULL,
  doctor_name_snapshot VARCHAR(80) NOT NULL,
  department_name_snapshot VARCHAR(80) NOT NULL,
  fee_snapshot DECIMAL(10,2) NOT NULL,
  symptom_description TEXT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  cancelled_at DATETIME(3) NULL,
  cancel_reason VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_registration_no (registration_no),
  UNIQUE KEY uk_registration_patient_request (patient_id, client_request_id),
  KEY idx_registration_patient (patient_id, created_at, id),
  KEY idx_registration_doctor_queue (doctor_id, visit_date, status, id),
  KEY idx_registration_active_slot (patient_id, visit_date, time_slot, status),
  KEY idx_registration_department (department_id, created_at),
  KEY idx_registration_completed (completed_at),
  KEY idx_registration_schedule (schedule_id, status),
  CONSTRAINT fk_registration_patient FOREIGN KEY (patient_id) REFERENCES patient_profile(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_registration_schedule FOREIGN KEY (schedule_id) REFERENCES doctor_schedule(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_registration_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_registration_department FOREIGN KEY (department_id) REFERENCES department(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_registration_result FOREIGN KEY (triage_result_id) REFERENCES triage_result(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_registration_slot CHECK (time_slot IN ('AM','PM')),
  CONSTRAINT ck_registration_fee CHECK (fee_snapshot >= 0),
  CONSTRAINT ck_registration_status CHECK (status IN ('WAITING','IN_PROGRESS','COMPLETED','CANCELLED'))
) ENGINE=InnoDB COMMENT='挂号及历史快照，活动时段唯一由锁患者档案实现';

CREATE TABLE medical_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_id BIGINT NOT NULL,
  doctor_id BIGINT NOT NULL,
  chief_complaint TEXT NULL,
  present_illness TEXT NULL,
  past_history TEXT NULL,
  physical_exam TEXT NULL,
  auxiliary_exam TEXT NULL,
  preliminary_diagnosis TEXT NULL,
  treatment_plan TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 0,
  submitted_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_record_registration (registration_id),
  KEY idx_record_doctor (doctor_id),
  CONSTRAINT fk_record_registration FOREIGN KEY (registration_id) REFERENCES registration(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_record_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_record_status CHECK (status IN ('DRAFT','SUBMITTED')),
  CONSTRAINT ck_record_version CHECK (version >= 0),
  CONSTRAINT ck_record_submission CHECK (
    (status = 'DRAFT' AND submitted_at IS NULL) OR
    (status = 'SUBMITTED' AND submitted_at IS NOT NULL
     AND chief_complaint IS NOT NULL AND CHAR_LENGTH(TRIM(chief_complaint)) BETWEEN 1 AND 10000
     AND present_illness IS NOT NULL AND CHAR_LENGTH(TRIM(present_illness)) BETWEEN 1 AND 10000
     AND past_history IS NOT NULL AND CHAR_LENGTH(TRIM(past_history)) BETWEEN 1 AND 10000
     AND physical_exam IS NOT NULL AND CHAR_LENGTH(TRIM(physical_exam)) BETWEEN 1 AND 10000
     AND auxiliary_exam IS NOT NULL AND CHAR_LENGTH(TRIM(auxiliary_exam)) BETWEEN 1 AND 10000
     AND preliminary_diagnosis IS NOT NULL AND CHAR_LENGTH(TRIM(preliminary_diagnosis)) BETWEEN 1 AND 10000
     AND treatment_plan IS NOT NULL AND CHAR_LENGTH(TRIM(treatment_plan)) BETWEEN 1 AND 10000)
  )
) ENGINE=InnoDB COMMENT='七段电子病历，提交后应用拒绝修改正文';

CREATE TABLE ai_call_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  trace_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '一次真实上游请求的关联标识',
  run_id BIGINT NULL,
  step_id BIGINT NULL,
  message_id BIGINT NULL,
  document_id BIGINT NULL,
  purpose VARCHAR(24) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  config_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  input_tokens BIGINT NULL,
  output_tokens BIGINT NULL,
  total_tokens BIGINT NULL,
  usage_source VARCHAR(16) NOT NULL DEFAULT 'UNAVAILABLE',
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  duration_ms BIGINT NULL,
  first_token_latency_ms BIGINT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_call_started_purpose (started_at, purpose),
  KEY idx_call_run_step (run_id, step_id),
  KEY idx_call_trace (trace_id),
  KEY idx_call_request (request_id),
  CONSTRAINT fk_call_run FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_call_step FOREIGN KEY (step_id) REFERENCES agent_step(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_call_message FOREIGN KEY (message_id) REFERENCES consultation_message(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_call_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_call_purpose CHECK (purpose IN ('PRECONSULT','AGENT','EMBED_DOCUMENT','EMBED_QUERY')),
  CONSTRAINT ck_call_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','TIMEOUT','CANCELLED')),
  CONSTRAINT ck_call_usage_source CHECK (usage_source IN ('PROVIDER','UNAVAILABLE')),
  CONSTRAINT ck_call_tokens CHECK ((input_tokens IS NULL OR input_tokens >= 0)
    AND (output_tokens IS NULL OR output_tokens >= 0) AND (total_tokens IS NULL OR total_tokens >= 0)),
  CONSTRAINT ck_call_unknown_usage CHECK (usage_source <> 'UNAVAILABLE' OR
    (input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)),
  CONSTRAINT ck_call_duration CHECK ((duration_ms IS NULL OR duration_ms >= 0)
    AND (first_token_latency_ms IS NULL OR first_token_latency_ms >= 0))
) ENGINE=InnoDB COMMENT='真实模型调用日志，未知usage为NULL';

-- 循环引用在被引用表创建之后添加，不关闭外键检查。
ALTER TABLE consultation_session
  ADD CONSTRAINT fk_session_source_message FOREIGN KEY (summary_source_message_id)
    REFERENCES consultation_message(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_session_latest_result FOREIGN KEY (latest_result_id)
    REFERENCES triage_result(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 角色归属、跨表身份一致、状态迁移、历史快照不变、结果可预约性、
-- 预约锁顺序/条件扣号、病历提交锁定和跨库READY校验仍由Service层执行。
-- JSON中的引用/科室ID不能由普通外键保证，必须校验真实来源和当前run。

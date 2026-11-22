-- =============================================================
-- 基于 Spring Boot 的血糖时序监测与智能问答系统的设计与实现 · V1.1 空库初始化
-- 修订日期：2026-10-19；目标：MySQL 5.7.12+（已选定的 5.7 系列）
-- 编码：UTF-8；连接字符集：utf8mb4；业务时区：Asia/Shanghai
--
-- 适用：新建 glucose_db 空库；不适用：有 V1.0 业务数据的数据库。
-- 本脚本不执行 DROP、TRUNCATE、覆盖更新或 V1.0 数据迁移。
-- CREATE TABLE 故意不使用 IF NOT EXISTS：发现已有表应停止，不能混用版本。
-- 请使用客户端遇错即停的默认行为，不要加 --force；DDL 不保证整体回滚。
-- 15 张表：12 张 P0 核心表 + 用药计划/记录 P1 + 饮食 P2 预留表。
-- 不使用外键；跨表归属/角色由应用服务与事务校验。
-- MySQL 5.7 不执行 CHECK：本脚本不声明 CHECK，业务完整性由后端统一校验。
-- 保留 NOT NULL、主键、普通/唯一索引与 STORED 生成列；不使用 CTE/窗口函数。
-- 显式 DYNAMIC 行格式；要求 InnoDB large_prefix=ON，5.7.12+ 默认环境适用。
--
-- 不预置任何管理员密码或伪造向量。
-- 示例知识为本项目编写的演示说明，status=0、index_status=PENDING。
-- 核验来源、内容和权限后，由管理端重建索引并显式启用。
-- 此文件更新不代表数据库已执行；已有数据须另写经审核的迁移脚本。
-- =============================================================

SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;
SET SESSION time_zone = '+08:00';
SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE DATABASE IF NOT EXISTS glucose_db
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE glucose_db;

-- 01. 用户与账号模式
CREATE TABLE sys_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  password VARCHAR(100) NULL COMMENT '仅账密账号存 BCrypt；微信患者可为 NULL',
  real_name VARCHAR(50) NULL,
  phone VARCHAR(20) NULL,
  avatar_url VARCHAR(255) NULL,
  openid VARCHAR(64) NULL,
  role VARCHAR(20) NOT NULL,
  data_mode VARCHAR(8) NOT NULL DEFAULT 'REAL' COMMENT '患者账号的数据模式；演示账号为 DEMO',
  status TINYINT NOT NULL DEFAULT 1,
  auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '密码变更/停用/注销后使旧凭证失效',
  last_login_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_openid (openid),
  KEY idx_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 02. 患者档案与计算版本
CREATE TABLE patient_profile (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  doctor_id BIGINT UNSIGNED NULL,
  gender TINYINT NULL,
  birth_date DATE NULL,
  diabetes_type VARCHAR(20) NULL COMMENT '用户提供，不用于自动生成诊断或目标',
  cgm_target_low DECIMAL(4,1) NOT NULL DEFAULT 3.9,
  cgm_target_high DECIMAL(4,1) NOT NULL DEFAULT 10.0,
  target_revision BIGINT NOT NULL DEFAULT 1,
  data_revision BIGINT NOT NULL DEFAULT 0,
  privacy_version VARCHAR(30) NULL,
  privacy_accepted_at DATETIME NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_id (user_id),
  KEY idx_doctor (doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 03. SMBG 分测量类型目标
CREATE TABLE glucose_target_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  measure_type TINYINT NOT NULL,
  target_low DECIMAL(4,1) NOT NULL,
  target_high DECIMAL(4,1) NOT NULL,
  basis VARCHAR(255) NULL COMMENT '设置依据或用户提供说明',
  updated_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_type (patient_id, measure_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 04. CGM 导入批次
CREATE TABLE glucose_import_batch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  request_id CHAR(36) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  temp_file_key VARCHAR(255) NULL COMMENT '后端内部引用，不向前端暴露路径',
  data_source VARCHAR(8) NOT NULL DEFAULT 'CGM',
  data_kind VARCHAR(8) NOT NULL DEFAULT 'REAL',
  sample_interval_minutes SMALLINT NOT NULL DEFAULT 5,
  status VARCHAR(24) NOT NULL COMMENT 'READY/REJECTED/CONFIRMED/NO_CHANGE/EXPIRED/REVOKED',
  total_rows INT NOT NULL DEFAULT 0,
  valid_rows INT NOT NULL DEFAULT 0,
  duplicate_rows INT NOT NULL DEFAULT 0,
  conflict_rows INT NOT NULL DEFAULT 0,
  error_rows INT NOT NULL DEFAULT 0,
  inserted_rows INT NOT NULL DEFAULT 0,
  start_at DATETIME NULL,
  end_at DATETIME NULL,
  expires_at DATETIME NOT NULL,
  confirmed_at DATETIME NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_request (patient_id, request_id),
  KEY idx_patient_batch (patient_id, created_at),
  KEY idx_file_hash (patient_id, file_sha256, data_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 05. 分来源血糖记录
CREATE TABLE glucose_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  measured_at DATETIME NOT NULL,
  glucose_mmol DECIMAL(4,1) NOT NULL,
  data_source VARCHAR(8) NOT NULL,
  ingest_method VARCHAR(12) NOT NULL,
  data_kind VARCHAR(8) NOT NULL DEFAULT 'REAL',
  measure_type TINYINT NULL COMMENT 'SMBG 必填；CGM 为 NULL，不能伪装成空腹或随机',
  sample_interval_minutes SMALLINT NULL,
  batch_id BIGINT UNSIGNED NULL,
  risk_flag VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '基础提醒结果，不等同于是否达标',
  note VARCHAR(200) NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  active_measured_at DATETIME GENERATED ALWAYS AS
    (CASE WHEN deleted = 0 THEN measured_at ELSE NULL END) STORED,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_active_point (patient_id, data_source, data_kind, active_measured_at),
  KEY idx_patient_source_time (patient_id, data_source, data_kind, measured_at),
  KEY idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 06. 基础提醒规则
CREATE TABLE alert_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 为全局基础规则',
  rule_type VARCHAR(8) NOT NULL COMMENT 'LOW/HIGH',
  warning_threshold DECIMAL(4,1) NOT NULL,
  severe_threshold DECIMAL(4,1) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  version BIGINT NOT NULL DEFAULT 1,
  updated_by BIGINT UNSIGNED NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_patient_rule (patient_id, rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 07. 合并预警事件
CREATE TABLE alert_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  data_source VARCHAR(8) NOT NULL,
  data_kind VARCHAR(8) NOT NULL,
  alert_type VARCHAR(8) NOT NULL COMMENT 'LOW/HIGH，严重度存 level',
  level TINYINT NOT NULL COMMENT '2 警告，3 严重；取事件内最高级别',
  first_record_id BIGINT UNSIGNED NOT NULL,
  last_record_id BIGINT UNSIGNED NOT NULL,
  start_at DATETIME NOT NULL,
  last_abnormal_at DATETIME NOT NULL,
  end_at DATETIME NULL,
  min_glucose DECIMAL(4,1) NOT NULL,
  max_glucose DECIMAL(4,1) NOT NULL,
  sample_count INT NOT NULL DEFAULT 1,
  event_status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ENDED/END_UNKNOWN/INVALIDATED',
  handle_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/HANDLED',
  is_historical TINYINT NOT NULL DEFAULT 0,
  message VARCHAR(255) NOT NULL,
  rule_snapshot JSON NOT NULL,
  active_key VARCHAR(160) NULL COMMENT '仅 OPEN 时有值；关闭后置 NULL',
  version BIGINT NOT NULL DEFAULT 1 COMMENT '并发更新版本',
  notice_version BIGINT NOT NULL DEFAULT 1 COMMENT '新建/升级才递增，普通采样不重复制造未读',
  patient_seen_notice_version BIGINT NOT NULL DEFAULT 0,
  last_notified_at DATETIME NULL,
  handled_by BIGINT UNSIGNED NULL,
  handled_at DATETIME NULL,
  handle_note VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_active_event (active_key),
  KEY idx_patient_event (patient_id, data_source, data_kind, start_at),
  KEY idx_handle (handle_status, level, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 08. 可追溯知识文档
CREATE TABLE kb_document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title VARCHAR(200) NOT NULL,
  category VARCHAR(50) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  source_title VARCHAR(255) NOT NULL,
  source_url VARCHAR(1000) NULL,
  source_version VARCHAR(80) NULL,
  published_on DATE NULL,
  document_version INT NOT NULL DEFAULT 1,
  content_hash CHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  index_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/BUILDING/READY/FAILED',
  active_index_version VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_kb_status (status, deleted, index_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 09. 版本化知识切片与向量
CREATE TABLE kb_chunk (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_id BIGINT UNSIGNED NOT NULL,
  document_version INT NOT NULL,
  index_version VARCHAR(64) NOT NULL,
  chunk_no INT NOT NULL,
  content VARCHAR(1200) NOT NULL,
  embedding BLOB NOT NULL,
  embedding_model VARCHAR(100) NOT NULL,
  dim SMALLINT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_index_chunk (doc_id, index_version, chunk_no),
  KEY idx_index_model (index_version, embedding_model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 10. 问答会话
CREATE TABLE qa_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(100) NOT NULL DEFAULT '新的对话',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_patient_session (patient_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 11. 消息、引用与数据摘要快照
CREATE TABLE qa_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_id BIGINT UNSIGNED NOT NULL,
  request_id CHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT 'PROCESSING/COMPLETED/FAILED',
  answer_mode VARCHAR(24) NULL COMMENT 'RAG/RETRIEVAL_ONLY/NO_COVERAGE/FIXED_NOTICE/HUMAN',
  sources JSON NULL COMMENT '实际引用片段、原文、来源与版本快照',
  context_snapshot JSON NULL COMMENT '区间、来源、样本量、覆盖、目标/数据/算法版本',
  model VARCHAR(100) NULL,
  embedding_model VARCHAR(100) NULL,
  prompt_version VARCHAR(30) NULL,
  degraded TINYINT NOT NULL DEFAULT 0,
  latency_ms INT NULL,
  failure_reason VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_request_role (session_id, request_id, role),
  KEY idx_session_message (session_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 12. 操作审计
CREATE TABLE operation_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  actor_id BIGINT UNSIGNED NULL,
  action VARCHAR(80) NOT NULL,
  resource_type VARCHAR(40) NOT NULL,
  resource_id VARCHAR(64) NULL,
  summary VARCHAR(500) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  details JSON NULL COMMENT '脱敏后的变更摘要，不保存凭据或整段健康请求',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_actor_time (actor_id, created_at),
  KEY idx_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 13. 用药计划。每个计划为每天一次的记录时点，医嘱字段创建后不覆盖历史。
CREATE TABLE medication_plan (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  drug_name VARCHAR(100) NOT NULL,
  dosage VARCHAR(50) NOT NULL COMMENT '仅登记已有医嘱，不由系统推荐',
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

-- 14. 用药打卡。保留旧版 taken 字段以兼容历史记录。
CREATE TABLE medication_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  record_date DATE NOT NULL,
  drug_name VARCHAR(100) NOT NULL,
  dosage VARCHAR(50) NULL COMMENT '用户已有方案的记录，不由系统推荐',
  plan_id BIGINT UNSIGNED NULL,
  scheduled_time TIME NULL,
  instruction VARCHAR(200) NOT NULL DEFAULT '',
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING未记录/TAKEN已服用/SKIPPED跳过/CANCELLED计划停用',
  version BIGINT NOT NULL DEFAULT 1,
  taken_at DATETIME NULL,
  taken TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plan_date (plan_id, record_date),
  KEY idx_patient_date (patient_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- 15. 饮食打卡（P2 预留）
CREATE TABLE diet_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  patient_id BIGINT UNSIGNED NOT NULL,
  record_date DATE NOT NULL,
  meal_type TINYINT NOT NULL,
  food_desc VARCHAR(500) NOT NULL,
  carbs_g DECIMAL(6,1) NULL,
  image_url VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_patient_date (patient_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;

-- =============================================================
-- 初始化数据；建表全部成功后执行，种子数据使用单独事务。
-- 规则是本项目演示默认配置，不是适用于所有人群的诊断标准。
-- LOW：g < 3.9 提醒、g < 3.0 严重；HIGH：g > 13.9 提醒、g > 16.7 严重。
-- 严重判断优先；同方向只生成一个事件，level 由实际读数决定。
-- =============================================================
START TRANSACTION;

INSERT INTO alert_rule
  (patient_id, rule_type, warning_threshold, severe_threshold, enabled, version)
VALUES
  (0, 'LOW', 3.9, 3.0, 1, 1),
  (0, 'HIGH', 13.9, 16.7, 1, 1);

INSERT INTO kb_document
  (title, category, content, source_title, source_version, document_version,
   content_hash, status, index_status, active_index_version)
VALUES
  ('【演示待审核】测量值达标率与 TIR 的区别', '基础概念', '手工指尖血糖测量只反映已测时点。测量值达标率按有对应目标的测量记录计算，不能解释为全天处于目标范围的时间占比。TIR 用于满足数据条件的连续葡萄糖监测；本系统还显示监测区间、有效样本量和覆盖率。目标范围需结合适用人群设置，不是统一诊断标准。', '本项目 V1.1 演示说明；非外部指南原文，待人工审核', 'V1.1 / 2026-10-19', 1, '2d819439e16f798cafc5c1d07ff84a6b3a3c20ea43ad6a9a70c8d0748bc2f545', 0, 'PENDING', NULL);

INSERT INTO kb_document
  (title, category, content, source_title, source_version, document_version,
   content_hash, status, index_status, active_index_version)
VALUES
  ('【演示待审核】连续监测数据不足时如何展示', '数据解读', '缺测不等于血糖为零或正常。本系统只对有效的五分钟采样槽位统计覆盖，不跨缺口补值。按项目规则，连续监测窗口不足十四个完整日或覆盖率低于百分之七十时，只展示描述性统计和不足原因，不展示长期 TIR 或 GMI。该门槛是系统展示规则，不代表达到门槛即可保证临床有效。', '本项目 V1.1 演示说明；非外部指南原文，待人工审核', 'V1.1 / 2026-10-19', 1, '0a37078285201d94b207d34ae883bacdbae4b3ee922b2e652d9f41eb2efacc07', 0, 'PENDING', NULL);

INSERT INTO kb_document
  (title, category, content, source_title, source_version, document_version,
   content_hash, status, index_status, active_index_version)
VALUES
  ('【演示待审核】记录空腹和餐后测量信息', '记录方法', '保存手工测量值时，应填写测量时间及实际测量类型。餐后两小时通常从开始进餐时计时。没有确认的空腹或餐后背景时，不应由系统凭时刻猜测测量类型；未设置某类型目标时，应显示目标未配置，不自动判断达标。', '本项目 V1.1 演示说明；非外部指南原文，待人工审核', 'V1.1 / 2026-10-19', 1, 'cd8ba568a563f2fd967bc28b4fd7de538d24d5388ac676914d69f89f20e6b741', 0, 'PENDING', NULL);

INSERT INTO kb_document
  (title, category, content, source_title, source_version, document_version,
   content_hash, status, index_status, active_index_version)
VALUES
  ('【演示待审核】异常提醒与系统使用边界', '使用说明', '异常提醒用于提示读数需要关注，不是诊断或处方。若读数明显异常，请及时寻求专业医疗帮助。本系统不根据夜间或晨起的少量测量判断病因，也不提供药品剂量调整。历史导入与演示数据应明确标识，不能冒充当前实时状态。', '本项目 V1.1 演示说明；非外部指南原文，待人工审核', 'V1.1 / 2026-10-19', 1, 'e49d78bf2c4ab9a7b5492bbfbcfb090d9dd70d2bff37025401b4af66a9980997', 0, 'PENDING', NULL);

COMMIT;

-- 管理员创建：由部署初始化器从安全输入读取初始口令，使用 BCrypt 后写入。
-- BCrypt 每次随机加盐产生不同散列是正常现象，并不妨碍校验。
-- 不提交口令、散列或默认弱密码，不开放临时的未鉴权“创建管理员”接口。
-- 应用层校验：DOCTOR / ADMIN 必须保存有效 BCrypt 散列；微信 PATIENT 可 password=NULL。
-- P0 不创建演示账号或患者血糖；后续生成器仅写入专用 data_mode=DEMO 账号。
-- 不创建 kb_chunk 占位向量；Embedding API 的模型/实际维度由入库流程确定。
-- bge-small-zh-v1.5 通常为 512 维，其他模型按实际响应校验 dim 与 BLOB 长度。

-- 版本/配置检查与初始化结果（只读）：14 表、2 条基础规则、4 条停用示例。
-- MySQL 8 已移除 innodb_large_prefix；SHOW VARIABLES 在变量不存在时返回空集，兼容 5.7/8。
SELECT VERSION() AS mysql_version, @@innodb_default_row_format AS default_row_format,
       @@sql_mode AS session_sql_mode;
SHOW VARIABLES LIKE 'innodb_large_prefix';
SELECT COUNT(*) AS created_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';

SELECT rule_type, warning_threshold, severe_threshold, enabled
FROM alert_rule WHERE patient_id = 0 ORDER BY rule_type;

SELECT id, title, status, index_status
FROM kb_document ORDER BY id;

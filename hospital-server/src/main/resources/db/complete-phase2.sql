-- 完整版阶段二：聊天与向量模型分离的数据库配置修订。
-- 幂等增量迁移；模型配置初始为空，未配置时后端回退到环境属性 + ai_provider_credential。

CREATE TABLE IF NOT EXISTS ai_model_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(100) NOT NULL,
  model_type VARCHAR(16) NOT NULL COMMENT 'CHAT 或 EMBEDDING',
  current_revision_id BIGINT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
  default_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status='ENABLED' THEN 1 ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_config_code (code),
  UNIQUE KEY uk_ai_config_type_guard (model_type, default_guard),
  KEY idx_ai_config_type (model_type, status, id)
) ENGINE=InnoDB COMMENT='模型配置，同类型至多一个启用配置';

CREATE TABLE IF NOT EXISTS ai_model_config_revision (
  id BIGINT NOT NULL AUTO_INCREMENT,
  config_id BIGINT NOT NULL,
  revision INT NOT NULL,
  provider VARCHAR(64) NOT NULL DEFAULT 'BAILIAN',
  base_url VARCHAR(500) NOT NULL,
  model_name VARCHAR(100) NOT NULL,
  api_key VARCHAR(512) NULL COMMENT '沿用本机约定明文存储，接口不回传',
  temperature DECIMAL(3,2) NULL,
  max_output_tokens INT NULL,
  timeout_ms INT NOT NULL DEFAULT 30000,
  embedding_dimension INT NULL COMMENT '向量模型必填',
  test_status VARCHAR(16) NOT NULL DEFAULT 'UNTESTED',
  tested_at DATETIME(3) NULL,
  test_detail VARCHAR(1000) NULL,
  created_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_config_revision (config_id, revision),
  KEY idx_ai_revision_config (config_id, id)
) ENGINE=InnoDB COMMENT='不可变模型配置修订，测试通过才能激活';

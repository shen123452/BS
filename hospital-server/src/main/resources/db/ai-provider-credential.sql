CREATE TABLE IF NOT EXISTS ai_provider_credential (
  provider VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  api_key VARCHAR(512) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端专用凭据，不对接口回传',
  updated_by BIGINT NULL COMMENT '最近保存的管理员账号，启动导入时为空',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型供应商凭据';

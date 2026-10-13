# 缩减版双数据库初始化

对应V2.2设计，共16张表。MySQL保存15张表，PostgreSQL保存1张向量表。本机执行结果见[本地数据库初始化记录](本地数据库初始化记录.md)，未连接或修改远程服务器。

| 文件 | 用途 |
|---|---|
| [01_mysql_schema.sql](01_mysql_schema.sql) | 创建独立hospital_triage数据库及15张业务表 |
| [02_postgresql_schema.sql](02_postgresql_schema.sql) | 在独立hospital_triage_vector数据库内启用vector扩展并创建向量表 |

## 执行方法

1. MySQL使用5.7.25或更高的5.7补丁版本，以有建库/建表权限的初始化账号运行01_mysql_schema.sql。文件设置utf8mb4和本连接的+08:00时区；应用连接也须配置时区和严格SQL模式。脚本使用MySQL 5.7提供的JSON、毫秒时间精度和utf8mb4_unicode_ci。
2. PostgreSQL服务器先安装pgvector 0.6.0或以上。连接维护库，在事务外执行`CREATE DATABASE hospital_triage_vector ENCODING 'UTF8' TEMPLATE template0;`，切换至该库后运行02_postgresql_schema.sql。若扩展已经安装在非public schema，应先调整脚本schema限定，不能直接混用。
3. 本地开发直接使用已有数据库登录账号：MySQL 使用 root，PostgreSQL 使用容器初始化账号 hospital_vector_user，不额外创建应用专用数据库账号。两库连接及事务管理器分别配置，密码由环境变量提供，不写入项目文件。网站登录统一使用 sys_user，角色固定为 ADMIN、DOCTOR、PATIENT，权限在后端按角色与数据归属检查；数据库连接账号不参与网站权限管理。

持久层可以使用 MyBatis-Plus。建议 MySQL 业务库使用 MyBatis-Plus 的实体映射、基础 CRUD 和分页能力；预约扣号、取消返号、状态迁移、幂等检查、知识候选过滤等关键 SQL 使用自定义 Mapper SQL。PostgreSQL 向量库优先使用 JdbcTemplate/JdbcClient，避免为 pgvector 类型额外维护第二套 MyBatis-Plus 映射；若确需统一持久层，必须为两个数据源分别配置 SqlSessionFactory、事务管理器和 Mapper 扫描路径。

两份脚本用于首次空库初始化；不包含DROP/TRUNCATE，不重复执行建表部分。MySQL DDL会隐式提交，失败可能留下已建的部分表，应根据报错检查执行进度，不能视为自动整体回滚。PostgreSQL建表、索引和触发器在单个事务内执行。对已有表升级须另写迁移，不能通过IF NOT EXISTS掩盖结构差异。

## 类型与实现约定

- 所有ID及外键统一为有符号BIGINT，自动生成正ID，兼容Java Long及跨库chunkId映射；API仍使用字符串ID。MySQL文档原先的UNSIGNED ID在实际DDL中统一为有符号范围，数量字段仍可用UNSIGNED。
- 状态使用VARCHAR和Java Enum。MySQL 5.7 会解析但忽略 CHECK 声明，不能依赖它拒绝非法值；枚举、范围、跨字段规则由后端 Service 校验，并用真实MySQL集成测试覆盖。空草稿允许正文为NULL，SUBMITTED要求七段非空且各不超过10,000字符；提交后不可修改和病历/挂号同事务完成由后端实现。
- request/client ID、哈希、空间键与稳定编码按区分大小写规则比较；username按数据库默认不区分大小写/重音的唯一规则，应用登录名规范化必须与之保持一致。
- 活动时段唯一遵循设计的“锁患者档案→检查→条件扣号→插预约”事务，没有额外添加生成列。脚本中的 CHECK 声明在 MySQL 5.7 不执行，不能代替后端校验、防超卖、身份/归属校验和跨库完整性检查。
- PostgreSQL保存不固定维度的vector，CHECK校验实际维度，触发器计算真实norm并维护updated_at；FAILED可无向量，READY必须有有效非零向量。首版不建HNSW/IVFFlat索引。
- 先从MySQL筛选有效chunkId，再在pgvector中过滤、排序、LIMIT，最后回查来源正文。两库之间没有外键、分布式事务或Redis。
- 不含种子数据；后续种子需使用虚构病例、动态排班日期和开发环境生成的BCrypt密码。

## 核验范围

2026-10-13 已在本机 MySQL 5.7.25 与 Docker PostgreSQL 16.15/pgvector 0.8.6 实际执行初始化脚本，完成中文/JSON读写、向量排序及约束验证。后端已使用现有root和hospital_vector_user接入，并完成管理员初始化、双库健康检查和向量库启动故障隔离验证。后续普通门诊、问诊、知识库与导诊复用同一表结构，上传前完整后端57项测试通过；维护重建、更多双库异常及完整真实模型验收仍待继续，见[开发进度](../../docs/开发进度.md)。初始化记录中的阶段数量为当时证据，不代表当前最终范围。

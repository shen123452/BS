# 医院智能导诊后端

已创建可运行的后端工程。MySQL 使用 MyBatis-Plus；PostgreSQL 向量访问使用 JdbcTemplate。两个数据源与事务管理器显式分开，不自动建表、不操作远程服务器。

## 环境与启动

JDK 21、Maven 3.9+，本地 MySQL57 运行，Docker 中 hospital-triage-pg 运行。数据库已按[初始化记录](../缩减版/sql/本地数据库初始化记录.md)创建。

日常开发使用 IDEA 直接运行普通 Spring Boot 启动类，无需执行脚本：

1. 打开 `pom.xml`，导入 Maven 工程，项目 JDK 选择21。
2. 找到 `src/main/java/com/shen/hospital/HospitalApplication.java`，创建其运行配置。
3. 在 IDEA 的“运行 → 编辑配置 → 环境变量”中填写以下变量。凭据仅保留在本机运行配置，不共享或提交包含凭据的配置文件。

| 变量 | 内容 |
|---|---|
| MYSQL_PASSWORD | 本地MySQL root账号的密码 |
| VECTOR_DB_PASSWORD | 本项目PostgreSQL的hospital_vector_user密码 |
| JWT_SECRET | 自行生成并保留的随机签名密钥，至少32字节；保持一致时重启不会仅因密钥变化使Token失效 |
| DASHSCOPE_API_KEY | 可选的首次导入值；管理员保存到 MySQL 后以数据库记录为准 |

4. 点击运行 `HospitalApplication`。本机管理员已于2026-10-13初始化，用户名为admin，无需再次初始化。若8082已有后端进程，先停止旧的后端再运行。

数据库地址、账号和模型名称已有本地默认值，需要调整时再设置相应变量。管理员可在 `/admin/ai-settings` 保存 API Key；后端写入 MySQL `ai_provider_credential` 表并在后续请求读取，接口不会回传密钥。启动时仅当数据库没有记录时才导入 `DASHSCOPE_API_KEY`。`.env.example`列出完整变量说明，Spring Boot不自动加载`.env`文件。MYSQL_PASSWORD与至少32字节的JWT_SECRET必填；未配置百炼密钥时基础管理接口仍可启动，AI验证明确返回AI_NOT_CONFIGURED。向量库不可用时不阻塞基础服务启动。

`scripts/start-dev.ps1`是可选的命令行辅助工具，不参与业务代码，项目不依赖它。它交互读取密码、生成本次会话的JWT密钥、打包并运行同一个启动类。命令行需要AI时加`-ConfigureAi`；仅首次新建管理员时加`-BootstrapAdmin`，已有管理员时保留原密码。密码和密钥不保存到项目文件。

服务默认只监听 127.0.0.1:8082，可通过 SERVER_PORT 调整。管理员默认用户名 admin；密码由首次启动环境指定，BCrypt 保存。公开注册只创建患者账号，医生账号由管理员创建。

## 已实现接口

统一 /api 前缀，成功/错误信封为 code、message、data、traceId。接口使用 Authorization: Bearer token，ID 返回字符串。客户端退出清除 token；未实现单条 JWT 的服务端撤销。

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | /health | 进程存活 |
| POST | /auth/register | 患者注册及同事务创建档案 |
| POST | /auth/login | 登录，返回 token/expiresIn/user |
| GET | /auth/me | 本人身份及 patientId/doctorId |
| GET/PUT | /patient/profile | 仅本人档案 |
| GET | /departments | 已登录用户查询启用科室 |
| GET | /doctor/profile | 医生查看本人信息 |
| GET/POST | /admin/departments | 科室分页与新增 |
| PUT | /admin/departments/{id} | 更新科室及停用关联检查 |
| GET/POST | /admin/doctors | 医生分页与账号一体创建 |
| PUT | /admin/doctors/{id} | 医生信息/启停/受控转科 |
| GET | /admin/users | 用户分页，不返回密码哈希 |
| PUT/PATCH | /admin/users/{id}/status | 启停账号，禁用立即影响旧 token |
| GET | /schedules | 今天起七天内可预约场次，分页及科室/医生/日期筛选 |
| GET | /schedules/{id} | 启用场次的实时余号、费用、截止及bookable |
| GET/POST | /admin/schedules | 管理日期范围内场次/新增 |
| PUT | /admin/schedules/{id} | 按已占用号源重算余号；保护历史身份和日期 |
| POST | /admin/schedules/{id}/close | 无活动预约时关闭 |
| GET | /doctor/schedules | 本人日期范围内场次 |
| POST/GET | /patient/registrations | 创建/幂等重放预约；本人分页 |
| GET | /patient/registrations/{id} | 本人预约历史快照与状态 |
| POST | /patient/registrations/{id}/cancel | 本人截止前取消WAITING；重复不再次返号 |
| GET | /patient/registrations/{id}/medical-record | 本人已提交病历 |
| GET | /doctor/registrations | 本人名下的日期/状态队列 |
| GET | /doctor/registrations/{id} | 本人名下预约及患者档案 |
| POST | /doctor/registrations/{id}/start | 当天开始接诊并创建草稿，重复不重复创建 |
| GET/PUT | /doctor/registrations/{id}/medical-record | 本人病历读取/草稿版本保存 |
| POST | /doctor/registrations/{id}/complete | 七段校验后病历提交与完成就诊同事务 |
| GET | /admin/health | MySQL、向量库分别检查 |
| GET | /admin/ai/config | 查看百炼配置状态，不返回密钥 |
| PUT | /admin/ai/api-key | 管理员保存 `{apiKey}` 到 MySQL，后续请求生效；响应只有脱敏配置状态 |
| POST | /admin/ai/capabilities/test | 实际调用 chat/stream/embedding/tools 并逐项返回结果 |
| POST/GET/PATCH | /admin/knowledge/documents、/admin/knowledge/documents/{id}/enabled、/admin/knowledge/documents/{id}/retry | 管理员上传、查看、重试、启停知识资料和片段 |
| GET | /admin/knowledge/status | 查看当前向量空间是否有可检索资料 |
| POST | /admin/knowledge/search | 在已启用资料中进行向量检索测试 |
| POST/GET | /patient/consultations | 本人问诊会话创建和分页 |
| GET | /patient/consultations/{id}、/patient/consultations/{id}/messages | 本人会话和消息 |
| POST | /patient/consultations/{id}/messages | SSE 流式问诊，返回 meta/delta/done/error |
| POST | /patient/consultations/{id}/cancel | 停止本人当前流式操作 |
| PUT | /patient/consultations/{id}/summary | 按版本保存与确认结构化症状摘要 |
| POST | /patient/triage-runs | 以确认摘要创建幂等导诊任务，返回 202 |
| GET/POST | /patient/triage-runs/{id}、/patient/triage-runs/{id}/cancel | 本人任务轮询与取消 |
| GET | /patient/triage-results/{id} | 本人导诊结果、证据及有效预约条件 |
| GET | /admin/agent-tools | 三个固定只读工具的名称与说明 |
| GET | /admin/agent-runs、/admin/agent-runs/{id} | 管理员分页查看导诊任务与公开步骤 |
| GET | /admin/statistics | 指定日期内预约/导诊状态、科室及每日预约量 |

注册示例：

```json
{"username":"patient_demo","password":"自行设置至少8位密码","displayName":"演示患者"}
```

患者更新档案时 KNOWN 必须有正文；UNKNOWN/NONE 清除旧正文，保留“未知”和“无”的区别。非法角色、年龄、状态由后端校验，不依赖 MySQL 5.7 的 CHECK。登录名规范化为小写 ASCII 字母、数字、点、下划线和短横线。

## 排班与普通就诊

所有业务日期/截止判断使用Asia/Shanghai，不依赖操作系统时区。普通可预约范围为今天到今天+6天。上午场次按08:00、下午按14:00开始；未填bookingDeadline时默认开始前30分钟，可提前设置但不能晚于开始时间。完整请求示例及冲突规则见[门诊接口说明](../docs/门诊接口说明.md)。

医生转科需先处理未来活动预约，并关闭未来开放排班，历史预约与排班所属保持原值。医生或账号停用立即阻止新预约；已存在预约/病历保留，基础历史读取不依赖场次是否仍开放。

预约锁本人档案并检查clientRequestId和请求哈希，同请求重放返回200与原记录，新建返回201。同键不同内容返回409，同患者同日同AM/PM最多一个WAITING/IN_PROGRESS预约；多人抢余号使用MySQL条件更新。前端不能指定患者身份、费用或名称快照。

取消和接诊使用与建单一致的父行锁顺序，避免MySQL5.7状态更新时外键检查的锁反转。取消仅截止前WAITING可执行；已取消重复返回原记录。病历草稿患者不可见，保存带version；提交校验七段正文且与预约COMPLETED同事务，提交后不可编辑。完整并发和故障证据见开发进度。

## 阿里云百炼

默认 baseUrl 使用用户指定的北京工作空间兼容接口，模型为 qwen-plus 和 text-embedding-v4，向量维度1024，可分别通过环境变量修改。

管理员保存 Key 后，以管理员调用 POST /api/admin/ai/capabilities/test。该操作会真实调用供应商，最多5次请求，可能产生费用；普通启动和自动化测试不自动调用百炼。四项独立验证，不以 chat 成功替代其他能力。此入口的 tools 使用 capability_echo，仅验证工具协议；业务 Agent 的三个工具另由业务流程与测试覆盖。

若工作空间接口不提供 Embedding 路由，须核对该工作空间开通情况与对应地域地址，不能伪造向量或结果。每次真实调用分别写入 ai_call_log，未知 Token 为 NULL；审计表不保存原始患者输入、密钥或 Prompt，业务问诊消息按本人归属另存。流式验证有总时限并忽略晚回调。

## 受控导诊与知识库

新增接口：`DELETE /api/patient/consultations/{id}`、`GET /api/patient/consultations/{id}/triage-runs`、`POST /api/admin/knowledge/rebuild`、`POST /api/admin/knowledge/documents/{id}/rebuild`。权限、活动锁、维护冲突与历史可读边界见[导诊接口说明](../docs/导诊接口说明.md)。

患者问诊、摘要确认、三个业务工具和异步导诊已实现首版。工具固定为 `searchMedicalKnowledge`、`findDepartments`、`findAvailableSchedules`，只读查询，参数严格校验，模型不能指定 patientId。模型选择工具，不能以固定代码顺序冒充 Agent。

每次导诊上限为 4 轮模型调用、6 次工具调用、60 秒总时限；知识工具最多 30 秒，SQL 工具与最终排班校验最多 3 秒，均受剩余总时限限制。外部调用不占用长数据库事务。支持取消、启动恢复、定期超时处理；晚回调不能覆盖终态。runId/stepId 关联调用审计，患者与管理员仅取得公开步骤。

导诊绑定摘要版本和输入快照。危险信号肯定时由规则生成急症提示；未知危险信号不允许直接进入普通低风险预约。引用必须来自本次实际检索，科室与排班需重新校验；无证据降级，不生成虚构来源。症状更新、摘要版本变化、过期等会阻止旧结果关联预约。详见[导诊接口说明](../docs/导诊接口说明.md)。

知识库支持 UTF-8 TXT/文本型 PDF、600 字符切片与 100 字符重叠、哈希和版本去重、异步批量 Embedding、pgvector 幂等写入、READY 完整性校验、失败重试、启停和来源回查。2026-10-14 已补齐全量维护重建、停用资料单独重建、会话逻辑删除与历史导诊分页恢复。全量重建保留启用选择/片段身份，以 REBUILD 标记持久化维护；全部目标及向量校验通过才恢复，失败/重启不提前开放。真实业务 Agent 的完整百炼、模型空间切换和跨库故障专项仍待验收。

统计按预约 visit_date、导诊 created_at 过滤，允许 1～93 天日期范围；结果来自真实 MySQL 聚合。当前首版已通过构建、登录和实际接口读取检查，完整管理员页面回归待继续。

## 验证

```powershell
mvn -B -ntp test
```

JWT 和模型适配测试无需数据库。设置 MYSQL_PASSWORD 后启用实际 MySQL 集成测试；另外设置 VECTOR_DB_PASSWORD 后启用实际 PostgreSQL 集成测试。认证和向量测试事务回滚，门诊并发测试真实提交后按明确创建的ID清理虚构数据。未提供对应环境变量时会显示跳过，不应把跳过称为数据库验证通过。

模型适配测试使用本机 HTTP 测试替身验证解析、流式和工具协议，不代表真实百炼实测。没有真实 API Key 时不声称四能力验证通过。

2026-10-13 执行 `mvn -B -ntp package`：16项测试通过，失败/错误/跳过均为0；MySQL与PostgreSQL集成测试均实际连接本机数据库。同日使用工作空间接口真实验证 chat、stream、Embedding 和 tool calling，四项均通过，产生5条成功调用记录。另以8083临时实例配置不可达向量库，确认服务能启动、管理员能登录、MySQL为UP/向量库为DOWN；未配置API Key时能力验证返回503/AI_NOT_CONFIGURED。临时实例已关闭。完整范围见[开发进度](../docs/开发进度.md)。

同日门诊阶段 `mvn -B -ntp package` 通过 28 项测试（含 12 项真实 MySQL 门诊测试），并完成实际 HTTP 普通门诊联调；测试虚构数据按创建 ID 清理。后续新增问诊、知识解析、知识任务、工具和导诊验证测试，既有 49 项全量与新增 8 项真实 MySQL 导诊集成测试分别通过，均无失败/错误/跳过；模型调用使用测试替身。最新上传前回归结果见[开发进度](../docs/开发进度.md)。

上传前对当前代码执行完整 `mvn test`：**57 项通过，失败 0、错误 0、跳过 0**，真实连接本地 MySQL 与 PostgreSQL。全部模型业务测试使用替身，本次未调用付费模型。

2026-10-14 当前代码已重新打包并启动于本机 8082，前端代理访问、管理员登录、双库健康和 AI 配置读取均成功；启动环境未配置 `DASHSCOPE_API_KEY`，但数据库凭据状态为已配置。上述启动检查未调用付费模型。四能力历史验证不代表完整业务 Agent 或医学效果验收。

## 续开发验证：2026-10-14

当前源码完整 `mvn test` **91 项全部通过，失败/错误/跳过均为 0**，真实连接本地 MySQL 和 PostgreSQL，模型调用为替身。当前 JAR 已打包启动于 8082，经前端代理管理员登录成功，双库健康均 UP；AI 配置从数据库读取，不调用付费模型。历史阶段数量保留为当时证据，当前范围见[开发进度](../docs/开发进度.md)。

本机已填入科室、医生、患者、排班、预约、病历和人工摘要演示数据。可通过 `scripts/seed-demo.mjs` 补充或只读复核；凭据只从环境或已有登录读取，演示对象 ID 清单保存在被 Git 忽略的本机目录。数量、账号和查看方式见[演示数据说明](../docs/演示数据说明.md)。

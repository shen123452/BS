# 糖尿病血糖时序监测与智能问答管理系统

本科毕业设计：血糖记录 → 分来源分析 → 异常提醒 → 医生处理 → 有依据的智能问答。

## 当前状态

2026-11-13：已导出 Figma 原生备份、18 页汇总 PDF 和离线页面预览，并根据原型实现新一轮前端界面。管理端采用左侧导航、三项工作台概览、待处理预警与处理抽屉、并排趋势/TIR/GMI、AGP 和每日统计；小程序更新登录、首页、录入/成功、记录、分析、问答与个人中心。管理端 22 项、患者端 32 项行为测试通过，管理端生产构建和微信 WXML/WXSS 编译通过。Web 使用隔离合成响应检查桌面与390px页面；小程序目前仅完成源文件布局预览，原生模拟器和真机验收仍待进行。以下日期记录为各轮历史验收，不代表本轮重新执行了后端、数据库或模型联调。

P0 主线及首批 P1 功能已完成本地隔离验收；阿里百炼真实问答联调通过，微信开发者工具/真机和生产部署仍待验收。患者端为**微信原生小程序**（WXML/WXSS/JS、CommonJS），不是 uni-app。

2026-10-24：后端 58 项、管理端 16 项、患者端基础回归通过；13 组临时 MySQL 8.4/Redis 7 集成场景通过。百炼 qwen-plus / text-embedding-v3 在本机 MySQL 5.7、Redis 5 环境完成真实回答、引用、幂等和临时账号清理验证。管理端生产构建与桌面/手机浏览器隔离交互通过。

2026-10-25：患者端将隐私确认合并到登录、智能问答改为独立底部模块；新增问答即开即用、历史会话与重试行为检查。针对开发者工具无可用 AppID 的情况，接口地址为本机时登录页会自动使用隔离开发账号，非本机环境仍严格使用真实微信登录。当前 32 项小程序行为测试及微信自带 WXML/WXSS 编译器检查通过。完整模拟器交互和真机仍待做。

2026-10-25：新增用药计划/每日服药状态、14 日合格 CGM 的 GMI 与 48 槽 AGP 风格分位图、医生人工回复、管理端 CSV 导出。用药、分析、医生绑定/解绑和导出在本机 MySQL 5.7/Redis 5 隔离环境完成 16 组验收。后端当前 80 项回归通过，患者端 32 项、管理端 18 项行为测试通过，管理端生产构建成功。工作台概览已加入代码并通过单元与前端行为检查；本轮后端改动尚未切换到运行中的 8083 服务。生产服务器未访问。

2026-10-30：管理端与患者端界面重构为浅色医院信息系统 / 患者记录风格，保留全部功能与接口；新增 Excel（XLSX）导出及 3 项后端导出测试。当前后端 83 项、患者端 32 项、管理端 19 项行为测试通过，管理端生产构建成功；微信 WXML/WXSS 编译器全部通过。

## 目录

```text
glucose-system/
├── backend/          Spring Boot 3 / MyBatis-Plus，默认端口 8083
├── admin-web/        Vue 3 / Element Plus / Vite，默认开发端口 5174
├── patient-miniapp/  微信原生小程序，原生 Canvas 2D
└── tests/            小程序行为检查、本地集成与浏览器验收脚本
```

空库初始化脚本为 `sql/schema.sql`。只向独立空库导入，已有库升级另行准备迁移方案。验收脚本生成的报告保存在 `glucose-system/tests/.artifacts/`。

## 本地运行

准备 JDK 17+、Maven、Node/npm、MySQL、Redis。实际验证用 JDK 21、Node 24；全流程隔离验收使用 MySQL 8.4 / Redis 7，百炼联调使用本机 MySQL 5.7 / Redis 5。后者已验证问答链路，完整业务兼容回归仍以隔离验收的版本为准。

### 后端

通过本地进程环境注入 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。凭据不放源码或提交到仓库。启动前确认指向独立开发库。

```powershell
cd glucose-system/backend
mvn -B -ntp package
java -jar target/glucose-backend.jar --spring.profiles.active=dev
```

接口文档：<http://127.0.0.1:8083/swagger-ui.html>。首次启动仅在 dev/test 允许本地默认管理员 `admin / admin123`；可用 `GLUCOSE_ADMIN_USERNAME`、`GLUCOSE_ADMIN_PASSWORD` 覆盖。正式部署必须显式启用 prod、设置 `JWT_SECRET` 和数据库凭据；首次部署还须设置管理员口令。prod 禁止缺少微信配置时回退 mock 登录。

可选外部服务配置：

- 微信：`WX_APPID`、`WX_SECRET`。仅 dev 允许缺配置时 mock，模拟 code 不是微信登录验收。
- 模型：`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`。
- 向量：`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL`、`EMBEDDING_DIM`。

无模型时问答按审核资料摘录或未覆盖降级。建立向量索引需要可用的 Embedding，引用包含原文摘录、来源和版本。

### 使用阿里百炼

先停止本项目当前运行的后端，避免 8083 端口占用及 Windows 下 jar 文件锁。进入 `glucose-system/backend` 后运行：

```powershell
.\start-bailian.ps1
```

脚本先构建，再以隐藏输入读取 API Key；也支持已有进程环境变量 `BAILIAN_API_KEY`。不要把密钥写入命令、源码或配置文件。默认使用北京地域兼容接口 `https://dashscope.aliyuncs.com/compatible-mode/v1`、聊天模型 `qwen-plus`、向量模型 `text-embedding-v3`（1024 维）。密钥只传入本地后端进程，结束后恢复原有模型环境变量；重启需重新提供密钥。数据库和 Redis 沿用本地配置。

切换向量模型或维度后，应在管理端对所需资料重建索引，旧模型向量不会混用。目前本地知识库共 9 篇资料，其中 5 篇已启用并完成索引：基础血糖概念、低血糖处理、糖尿病筛查结果、日常管理、CGM/SMBG 记录区别；原有 4 篇项目演示规则仍停用。新增内容为逐条核对 NIH/NIDDK 原文的中文摘要，急救边界补充核对 NHS，保留原文复审时间与网页快照；正式发布前仍需临床人员审核。

服务启动后，在项目根目录可复验：

```powershell
node glucose-system/tests/bailian-acceptance.mjs
```

该脚本会调用真实百炼 API，使用合成临时账号并在结束时注销；需要本机 Redis 客户端及本地管理员登录。首次准备该基础资料时才显式加 `--prepare-knowledge`。小程序重新编译后发送新问题，旧的未覆盖回答不会自动重写。

### 管理端

```powershell
cd glucose-system/admin-web
npm ci
npm run dev
```

打开 <http://127.0.0.1:5174>。开发服务将 `/api` 转发到后端 8083。生产用 `npm run build`；需由 Nginx 代理同源 API，并配置 SPA 路由回退。

### 微信原生患者端

微信开发者工具导入 `glucose-system/patient-miniapp`，设置测试 AppID；开发设置可关闭本地合法域名校验。AppSecret 仅存后端环境。真机需将 `utils/request.js` 的公开 `BASE_URL` 改为局域网可达地址或测试域名，并检查手机网络和微信合法域名。

患者端已通过 Node 页面行为测试及微信自带 WXML、WXSS 编译器检查；390px 微信开发者工具模拟器已查看登录首屏。当前开发者工具的自动化端口无法稳定启动新版项目，其他主要页面的模拟器联动及真机验收仍待做。

## 隔离合成演示数据

仅在独立开发库、dev 环境显式启用：

```powershell
java -jar target/glucose-backend.jar --spring.profiles.active=dev --app.demo.enabled=true
```

首次新建 `demo_patient`；已有同名账号或对应 openid 则跳过，不修改其数据。包含过去 14 个完整日的 4002 个 CGM 点、14 个 SMBG 点、一段缺测和历史异常，全部标记 `DEMO / GENERATOR`，与真实患者数据隔离。后续启动不会补写，合成日期不自动滚动。

仅在未配置真实微信且 dev 允许 mock 时，可由本地 API 客户端使用 `POST /api/app/auth/wx-login`、请求体 `{"code":"glucose-local-demo"}` 取得该演示账号临时令牌；令牌只保留在客户端内存。管理端可直接查看演示患者，普通小程序登录不会自动进入固定演示账号。

## 已实现功能

- 认证与权限：验证码、JWT、凭证失效、医生行级授权与即时解绑。
- 记录与导入：手工录入改删、分钟内重复检查、CSV 预检/错误明细/确认/撤销及幂等。
- 分析与提醒：完整日统计、一致版本快照、CGM 缺测断线与 SMBG 离散点、合格 CGM 的 GMI 与 30 分钟 AGP 分位图、预警事件合并/过期/处理版本冲突。
- 用药与随访：已有用药计划、每日服用/跳过/撤销、历史只读与停用留痕；医生可在绑定患者的问答会话中发送人工回复。
- 资料与隐私：生日清除、六类 SMBG 目标、隐私确认、注销及本人业务数据清理。
- 问答与知识库：会话事务、重复请求回放、失败恢复、当前索引过滤、引用快照、模型超时/限流和降级。
- 管理界面：患者/医生管理、记录趋势、GMI/AGP、预警、问答记录与人工回复、知识库、按权限导出 CSV / Excel；桌面侧栏导航、表格层次与窄屏适配。

管理端已有受权限限制的 CSV / Excel（XLSX）导出与工作台概览；订阅提醒和分块回答仍属于后续 P1/P2。真实微信、知识库临床内容审核、生产迁移及多实例容量验证仍待验收；百炼真实调用已通过本地限定场景验证。

## 验证

在 backend 和 admin-web 目录分别运行：

```powershell
# backend
mvn -B -ntp package
# admin-web
npm test
npm run build
```

在项目根目录运行：

```powershell
node --test glucose-system/tests/patient-miniapp.test.cjs
node glucose-system/tests/local-acceptance.mjs
node glucose-system/tests/runtime-contract.mjs
npm test --prefix glucose-system/tests
 npm test --prefix glucose-system/tests
```

第二条需要已启动的 Docker、`mysql:8.4` 和 `redis:7` 镜像及最新后端 jar。端口 13316、16386、18083 须空闲；脚本只创建自己的临时容器、导入文件和测试服务，随机凭据只在进程内，结束自动清理。不会连接已有业务库或调用真实微信/模型。

第三条只读取当前 8083 服务的接口定义，确认“当前时间”新增录入不要求客户端填写 measuredAt，同时编辑接口仍要求明确时间。修改源码或重新打包不会自动更新已经运行的 Java 进程；若出现 `measuredAt 不能为空`，应重启本项目后端后再执行此检查，不需要清库或修改小程序时间。

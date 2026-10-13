# 医院智能导诊系统

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

按已确认的毕业设计方案使用 Vue 3、Vite 6、Element Plus、Vue Router、Axios；后端为 Spring Boot、MyBatis-Plus。本地 MySQL 5.7 存业务，Docker PostgreSQL/pgvector 存向量。

## Users

患者预约并查看自己的就诊与已提交病历；医生查看本人排班、接诊名下患者并填写病历；管理员维护账号、科室、医生与排班。用户已确认三端普通门诊流程一起完成。

## Product Purpose

完成可运行、可操作、可答辩的医院智能导诊毕业设计。普通门诊、预问诊、知识库与受控业务 Agent 已有首版，当前保存开发成果，后续完成真实模型联调、剩余功能与交付验收。

## Operating Context

本地开发：IDEA 运行 HospitalApplication；前端单独运行 Vite。普通管理系统的账号权限与资源归属，由后端验证。业务时间为 Asia/Shanghai。

## Capabilities and Constraints

普通预约使用真实七日号源；无支付功能。患者公开注册，医生由管理员开户。七段病历手动保存，提交后只读。AI、知识库、症状确认、导诊结果和统计入口按已实现接口接入；缺少模型配置时显示实际不可用状态。密钥、数据库密码不进入前端或源码。

## Brand Commitments

项目既有《页面与交互设计》确定蓝白医疗管理界面、中文系统字体、Element Plus 组件和 SVG 图标。患者手机可用，管理表格在容器中滚动。

## Evidence on Hand

缩减版设计文档、11 张原型图、当前本机服务、57 项后端测试与 8 项前端测试。普通门诊有真实接口联调，AI 页面另有隔离浏览器验证；完整业务 Agent 的真实供应商验收仍待完成。初始业务数据为空，页面需给出真实空状态；演示数据不得伪装成业务数据。

## Product Principles

- 普通门诊每一步有清楚的下一步操作。
- 页面状态来自实际接口，失败保留用户输入。
- 未提供病史与明确无病史分别显示。
- 医生签署内容与患者历史预约保持可追溯。

## Accessibility & Inclusion

可见表单标签、键盘焦点、状态配文字、错误恢复入口、患者 375px 手机布局，尊重减少动态效果设置。

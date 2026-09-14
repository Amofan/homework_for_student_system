# 初中数学作业分析系统

这是一个面向初中数学教师的毕业设计项目。系统把班级名册、知识点题库、结构化答案导入、规则评分、大模型辅助建议、教师复核和知识点画像连成一条可追溯的教学工作流。

系统坚持一个明确边界：大模型只提供建议，教师确认后的结果才是正式成绩，也只有正式成绩会进入学情分析。

## 已实现功能

- 教师账号登录、JWT 鉴权和不同教师之间的数据隔离；
- 班级与学生名册管理；
- 七至九年级知识点体系；
- 单选题、填空题和解答题题库；
- 客观题可接受答案、解答题过程评分项校验；
- 作业创建和 `.xlsx` 答案批量导入；
- Excel 表头、公式单元格、未知学生、未知题目和重复答案校验；
- 客观题确定性规则评分；
- 解答题大模型任务、结构化结果校验、有限重试和人工兜底状态；
- 教师采纳、修改或驳回评分建议；
- 只统计已确认结果的知识点掌握度与高频错因；
- 中文教师工作台、复核工作区、分析图表和移动端布局；
- 不依赖 MySQL 或真实模型的本地演示模式。

## 技术栈

- 后端：Java 21、Spring Boot 4.1.1、Spring Security、Spring JDBC、Flyway、Apache POI；
- 数据库：MySQL 8.4 LTS，自动化测试和演示模式使用 H2；
- 前端：Vue 3、TypeScript、Vite、Element Plus、ECharts；
- AI：后端直调 Responses 风格接口，使用 JSON Schema 请求结构化结果；
- 验证：JUnit、MockMvc、Vitest、Playwright。

## 最快演示方式

演示模式使用内存数据库，关闭程序后数据会自动消失，不会修改本机 MySQL。

先启动后端：

```powershell
cd E:\0_all_private_project\homework_for_student_system\backend
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
```

再打开另一个终端启动前端：

```powershell
cd E:\0_all_private_project\homework_for_student_system\frontend
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`，使用以下演示账号：

- 账号：`demo`
- 密码：`MathDemo!2026`

演示模式已准备班级、学生、题库、作业、待复核 AI 建议和已确认画像数据。

## 正式本地运行

1. 复制 `.env.example` 为 `.env`，设置数据库密码、JWT 密钥和可选的大模型参数。
2. 根据 `.env` 启动 MySQL 8.4。
3. 把环境变量注入后端进程并启动 Spring Boot。
4. 启动前端，开发服务器会把 `/api` 代理到 `http://localhost:8080`。

`compose.yaml` 使用持久化卷 `homework_mysql_data`。不要执行 `docker compose down --volumes`，否则会删除本地业务数据。

## 大模型配置

本项目不使用 RAG、向量数据库或 OCR。启用大模型时配置：

```dotenv
MODEL_ENABLED=true
MODEL_BASE_URL=https://api.example.com
MODEL_API_PATH=/v1/responses
MODEL_NAME=模型名称
MODEL_API_KEY=服务端密钥
MODEL_TIMEOUT_SECONDS=30
```

密钥只在后端请求头中使用。发送给模型的内容仅包括匿名答案编号、题目、标准答案、学生作答、总分、评分项和允许的错因标签，不包含学生姓名、学号、班级名称或 JWT。

## Excel 答案格式

答案文件仅支持不超过 5 MiB 的 `.xlsx`，首个工作表表头顺序固定为：

```text
student_no | question_code | answer
```

一次最多导入 20,000 行。系统会先解析并校验整份文件；存在任何错误时整批不写入，并返回工作表、行号、字段和错误码。

## 验证命令

后端：

```powershell
mvn -f backend/pom.xml test
```

前端：

```powershell
npm --prefix frontend run test -- --run
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

浏览器验收需要先启动 `demo` 后端和前端：

```powershell
npm --prefix frontend run test:e2e
```

## 目录说明

```text
backend/                         Spring Boot 后端
frontend/                        Vue 教师端
docs/superpowers/specs/          中文需求与设计说明
docs/superpowers/plans/          中文实施与验收计划
compose.yaml                     持久化 MySQL 开发环境
compose.e2e.yaml                 一次性集成测试数据库配置
```

## 明确不做的范围

当前毕业设计不包含学生端、家长端、在线考试、拍照识别、RAG、向量数据库和多学科扩展。公式目前以安全纯文本保存，后端不渲染用户 HTML。

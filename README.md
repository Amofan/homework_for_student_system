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
- 基于已确认画像生成三级分层练习（基础巩固、方法纠错、综合提升）、教师确认并导出可打印 Word；
- 教师端题干、作答和评分说明的 KaTeX 公式渲染，以及 Word 导出的 Unicode 公式子集与降级题号提示；
- 论文离线评测脚本：按匿名样本计算评分误差、错因分类指标和预估节省时间；
- 中文教师工作台、复核工作区、分析图表和移动端布局；
- 不依赖 MySQL 或真实模型的本地演示模式。

## 技术栈

- 后端：Java 21、Spring Boot 4.1.1、Spring Security、Spring JDBC、Flyway、Apache POI；
- 数据库：MySQL 8.4 LTS，自动化测试和演示模式使用 H2；
- 前端：Vue 3、TypeScript、Vite、Element Plus（模板按需导入）、ECharts（只注册用到的图表）；
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

后端 MySQL 集成验证（需要本机 Docker 处于运行状态）：

```powershell
mvn -f backend/pom.xml verify -Pmysql-it
```

`mvn test` 只运行 Surefire 收集的 `*Test.java`，全程使用内存数据库，不要求本机安装 Docker。

加上 `-Pmysql-it` 后，Failsafe 会额外执行 `*IT.java`：每个测试类各自启动一个一次性
`mysql:8.4` 容器，Flyway 在其上执行 V1 至最新的全部迁移，测试结束由 Testcontainers
自动销毁容器。整个过程不复用 `compose.yaml` 的 `homework_mysql_data` 卷，也不会新建
任何数据卷。

该配置刻意让 `application-mysql-it.yml` 的 datasource 指向一个不存在的地址，真实连接
信息全部来自 `@ServiceConnection`。这样一旦容器注入失效，测试会立即连接失败，而不是
静默退回 H2 —— 否则“已在真实 MySQL 上验证过”这一结论就失去了意义。

排查失败时：

- 确认 Docker 正在运行：`docker version`；
- 只跑其中一个类：`mvn -f backend/pom.xml -Pmysql-it test-compile failsafe:integration-test failsafe:verify -Dit.test=MySqlMigrationIT`；
- 首次运行需要拉取 `mysql:8.4` 与 `testcontainers/ryuk` 镜像，耗时较长属正常；
- 报告位于 `backend/target/failsafe-reports/`，其中保留 Flyway 的原始错误信息。

前端：

```powershell
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

`npm run test` 只运行 `src/**/*.test.ts` 下的 Vitest 单元测试；`npm run test:e2e` 只运行 `e2e/**/*.spec.ts` 下的 Playwright 端到端测试。两者收集范围互不重叠，可以独立运行和独立报告。

浏览器验收需要先启动 `demo` 后端和前端：

```powershell
npm --prefix frontend run test:e2e
```

论文离线评测（只用 Python 标准库，不需要联网，也不访问数据库）：

```powershell
python -m unittest discover -s evaluation/tests -v
```

评测脚本的输入是一份冻结的匿名 CSV，同一份输入永远得到同一份结果，产出可直接引用的
JSON。用法、列定义和输出字段见 `evaluation/README.md`。

## 本轮验证与实验现状

2026-09-15 的验证快照：

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 后端单元测试 | `mvn -f backend/pom.xml test` | 124 通过，0 失败 |
| 后端 MySQL 集成 | `mvn -f backend/pom.xml verify -Pmysql-it` | 7 通过，0 失败 |
| 前端单元测试 | `npm --prefix frontend run test` | 60 通过（9 个文件） |
| 前端类型与构建 | `npm --prefix frontend run typecheck` / `run build` | 退出码 0 |
| 浏览器主流程 | `npm --prefix frontend run test:e2e` | 1 通过 |
| 离线评测脚本 | `python -m unittest discover -s evaluation/tests` | 26 通过 |

真实模型链路已在本机持久化 MySQL 上跑通：使用 OpenAI Responses 兼容接口调用
`deepseek-flash`，40 次评分调用全部成功，无重试、无人工兜底。评测导出、离线指标与
归档哈希都产出了实际文件。

**但这一轮是试运行，不是正式实验。** 两条理由，任一条都足以否定它的论文资格：

1. 样本量 40，低于《论文正式实验方案》规定的 100–300；
2. 更要紧的是，本轮"教师真值"列是为跑通链路**构造**的，不是合作教师的真实判定。

第 2 条决定了 `MAE`、错因 `F1`、教师采纳率这些数字目前是循环论证：拿我编的真值去
衡量模型的输出，得到的只能是"链路是否通畅"，而不是"模型与真实教师有多一致"。因此
本轮产出的数字**不得写进论文结果**。

试运行观测到的数字（仅存档，不作结论）：样本 40，MAE 0.45，完全一致率 0.70，
容差 1 分命中率 0.90，错因宏平均 F1 0.59，采纳 0.70 / 修改 0.20 / 驳回 0.10。
模型侧平均 8.87 秒、中位 3.75 秒，教师侧平均 71.36 秒、中位 62.00 秒。

正式实验仍待完成：取得合作教师授权使用的真实作答至少 100 条，由教师逐条复核形成
真值列，再按 `docs/experiments/2026-09-15-论文正式实验方案.md` 冻结的口径重跑。

匿名样本、指标 JSON 与运行日志一律存放在仓库外的
`E:\homework-analysis-experiment-archive\2026-09-main-study`（含 `checksums/sha256.txt`），
不进入 Git。

本轮仍未验证：跨域前后端分离部署下的下载响应头行为（当前只在同源代理下验证）、
并发教师复核的冲突处理、以及模型服务不可用时的端到端降级表现。

## 目录说明

```text
backend/                         Spring Boot 后端
frontend/                        Vue 教师端
evaluation/                      论文离线评测脚本、示例数据与测试
docs/superpowers/specs/          中文需求与设计说明
docs/superpowers/plans/          中文实施与验收计划
docs/experiments/                论文实验记录
compose.yaml                     持久化 MySQL 开发环境
compose.e2e.yaml                 一次性集成测试数据库配置
```

## 明确不做的范围

当前毕业设计不包含学生端、家长端、在线考试、拍照识别、RAG、向量数据库和多学科扩展。公式仍以安全纯文本保存，不保存用户 HTML；教师端只把 `$...$` / `$$...$$` 公式段交给 KaTeX 渲染。Word 导出使用明确的 Unicode 子集，无法转换的公式保留源码并标红，同时在下载时点名题号。

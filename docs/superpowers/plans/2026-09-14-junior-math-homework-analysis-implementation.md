# 初中数学作业分析系统实施与验收计划

## 一、项目目标

建设一个面向初中数学教师的作业分析系统，完成以下教学闭环：

```text
班级与知识点准备
        ↓
建立题库与评分规则
        ↓
创建作业并导入答案
        ↓
客观题规则评分 / 解答题 AI 建议
        ↓
教师复核并形成正式结果
        ↓
知识点画像与错因分析
```

系统不把大模型当作最终裁判。所有规则结果和 AI 建议都要经过教师确认，只有确认结果能够进入正式统计。

## 二、范围约束

- 目标用户仅为初中数学教师；
- 不开发学生端、家长端和在线考试；
- 不开发 OCR、拍照搜题、RAG 或向量数据库；
- 只通过后端访问大模型，前端不能接触模型密钥；
- 模型请求不能包含姓名、学号、班级名称和登录令牌；
- 持久化数据库为 MySQL 8.4，结构变更统一使用 Flyway；
- 演示与单元测试可以使用一次性 H2 内存数据库；
- 不采用微服务，后端保持按业务模块划分的单体应用；
- 未经用户明确授权，不提交、不推送、不部署，也不删除 Docker 数据卷。

## 三、技术基线

- Java 21、Maven、Spring Boot 4.1.1；
- Spring Security、JWT、Spring JDBC、Flyway；
- MySQL 8.4 LTS、H2 测试数据库；
- Apache POI 5.4.1；
- Vue 3.5.42、TypeScript 6.0.3、Vite 8.3.0；
- Element Plus 2.14.5、ECharts 6.1.0；
- JUnit、MockMvc、Vitest、Playwright 1.63.0。

## 四、模块与接口

### 1. 身份与数据隔离

主要接口：

- `POST /api/auth/login`：教师登录；
- `GET /api/auth/me`：读取当前教师；
- `GET/POST/PUT/DELETE /api/classes`：班级管理；
- `GET/POST/PUT/DELETE /api/students`：学生名册管理。

验收要求：

- 密码使用 BCrypt 哈希保存；
- JWT 有签发者、过期时间、用户和教师声明；
- 所有业务查询在服务端按教师编号限制；
- 无权访问与资源不存在统一返回未找到，防止枚举编号。

### 2. 知识点与题库

主要接口：

- `GET/POST /api/knowledge-points`；
- `GET/POST /api/questions`；
- `GET /api/questions/{questionId}`。

核心规则：

- 题型包括单选题、填空题和解答题；
- 每道题必须有一个属于当前教师且已启用的主知识点；
- 客观题必须至少配置一个可接受答案；
- 解答题必须配置有序评分项，分值之和必须等于题目总分；
- 题干和答案以 UTF-8 纯文本保存，可包含 LaTeX 分隔符，不保存用户 HTML。

### 3. 作业与 Excel 导入

主要接口：

- `GET/POST /api/assignments`；
- `GET /api/assignments/{assignmentId}`；
- `POST /api/assignments/{assignmentId}/answers/import`。

导入要求：

- 仅接受不超过 5 MiB 的 `.xlsx`；
- 表头固定为 `student_no,question_code,answer`；
- 最多 20,000 行答案；
- 拒绝公式单元格、空标识、未知学生、作业外题目和重复行；
- 先完成整表解析与校验，再开启数据库事务；
- 任一行错误时整批不写入，并返回行号、字段和稳定错误码。

### 4. 规则评分

主要接口：

- `POST /api/grading/assignments/{assignmentId}/run`。

核心规则：

- 客观题只做确定性答案比较；
- 归一化包括首尾空白、全角拉丁字符和等号周围空白；
- 不伪装成符号计算器，例如 `3.0` 与 `3` 默认不等价；
- 解答题不能进入规则评分器，只创建 AI 任务；
- 重复启动评分时跳过已有结果或已有任务。

### 5. AI 辅助评分

主要接口：

- `POST /api/grading/ai-tasks/assignments/{assignmentId}/process-one`。

模型边界：

- 业务层只依赖 `AiModelClient` 接口；
- 默认适配 Responses 风格接口；
- 使用 JSON Schema 请求结构化输出；
- 对建议总分、分项合计、评分项编号、分值上限、证据和错因标签进行二次校验；
- 网络或模型错误写入任务状态，按 30 秒、2 分钟和 10 分钟安排有限重试；
- 连续失败后进入 `MANUAL_REQUIRED`，不丢失学生答案；
- 日志和数据库不保存密钥或授权头。

### 6. 教师复核

主要接口：

- `GET /api/grading/assignments/{assignmentId}/review-queue`；
- `POST /api/grading/results/{resultId}/review`。

复核方式：

- `ACCEPT`：采纳建议；
- `MODIFY`：修改得分或错因，必须填写原因；
- `REJECT`：驳回建议并人工评分，必须填写得分与原因。

一次复核事务同时保存教师决定、正式得分、最终错因和审计记录。已复核结果不能重复确认。

### 7. 学情分析

主要接口：

- `GET /api/analytics/classes/{classId}/mastery`；
- `GET /api/analytics/assignments/{assignmentId}/errors`。

统计口径：

- 知识点掌握度 = 已确认得分之和 ÷ 对应题目总分之和；
- 仅按主知识点归集，防止一道题重复计分；
- 同时返回分子、分母、比例和答案数量；
- 高频错因只统计已确认且非正确的记录；
- 未复核的规则结果与 AI 建议一律不进入画像。

### 8. 教师端界面

页面包括：

- 登录页与路由守卫；
- 教学工作台；
- 班级与学生名册；
- 知识点体系；
- 数学题库与评分项编辑器；
- 作业创建、Excel 导入和启动评分；
- 教师复核工作区；
- ECharts 知识点画像和错因排名。

界面采用“教师方格批改簿”视觉主题。深蓝代表教学记录，蓝色代表知识标注，红色边线代表教师批改，绿色代表已确认状态。页面需要支持键盘焦点、空状态、错误状态、加载状态和小屏布局。

## 五、数据库迁移

- `V1__identity_and_classroom.sql`：教师、班级和学生；
- `V2__question_bank.sql`：知识点、题目和评分项；
- `V3__assignments_and_submissions.sql`：作业、提交和答案；
- `V4__grading.sql`：评分结果、教师复核和审计；
- `V5__ai_tasks.sql`：可重试 AI 任务。

迁移文件写入仓库不等于对持久化数据库执行迁移。正式 MySQL 的执行时机由启动环境决定。

## 六、验证计划

### 后端验证

```powershell
mvn -f backend/pom.xml test
```

覆盖范围：

- 应用启动和健康检查；
- JWT 签发、解析和过期校验；
- 登录错误码和当前教师；
- 跨教师班级、学生和资源访问；
- 学生名册增删改查；
- 题库评分项不变量；
- Excel 表头、公式和结构化答案解析；
- 错误工作簿整批不落库；
- 客观题答案归一化；
- AI 结构化结果约束；
- 教师复核后才进入知识点画像。

### 前端验证

```powershell
npm --prefix frontend run test -- --run
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

### 浏览器验收

先启动 `demo` 后端和前端，再执行：

```powershell
npm --prefix frontend run test:e2e
```

验收路径：教师登录 → 查看示例作业 → 进入 AI 建议复核 → 打开知识点画像。

### 最终检查

```powershell
git diff --check
git status --short
```

最终报告必须区分已实现、当前运行验证、未验证和剩余限制，不把计划能力写成完成事实。

## 七、当前实施状态

- [x] 工程骨架与安全配置；
- [x] 教师登录、班级隔离和学生名册；
- [x] 知识点、题库和过程评分项；
- [x] 作业与结构化答案导入；
- [x] 客观题规则评分；
- [x] 大模型适配器、结构化校验和任务状态；
- [x] 教师复核与审计；
- [x] 知识点掌握度和错因统计；
- [x] 中文教师端主要页面；
- [x] 内存数据库演示模式；
- [x] 浏览器主路径验收；
- [ ] 真实第三方模型联调；
- [ ] 持久化 MySQL 集成验证；
- [ ] 分层练习生成与 Word 导出；
- [ ] 毕业论文中的离线评价实验。

未完成项需要在获得真实模型密钥、数据库运行授权或进入下一阶段后继续，不影响当前演示模式和核心业务闭环。

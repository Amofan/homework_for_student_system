# 学生账号、整卷上传与 OCR 识别设计方案

## 1. 目标

在现有教师端初中数学作业分析系统中新增学生账号和整卷识别能力，使系统支持：

1. 教师创建或批量导入学生账号；
2. 教师上传空白试卷，并可选上传参考答案或解析卷；
3. 系统识别题号、题干、公式和题图，教师确认后生成正式题库和作业；
4. 学生登录后通过 PDF 或多张照片提交整份答卷；
5. 系统将学生答卷与教师确认的试卷模板对齐，按题目切分答案并识别文字与公式；
6. 教师确认识别结果后，复用现有规则评分、AI 建议、教师复核和学情分析链路；
7. 原始试卷、答案截图、OCR 输出和人工修改均可追溯。

系统继续遵守“AI 和 OCR 只产生候选结果，教师确认后才形成正式教学数据”的原则。

## 2. 已确认的产品决策

- 新增学生账号和学生端，不再采用教师代学生上传作为唯一入口；
- 学生账号由教师创建或随班级名单批量导入，不开放自由注册；
- 系统生成全局唯一登录名和一次性临时密码；
- 学生首次登录必须修改密码，教师可以重置但不能读取学生修改后的密码；
- 教师上传空白试卷，参考答案或解析卷为可选材料；
- 学生可以上传 PDF 或多张照片；
- 学生只能检查页数、顺序、方向和清晰度，不得修改 OCR 识别文本；
- OCR 文本、题目匹配和答案切分由教师校对；
- 批改开始前允许学生重新提交，新版本成为当前版本，旧版本保留；
- 批改开始后提交锁定，只有教师退回后才能再次提交；
- 图片保存在私有对象存储或受控文件目录，MySQL 只保存元数据和业务关系；
- 首版本地 OCR 不把整份学生答卷发送给第三方服务。

## 3. 当前系统基础与改造边界

当前系统是 Vue 3 + Spring Boot + MySQL 的模块化单体，已有教师认证、班级学生、题库、作业、Excel 答案导入、规则评分、AI 建议、教师复核、学情分析和分层练习。

现有正式数据仍由以下表承载：

- `question`、`question_knowledge_point`、`rubric_item`：正式题目；
- `assignment`、`assignment_question`：正式作业；
- `submission`、`student_answer`：正式学生提交与单题答案；
- `ai_grading_task`、`grading_result`、`teacher_review`：评分和复核；
- 现有学情统计继续只读取教师确认后的结果。

本次新增的文档、OCR 和上传模型位于正式业务表之前。未经确认的 OCR 数据不能直接写入 `question` 或 `student_answer`，避免识别错误污染现有评分与统计口径。

本次不重写既有规则评分、AI 任务、教师复核和学情分析算法；只扩展它们读取答案图片和来源追踪的能力。

## 4. 非目标

首版不实现：

- 学生自由注册、短信验证码、第三方社交登录或学校统一身份认证；
- 家长端、管理员端和校级组织体系；
- 在线答题或在线考试防作弊；
- 学生编辑 OCR 文本；
- 根据 OCR 结果自动发布正式题目、答案、评分项或成绩；
- 自动把几何图、函数图重绘成 SVG；
- 直接把整页答卷交给多模态模型并自动给出正式分数；
- 对任意试卷版式承诺完全自动切分；
- 首版跨教师共享题库和跨班级学生账号合并。

## 5. 角色、认证与授权

### 5.1 账号模型

`app_user.role` 支持 `TEACHER` 与 `STUDENT`。`student` 增加可空且唯一的 `user_id` 外键：旧学生数据迁移后仍可存在但尚未开通账号，教师执行“开通账号”后才建立关联。

系统生成的学生登录名使用不可推导其他学生身份的随机标识，不直接拼接姓名、身份证号或完整学号。临时密码只在创建或重置响应中返回一次，数据库只保存 BCrypt 哈希。

学生首次登录时账号状态为 `PASSWORD_CHANGE_REQUIRED`。完成改密后转为 `ACTIVE`。教师禁用或删除学生时，关联账号同步禁用，但保留历史提交与审计数据。

### 5.2 JWT 与当前主体

JWT 保留 `sub=userId` 和 `role`：

- 教师令牌带 `teacherId`；
- 学生令牌带 `studentId`；
- 不在学生令牌中暴露姓名、学号、班级名称；
- 后端使用统一 `CurrentActor` 解析主体，业务模块分别要求教师或学生角色；
- `/api/auth/me` 返回角色对应的资料结构，前端按角色跳转。

### 5.3 API 分区

- `/api/teacher/**`：教师业务；
- `/api/student/**`：学生业务；
- `/api/auth/**`：登录、当前用户和首次改密；
- 现有教师 API 在兼容期继续可用，但新功能全部使用角色明确的路径；
- 每个查询在 SQL 层通过教师归属或学生 ID 过滤，不能只依赖前端隐藏或内存比较。

## 6. 作业生命周期

作业状态扩展为：

```text
DRAFT → OCR_REVIEW → PUBLISHED → SUBMITTING
      → GRADING → REVIEWING → COMPLETED
```

- `DRAFT`：教师手工建题或尚未完成试卷上传；
- `OCR_REVIEW`：试卷 OCR 已产生候选题目，等待教师确认；
- `PUBLISHED`：作业已发布，学生可见；
- `SUBMITTING`：至少一名学生已提交，仍允许未锁定学生提交或重交；
- `GRADING`：教师已启动识别确认后的评分，相关学生提交锁定；
- `REVIEWING`：评分结果等待教师复核；
- `COMPLETED`：教师完成本次作业处理。

发布前必须存在至少一道已确认题目。学生只能访问自己所在班级已发布的作业。

## 7. 文件与图片存储

### 7.1 存储原则

文件内容不放入 MySQL BLOB，也不写入前端静态目录。后端通过 `FileStorage` 接口使用两种实现：

- 本地演示和自动化测试：项目目录之外的受控数据目录；
- 正式环境：私有 S3 兼容对象存储。

对象默认私有。浏览器通过后端鉴权流式读取，或获取短时有效的预签名读取地址。存储键完全由服务端生成，不使用用户文件名作为路径。

建议对象键：

```text
teachers/{teacherId}/documents/{documentId}/original/{fileId}.{ext}
teachers/{teacherId}/documents/{documentId}/pages/{pageNo}.png
teachers/{teacherId}/documents/{documentId}/regions/{regionId}.png
students/{studentId}/submissions/{versionId}/original/{fileId}.{ext}
students/{studentId}/submissions/{versionId}/pages/{pageNo}.png
students/{studentId}/submissions/{versionId}/answers/{answerRegionId}.png
```

### 7.2 文件类型

系统保存三层资产：

1. 原始文件：用户上传的 PDF、PNG 或 JPEG；
2. 页面文件：经过方向与透视校正的无损 PNG；
3. 派生文件：缩略图、题图、题目原始区域和学生答案区域。

几何图、坐标图、函数图和学生作图按 PNG 保存，不自动重绘。页面缩略图可使用 WebP，原始文件和 OCR 页面不能被缩略图替代。

### 7.3 核心表

`stored_file`：

- `id`、`teacher_id`、可空 `student_id`；
- `storage_key`、`original_name`、`mime_type`、`size_bytes`；
- `sha256`、可空 `width`、`height`；
- `status`、`created_at`、可空 `deleted_at`。

`document_upload`：

- 文档种类：`EXAM_PAPER`、`ANSWER_KEY`、`STUDENT_SUBMISSION`；
- 关联教师、作业、可空学生与提交版本；
- 原始文件、处理状态、页数、当前 OCR 版本和失败原因代码。

`document_page`：

- 文档、页码、页面文件、缩略图；
- 原始和校正后尺寸；
- 方向、变换矩阵、清晰度结果。

`document_region`：

- 页面和区域类型；
- 归一化 `x/y/width/height`，范围为 `0..1`；
- OCR 文本、LaTeX、置信度、原始 OCR JSON；
- 区域裁剪文件、人工确认状态和修订版本。

`question_asset`：

- 正式题目、文件、来源区域；
- 角色：`STEM_FIGURE`、`SOURCE_CROP`、`REFERENCE_IMAGE`；
- 排序、替代文本。

`student_answer_asset`：

- 正式 `student_answer`、文件、来源区域；
- 角色：`ANSWER_CROP`、`STUDENT_DRAWING`、`SOURCE_PAGE`；
- 排序。

图片关系使用外键和稳定 ID，不在题干内嵌 Base64 或私有图片标记。首版题图统一按顺序显示在题干文本之后。

## 8. OCR 服务架构

### 8.1 边界

新增独立 Python OCR 服务：

- Spring Boot 负责鉴权、上传、任务编排、状态、业务校验和正式入库；
- Python 服务负责文档预处理、版面检测、文字识别、公式识别和结构化区域输出；
- Python 服务无业务数据库凭据，不直接写 MySQL；
- Java 通过内部 HTTP 调用 `OcrProvider`；
- `OcrProvider` 隔离具体 OCR 引擎，首版默认适配本地 PaddleOCR；
- 单元测试使用固定响应的假实现，不下载或启动真实模型。

### 8.2 处理步骤

教师试卷：

```text
原始文件校验
→ PDF 页面渲染或图片解码
→ 方向、透视、阴影和清晰度处理
→ 版面与题号检测
→ 题干、选项、公式和题图识别
→ 跨页题目合并候选
→ 空白试卷与解析卷题号匹配
→ 教师校对
→ 事务性生成正式题目与作业
```

学生答卷：

```text
原始文件校验
→ 页码与方向检测
→ 与教师确认的试卷模板配准
→ 缺页、重复页、错序和错误试卷检查
→ 按题目切分答案区域
→ 手写文字和公式识别
→ 低置信度与跨页答案标记
→ 教师校对
→ 事务性生成正式单题答案
```

OCR 置信度统一归一化到 `0..1`。低于配置阈值的文本或无法映射到唯一题目的区域进入人工队列；阈值默认 `0.85`，只能影响提示，不能绕过教师确认。

### 8.3 OCR 状态

```text
PENDING → RUNNING → NEEDS_REVIEW → CONFIRMED
                  ↘ RETRY_WAIT
                  ↘ FAILED
```

- 临时网络、进程不可用或资源繁忙进入有限重试；
- 文件损坏、类型非法、页数超限和无法解码直接失败；
- 低置信度属于 `NEEDS_REVIEW`，不是失败；
- 每次任务记录 OCR 引擎、模型版本、参数版本、耗时和原始结构化输出；
- 同一文件哈希、文档类型和处理版本组成幂等键；
- 部分页面失败时整份文档不能确认，禁止产生半份题库或半份答卷。

## 9. 教师整卷导入

教师创建作业时可以选择“手工选题”或“上传整卷”。整卷模式支持一个必传的空白试卷和一个可选的参考答案或解析卷。

OCR 校对工作台必须提供：

- 页面缩略图与原图定位；
- 题目边界拖动、拆分、合并和排序；
- 题号、题型、分值、题干、选项和公式修改；
- 题图裁剪、删除和排序；
- 空白卷与解析卷题号匹配；
- 标准答案、可接受答案、知识点、难度和评分项编辑；
- 未完成字段和低置信度提示；
- 全部校验通过后一次性确认。

确认过程使用数据库事务：创建题目、知识点关系、评分项、题图关系、作业和题目顺序必须全部成功或全部回滚。原始文档与 OCR 候选数据不因确认失败而删除，教师可以修正后重试。

## 10. 学生上传与提交版本

### 10.1 学生端

学生端提供：

- 首次改密；
- 待提交、处理中、已提交、被退回和已完成的作业列表；
- PDF 或多图片选择；
- 页面预览、排序、旋转、删除和清晰度提示；
- 上传进度、失败重试和最终提交确认；
- 当前提交状态与历史版本摘要；
- 教师发布后的最终成绩和反馈。

学生看不到标准答案、评分项、其他学生数据、OCR 原始输出、AI 提示词和模型原始响应。

### 10.2 提交版本

新增 `submission_version`：

- `id`、`assignment_id`、`student_id`、递增 `version_no`；
- `status`：`DRAFT`、`UPLOADED`、`PROCESSING`、`NEEDS_REVIEW`、`CONFIRMED`、`LOCKED`、`SUPERSEDED`、`RETURNED`、`FAILED`；
- `is_current`、`submitted_at`、可空 `locked_at`、`returned_at`；
- 退回原因和创建时间。

同一学生同一作业只有一个 `is_current=true` 的版本。上传草稿不算正式提交；学生点击确认后才设置 `submitted_at` 并启动 OCR。

批改开始前，新版本确认上传后，旧当前版本变为 `SUPERSEDED`。批改开始后当前版本进入 `LOCKED`，学生不能再上传新版本。教师退回后当前版本变为 `RETURNED`，系统才允许创建下一版本。

新版本不会复用旧版本的 OCR、`student_answer`、AI 任务或评分结果。旧版本的数据只读保留，用于审计。

### 10.3 审计

`submission_audit` 记录：

- 上传开始、上传完成、提交确认；
- 自动 OCR 状态变化；
- 教师修改 OCR 文本或题目映射；
- 学生重交、旧版本失效；
- 批改锁定、教师退回和退回原因；
- 操作者角色、操作者 ID、时间和结构化详情。

不在审计日志中复制完整学生答案或文件访问地址。

## 11. 与现有评分链路的集成

教师确认学生答卷 OCR 后，系统才创建或替换当前版本对应的 `submission` 与 `student_answer`。每个答案保存 OCR 文本，同时通过 `student_answer_asset` 关联原始答案图。

- 单选题、填空题继续走 `ObjectiveRuleGrader`；
- 解答题继续创建 `ai_grading_task`；
- 首版 AI 评分仍以教师确认后的文本为主，不要求现有模型支持图片；
- 教师复核页增加答案原图、OCR 文本、所在页和区域定位；
- 评分任务一旦创建，提交版本进入锁定；
- 教师退回时，未确认评分任务和结果标记为失效，不物理删除审计记录；
- 新提交版本必须重新执行 OCR 和评分；
- 只有现有教师复核完成后的正式结果进入学情统计。

## 12. 前端结构

保留一个 Vue 应用，共用登录页和基础组件，按角色进入不同布局：

```text
/login
├─ TEACHER → /teacher/*
└─ STUDENT → /student/*
```

教师端新增页面：

- 学生账号管理；
- 试卷上传；
- 试卷 OCR 校对；
- 作业发布与提交进度；
- 答卷 OCR 校对；
- 提交版本与退回记录。

学生端新增页面：

- 首次修改密码；
- 我的作业；
- 答卷上传与页面整理；
- 提交详情与历史版本；
- 最终结果和教师反馈。

路由守卫必须同时检查认证状态、角色和首次改密状态。学生移动端上传页面优先适配手机竖屏，并支持相机或相册多选。

## 13. API 轮廓

教师账号管理：

- `POST /api/teacher/classes/{classId}/student-accounts/provision`
- `POST /api/teacher/classes/{classId}/student-accounts/import`
- `POST /api/teacher/students/{studentId}/password/reset`
- `PATCH /api/teacher/students/{studentId}/account-status`

教师试卷：

- `POST /api/teacher/paper-imports`
- `POST /api/teacher/paper-imports/{id}/files`
- `POST /api/teacher/paper-imports/{id}/process`
- `GET /api/teacher/paper-imports/{id}`
- `PATCH /api/teacher/paper-imports/{id}/regions/{regionId}`
- `POST /api/teacher/paper-imports/{id}/confirm`
- `POST /api/teacher/assignments/{assignmentId}/publish`

教师提交管理：

- `GET /api/teacher/assignments/{assignmentId}/submission-status`
- `GET /api/teacher/submission-versions/{versionId}`
- `PATCH /api/teacher/submission-versions/{versionId}/answers/{regionId}`
- `POST /api/teacher/submission-versions/{versionId}/confirm`
- `POST /api/teacher/submission-versions/{versionId}/return`

学生端：

- `GET /api/student/assignments`
- `GET /api/student/assignments/{assignmentId}`
- `POST /api/student/assignments/{assignmentId}/submission-versions`
- `POST /api/student/submission-versions/{versionId}/files`
- `PATCH /api/student/submission-versions/{versionId}/pages/order`
- `PATCH /api/student/submission-versions/{versionId}/pages/{pageId}/rotation`
- `DELETE /api/student/submission-versions/{versionId}/pages/{pageId}`
- `POST /api/student/submission-versions/{versionId}/submit`
- `GET /api/student/submission-versions/{versionId}`

文件上传首版经过 Spring Boot，以便集中执行大小、文件签名和归属校验。大文件直传预签名 URL 只作为后续优化，不属于首版。

## 14. 上传安全与隐私

- 允许列表仅包含 PDF、PNG 和 JPEG；
- 同时检查扩展名、声明 MIME、文件签名和实际解码结果；
- 限制单文件大小、整次提交大小、页面数、单页像素数和 PDF 渲染后总像素；
- 拒绝 SVG、HTML、脚本和可执行内容；
- 原始文件名只作为显示元数据，清理控制字符和路径字符；
- 服务端生成不可预测存储键，禁止覆盖已有对象；
- 上传完成后计算 SHA-256 并验证存储完整性；
- 所有文件读取都执行教师归属或学生本人校验；
- 预签名地址有效期短，不写入日志或数据库业务字段；
- 密码、令牌、学生答案正文和对象存储凭据不得进入日志；
- OCR 服务运行在内部网络，不暴露公网接口；
- 向外部 AI 发送内容时继续去除姓名、学号和班级名称；
- 删除学生或作业不立即物理删除审计所需文件，按统一保留策略异步清理。

默认保留策略为：未提交草稿和失败上传 7 天，标记删除的派生文件保留 30 天宽限期，已完成作业的过期提交版本保留 180 天；到期后删除对象内容但保留审计行、文件哈希和删除时间。部署方可以延长保留期，但不能让数据库审计行先于对象删除成功而消失。

## 15. 异常与恢复

- 文件上传中断：保留未完成上传会话，允许同一学生继续上传；
- 页面模糊或反光：质量检测返回 `BLOCKING` 时禁止最终提交，返回 `WARNING` 时要求学生逐页确认后才允许提交；
- 缺页、重复页、错序：进入学生页面整理或教师校对，不自动猜测并继续评分；
- 错误试卷：模板匹配失败，标记人工处理；
- OCR 服务不可用：任务进入有限重试，不产生正式答案；
- OCR 部分页面失败：整份文档保持未确认；
- 题目无法唯一匹配：保存为未分配区域，必须由教师指定；
- 教师确认冲突：使用版本号乐观锁，后提交者收到冲突提示并重新加载；
- 存储写入成功但数据库事务失败：登记待清理对象，由补偿任务删除；
- 数据库成功但派生图生成失败：原始文件保留，可重新执行派生任务；
- 新版本重交：旧版本不可变，不覆盖文件和 OCR 结果。

## 16. 测试设计

### 16.1 后端

- 教师创建、批量开通、禁用和重置学生账号；
- 首次改密状态和两类 JWT 声明；
- 学生访问教师 API、其他学生作业和文件的越权测试；
- 文件扩展名、MIME、签名、大小、页数、像素和损坏文件校验；
- 本地存储适配器的写入、读取、哈希和补偿清理；
- OCR 幂等、重试、部分失败和状态流转；
- 教师试卷确认的全事务回滚；
- 学生提交版本唯一当前约束、重交、锁定和退回；
- OCR 确认前禁止评分；
- 新版本不会复用旧版本评分；
- MySQL 8.4 上的全部 Flyway 迁移与约束。

### 16.2 OCR 回归

建立不含真实身份的固定样本集，覆盖：

- 手机拍照、扫描件、旋转、透视、阴影和反光；
- 单栏、双栏、跨页题；
- 中文、数字、印刷公式和手写公式；
- 几何图、坐标图、函数图和学生作图；
- 空白答案、超出答题区域和写在试卷背面的答案。

每次 OCR 模型或参数变化记录题目切分结果、公式识别结果、页面匹配结果、低置信度数量和人工修正时间。模型升级必须使用相同冻结样本回归，不以单个演示页面代替。

### 16.3 前端

- 角色跳转和首次改密守卫；
- 教师账号批量导入错误反馈；
- 试卷 OCR 校对的拆分、合并、排序、题图和公式编辑；
- 学生移动端多页上传、排序、旋转、删除、进度和失败重试；
- 提交锁定、退回和历史版本状态；
- 加载、空数据、错误、禁用和冲突状态；
- 答案图片的键盘访问、替代文本和响应式显示。

### 16.4 端到端验收

完整执行：

```text
教师批量创建学生账号
→ 学生首次登录改密
→ 教师上传含公式和题图的试卷及解析卷
→ 教师校对并发布作业
→ 学生手机上传多页答卷
→ 学生在批改前重新提交
→ 教师启动处理并锁定提交
→ 教师退回一次，学生再次提交
→ OCR 识别并由教师校对
→ 规则/AI 评分
→ 教师复核
→ 学情分析
→ 学生查看最终反馈
```

## 17. 分阶段交付

### 阶段 1：学生身份与权限

完成账号开通、批量导入、临时密码、首次改密、角色 JWT、API 隔离、学生布局和作业列表。

### 阶段 2：私有文件存储

完成文件元数据、文档与页面模型、本地存储适配器、受保护读取、上传校验和补偿清理。

### 阶段 3：教师整卷导入

完成 OCR 服务骨架、教师试卷上传、任务状态、页面与区域输出、OCR 校对工作台、题图关联和事务性生成题库与作业。

### 阶段 4：作业发布与学生上传

完成发布状态、学生多页上传、页面整理、提交确认、版本历史、重交、锁定和教师退回。

### 阶段 5：答卷模板对齐与答案识别

完成模板配准、页码检查、答案区域切分、手写文字与公式识别、低置信度队列和教师确认入库。

### 阶段 6：评分与复核集成

完成现有评分链路接入、答案图片展示、版本失效处理、重新评分和最终结果发布。

### 阶段 7：质量与上线准备

完成真实脱敏样本回归、并发与容量测试、备份恢复、文件保留策略、移动端验收和部署文档。

## 18. 完成标准

该功能只有同时满足以下条件才算完成：

1. 教师可安全开通学生账号，学生首次登录必须改密；
2. 学生无法访问教师或其他学生的数据；
3. 教师整卷可以提取文字、公式和题图并经人工确认生成作业；
4. 学生可以从手机提交完整答卷并按规则重交；
5. 原始文件、页面、题图和答案截图均可追溯且不公开；
6. 未确认 OCR 数据不能进入评分；
7. 新提交版本不会错误复用旧评分；
8. 复核页可以同时查看确认文本与答案原图；
9. 现有教师确认和学情统计口径不变；
10. 后端、前端、MySQL 集成、OCR 固定样本和浏览器主流程验证全部通过。

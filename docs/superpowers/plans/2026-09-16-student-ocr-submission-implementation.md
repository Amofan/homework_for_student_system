# 学生账号、整卷上传与 OCR 识别实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有教师端系统中增加学生账号、教师整卷建题、学生整卷提交、OCR 校对、题图/答案图存储，并把确认后的答案接入现有评分与学情链路。

**Architecture:** 保留 Vue 3 + Spring Boot 模块化单体作为业务核心，新增私有文件存储抽象和独立 Python OCR 服务。所有 OCR 结果先进入候选文档域，教师确认后才事务性写入现有 `question`、`submission`、`student_answer`；原始文件和版本历史保持不可变、可审计。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring Security、Spring JDBC、Flyway、MySQL 8.4/H2、Apache PDFBox 3.0.8、AWS SDK for Java S3 2.54.17、Vue 3、TypeScript、Element Plus、FastAPI 0.141.1、Pydantic 2.13.5、PaddleOCR 3.7.0、JUnit/MockMvc/Testcontainers、Vitest、Playwright、pytest。

**Spec:** `docs/superpowers/specs/2026-09-16-ocr-student-submission-design.md`

## Global Constraints

- OCR 和 AI 只能产生候选数据；只有教师确认的数据能写入正式题库、答案、成绩和学情统计。
- 学生账号只能由教师创建或批量开通；不提供自由注册。
- 学生可以检查页面但不能编辑 OCR 文本。
- 批改前可重交并保留历史；批改开始后锁定，教师退回后才允许新版本。
- 文件必须私有；MySQL 只保存元数据和关系，不保存 Base64 或 BLOB。
- 首版题图按 `sort_order` 显示在题干后，不在 `question.content` 中插入私有图片标记。
- 本地 OCR 不把整份学生答卷发送给第三方；外部 AI 不接收姓名、学号、班级名或永久文件 URL。
- 保留现有 `/api/**` 教师接口的兼容行为；新增能力使用 `/api/teacher/**` 与 `/api/student/**`。
- 手工编辑使用 `apply_patch`；不得覆盖用户已有改动。
- 不自动提交、推送、创建 PR 或部署。每项任务末尾执行 review checkpoint；只有用户另行授权时才创建提交。
- 每个任务先运行列出的定向测试，再在任务收口时运行全量相关测试。

---

## 文件结构总览

### 数据库迁移

- `backend/src/main/resources/db/migration/V8__student_accounts_and_assignment_lifecycle.sql`：学生账号、首次改密、作业发布字段。
- `backend/src/main/resources/db/migration/V9__document_storage_and_ocr.sql`：文件、文档、页面、区域和 OCR 任务。
- `backend/src/main/resources/db/migration/V10__question_assets.sql`：题图和题目来源区域。
- `backend/src/main/resources/db/migration/V11__submission_versions_and_answer_assets.sql`：提交版本、审计、答案资产与评分失效来源。

### 后端新增包

- `auth/`：角色感知的当前主体和首次改密。
- `classroom/account/`：学生账号开通、重置和批量导出凭据。
- `document/`：文件校验、存储、页面渲染、鉴权读取。
- `ocr/`：OCR 契约、HTTP 适配器、任务编排和状态机。
- `paper/`：教师试卷导入、候选题目校对和确认。
- `submission/`：学生提交版本、页面整理、退回和 OCR 确认。

### OCR 服务

- `ocr-service/pyproject.toml`：锁定 Python 依赖和测试配置。
- `ocr-service/app/main.py`：只暴露内部健康检查与识别接口。
- `ocr-service/app/contracts.py`：请求、页面、区域和响应模型。
- `ocr-service/app/pipeline.py`：预处理、版面、文字和公式识别编排。
- `ocr-service/tests/`：契约、坐标归一化、错误映射和固定样本测试。

### 前端新增

- `frontend/src/layouts/TeacherLayout.vue`、`StudentLayout.vue`：角色布局。
- `frontend/src/views/student/`：首次改密、作业列表、答卷上传、提交详情。
- `frontend/src/views/teacher/PaperImportView.vue`：整卷上传。
- `frontend/src/views/teacher/PaperReviewView.vue`：题目 OCR 校对。
- `frontend/src/views/teacher/SubmissionReviewView.vue`：学生答案 OCR 校对。
- `frontend/src/components/document/`：页面缩略图、区域框、私有图片和上传队列。

---

### Task 1: 学生账号数据库与角色感知认证

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__student_accounts_and_assignment_lifecycle.sql`
- Create: `backend/src/main/java/com/homework/analysis/auth/AccountStatus.java`
- Create: `backend/src/main/java/com/homework/analysis/auth/CurrentActor.java`
- Create: `backend/src/main/java/com/homework/analysis/auth/CurrentStudent.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthPrincipal.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthRepository.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthService.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthController.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/JwtService.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/SecurityConfiguration.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/CurrentTeacher.java`
- Modify: `backend/src/test/java/com/homework/analysis/testing/TestDatabaseCleaner.java`
- Modify: `backend/src/test/java/com/homework/analysis/auth/JwtServiceTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/auth/AuthApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/integration/H2MigrationTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/integration/MySqlMigrationIT.java`

**Interfaces:**
- Produces: `AuthPrincipal(long userId, Long teacherId, Long studentId, String role, boolean passwordChangeRequired, Instant expiresAt)`.
- Produces: `CurrentActor.requireTeacher(Authentication)` and `CurrentActor.requireStudent(Authentication)`.
- Preserves: existing teacher login, `/api/auth/me`, and existing tests using `JwtService.issue(long,long,String)` through a deprecated compatibility overload until all call sites migrate.

- [ ] **Step 1: Write migration and MySQL assertions first**

Add V8 with these exact changes:

```sql
alter table app_user add column account_status varchar(32) not null default 'ACTIVE';
alter table student add column user_id bigint;
alter table student add constraint uk_student_user unique (user_id);
alter table student add constraint fk_student_user foreign key (user_id) references app_user(id);
alter table assignment add column published_at timestamp(3);
alter table assignment add column due_at timestamp(3);
alter table assignment add column version int not null default 0;
create index idx_student_user on student(user_id);
create index idx_assignment_class_status on assignment(class_id, status);
```

Add `H2MigrationTest` to assert the new columns and relationships under the default test profile. Extend `MySqlMigrationIT.CORE_TABLES` and add MySQL information-schema assertions for the V8 columns and foreign key.

- [ ] **Step 2: Run migration tests and verify the expected red state**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=H2MigrationTest test
```

Expected before implementation: test fails because V8 or its columns are missing. After adding V8, H2 migration coverage passes; MySQL-specific assertions run in the final task with `-Pmysql-it`.

- [ ] **Step 3: Add role-aware JWT tests**

Test these claims explicitly:

```java
var teacher = jwtService.parse(jwtService.issueTeacher(1, 11));
assertThat(teacher.teacherId()).isEqualTo(11L);
assertThat(teacher.studentId()).isNull();

var student = jwtService.parse(jwtService.issueStudent(2, 1001, true));
assertThat(student.studentId()).isEqualTo(1001L);
assertThat(student.teacherId()).isNull();
assertThat(student.passwordChangeRequired()).isTrue();
```

Add an API test proving a `STUDENT` account can log in and `/api/auth/me` returns `role`, `studentId`, `displayName`, `passwordChangeRequired`, while a teacher response still returns `teacherId` and `schoolName`.

- [ ] **Step 4: Implement the unified authentication model**

Use these public methods:

```java
public String issueTeacher(long userId, long teacherId);
public String issueStudent(long userId, long studentId, boolean passwordChangeRequired);
public AuthPrincipal parse(String token);
```

`AuthRepository.findLoginAccount` must `left join teacher` and `left join student`, reject an account that matches neither role profile, and return exactly one subject ID matching the role. `AuthService.MeResponse` becomes a single stable record with nullable role-specific fields.

- [ ] **Step 5: Enforce roles at both filter and service boundaries**

Configure the JWT converter to map `role=TEACHER` to `ROLE_TEACHER` and `role=STUDENT` to `ROLE_STUDENT`. Add request rules:

```java
.requestMatchers("/api/teacher/**").hasRole("TEACHER")
.requestMatchers("/api/student/**").hasRole("STUDENT")
.requestMatchers("/api/**").authenticated()
```

`CurrentTeacher.id(...)` delegates to `CurrentActor.requireTeacher(...)`; `CurrentStudent.id(...)` delegates to `requireStudent(...)`.

- [ ] **Step 6: Verify task 1**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=JwtServiceTest,AuthApiTest,H2MigrationTest test
git diff --check
```

Expected: teacher compatibility remains green; student claims and role-denial cases pass; no whitespace errors.

---

### Task 2: 学生账号开通、临时密码、重置与批量凭据

**Files:**
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/StudentAccountService.java`
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/StudentAccountController.java`
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/StudentAccountRepository.java`
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/ProvisionedStudentAccount.java`
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/PasswordChangeCommand.java`
- Create: `backend/src/main/java/com/homework/analysis/classroom/account/StudentCredentialWorkbook.java`
- Modify: `backend/src/main/java/com/homework/analysis/classroom/StudentView.java`
- Modify: `backend/src/main/java/com/homework/analysis/classroom/StudentRepository.java`
- Modify: `backend/src/main/java/com/homework/analysis/classroom/StudentService.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthController.java`
- Modify: `backend/src/main/java/com/homework/analysis/auth/AuthService.java`
- Create: `backend/src/test/java/com/homework/analysis/classroom/account/StudentAccountApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/classroom/account/StudentCredentialWorkbookTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/classroom/StudentApiTest.java`

**Interfaces:**
- Produces: `POST /api/teacher/classes/{classId}/student-accounts/provision` with `studentIds`.
- Produces: `POST /api/teacher/students/{studentId}/password/reset`.
- Produces: `POST /api/auth/password/change` for the authenticated actor.
- Produces: an XLSX credential export containing `student_no`, `name`, `username`, `temporary_password` only for accounts created/reset in that request.

- [ ] **Step 1: Add failing account lifecycle tests**

Cover these invariants:

```text
teacher A can provision only students in teacher A's class
provisioning an already active account is idempotent and does not reveal a password
reset returns a new temporary password once and sets PASSWORD_CHANGE_REQUIRED
first password change invalidates the temporary password and returns a fresh token
student soft delete disables app_user
no endpoint can retrieve the stored password hash or the last plaintext password
```

- [ ] **Step 2: Implement credential generation and persistence**

Use `SecureRandom` and a 16-character alphabet excluding ambiguous characters. Generate usernames as `stu_` plus 12 lowercase base32 characters; retry on `uk_app_user_username` collision. Persist `BCryptPasswordEncoder.encode(...)`, `role='STUDENT'`, `account_status='PASSWORD_CHANGE_REQUIRED'`, then set `student.user_id` in one transaction.

`ProvisionedStudentAccount` must be:

```java
public record ProvisionedStudentAccount(
    long studentId, String studentNo, String name,
    String username, String temporaryPassword, boolean newlyProvisioned) {}
```

For an existing account, `temporaryPassword` is null and `newlyProvisioned=false`.

- [ ] **Step 3: Implement first-password change**

Require current password plus a new password of 10–72 characters containing at least three of uppercase, lowercase, digit and symbol. After update, set `account_status='ACTIVE'` and issue a new role-correct token. Reject reuse of the current password.

- [ ] **Step 4: Implement one-time credential XLSX export**

`StudentCredentialWorkbook.write(List<ProvisionedStudentAccount>)` returns `byte[]`. Only include rows whose plaintext password exists in the current request. Add `Cache-Control: no-store` and a filename `student-credentials-{classId}.xlsx`.

- [ ] **Step 5: Verify task 2**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=StudentAccountApiTest,StudentCredentialWorkbookTest,StudentApiTest,AuthApiTest test
git diff --check
```

Expected: account ownership, one-time secret behavior, reset and first-change flows all pass.

---

### Task 3: 前端双角色认证、教师学生账号界面与学生外壳

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/src/router/index.ts`
- Rename/Modify: `frontend/src/layouts/AppLayout.vue` → `frontend/src/layouts/TeacherLayout.vue`
- Create: `frontend/src/layouts/StudentLayout.vue`
- Modify: `frontend/src/views/LoginView.vue`
- Create: `frontend/src/views/student/ChangePasswordView.vue`
- Create: `frontend/src/views/student/StudentAssignmentsView.vue`
- Modify: `frontend/src/views/ClassroomView.vue`
- Modify: `frontend/src/styles.css`
- Create: `frontend/src/stores/auth.test.ts`
- Create: `frontend/src/views/student/ChangePasswordView.test.ts`
- Create: `frontend/src/views/ClassroomView.test.ts`
- Modify: `frontend/src/App.test.ts`

**Interfaces:**
- Consumes: task 1 `MeResponse` and task 2 provisioning/reset endpoints.
- Produces: role-aware router guards and stable frontend types `TeacherProfile | StudentProfile`.

- [ ] **Step 1: Write failing auth-store and route tests**

Assert:

```text
TEACHER login redirects to /teacher
STUDENT active login redirects to /student/assignments
STUDENT passwordChangeRequired redirects only to /student/change-password
student navigation to /teacher is rejected
teacher navigation to /student is rejected
logout clears token and profile
```

- [ ] **Step 2: Refactor profile types without unsafe casts**

Use a discriminated union:

```ts
export type Profile =
  | { role: 'TEACHER'; userId: number; teacherId: number; displayName: string; schoolName?: string; passwordChangeRequired: false }
  | { role: 'STUDENT'; userId: number; studentId: number; displayName: string; passwordChangeRequired: boolean }
```

The router guard must branch on `profile.role`, not decode JWT client-side.

- [ ] **Step 3: Add student layout and first-change page**

Student navigation contains only“我的作业”“提交记录”“退出登录”. The first-change page submits current and new passwords, stores the returned token, reloads `/auth/me`, then routes to `/student/assignments`.

- [ ] **Step 4: Add teacher account actions to the roster**

Extend each row with account state and actions“开通账号”“重置密码”. Provisioning shows plaintext credentials once in a modal with an explicit download button; closing the modal clears the plaintext state. Never persist credentials in `localStorage`.

- [ ] **Step 5: Verify task 3**

Run:

```powershell
npm --prefix frontend run test -- src/stores/auth.test.ts src/views/student/ChangePasswordView.test.ts src/views/ClassroomView.test.ts src/App.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Expected: role redirects and first-change gate pass; production build remains successful.

---

### Task 4: 作业发布生命周期与学生作业列表

**Files:**
- Create: `backend/src/main/java/com/homework/analysis/assignment/AssignmentStatus.java`
- Create: `backend/src/main/java/com/homework/analysis/assignment/PublishAssignmentCommand.java`
- Create: `backend/src/main/java/com/homework/analysis/assignment/StudentAssignmentView.java`
- Modify: `backend/src/main/java/com/homework/analysis/assignment/AssignmentService.java`
- Modify: `backend/src/main/java/com/homework/analysis/assignment/AssignmentController.java`
- Create: `backend/src/main/java/com/homework/analysis/assignment/StudentAssignmentController.java`
- Create: `backend/src/test/java/com/homework/analysis/assignment/AssignmentPublicationApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/assignment/StudentAssignmentApiTest.java`
- Modify: `frontend/src/views/AssignmentView.vue`
- Modify: `frontend/src/views/student/StudentAssignmentsView.vue`
- Create: `frontend/src/views/student/StudentAssignmentsView.test.ts`

**Interfaces:**
- Produces: `POST /api/teacher/assignments/{id}/publish` with optional ISO-8601 `dueAt`.
- Produces: `GET /api/student/assignments` and `GET /api/student/assignments/{id}` without answers/rubrics.

- [ ] **Step 1: Write publication and privacy tests**

Prove draft assignments are invisible to students; published assignments are visible only to students in the assigned class; student JSON does not contain `standardAnswer`, `acceptedAnswers`, `rubricItems`, other student IDs or teacher-only status counts.

- [ ] **Step 2: Implement explicit status transitions**

Allowed transitions:

```java
DRAFT -> PUBLISHED
OCR_REVIEW -> PUBLISHED
PUBLISHED -> SUBMITTING
SUBMITTING -> GRADING
GRADING -> REVIEWING
REVIEWING -> COMPLETED
```

Reject all backwards transitions except the submission-level teacher return flow implemented later. Use `assignment.version` optimistic locking for publish and phase changes.

- [ ] **Step 3: Implement student-safe projections**

`StudentAssignmentView` contains only ID, title, due date, status, question count, submission summary and teacher display name. Do not reuse `QuestionView` because it includes answers and rubrics.

- [ ] **Step 4: Wire teacher publish and student list UI**

Add due-date selection and publish confirmation to `AssignmentView.vue`. Student cards show due date, current state and primary action; before task 9 the upload action may route to a disabled placeholder page with “答卷上传将在文件模块完成后启用”.

- [ ] **Step 5: Verify task 4**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=AssignmentPublicationApiTest,StudentAssignmentApiTest test
npm --prefix frontend run test -- src/views/AssignmentView.test.ts src/views/student/StudentAssignmentsView.test.ts
npm --prefix frontend run typecheck
```

---

### Task 5: 私有文件存储、文件校验与受保护读取

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__document_storage_and_ocr.sql`
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-demo.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/java/com/homework/analysis/document/StorageProperties.java`
- Create: `backend/src/main/java/com/homework/analysis/document/FileStorage.java`
- Create: `backend/src/main/java/com/homework/analysis/document/LocalFileStorage.java`
- Create: `backend/src/main/java/com/homework/analysis/document/S3FileStorage.java`
- Create: `backend/src/main/java/com/homework/analysis/document/StoredFileService.java`
- Create: `backend/src/main/java/com/homework/analysis/document/UploadFileInspector.java`
- Create: `backend/src/main/java/com/homework/analysis/document/PdfPageRenderer.java`
- Create: `backend/src/main/java/com/homework/analysis/document/DocumentFileController.java`
- Create: `backend/src/main/java/com/homework/analysis/document/DocumentCleanupJob.java`
- Create: `backend/src/test/java/com/homework/analysis/document/LocalFileStorageTest.java`
- Create: `backend/src/test/java/com/homework/analysis/document/UploadFileInspectorTest.java`
- Create: `backend/src/test/java/com/homework/analysis/document/DocumentFileApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/document/DocumentCleanupJobTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/testing/TestDatabaseCleaner.java`
- Modify: `backend/src/test/java/com/homework/analysis/integration/H2MigrationTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/integration/MySqlMigrationIT.java`

**Interfaces:**
- Produces: `StoredObject put(StorageWrite request)`, `InputStream open(String key)`, `void delete(String key)`.
- Produces: authenticated `GET /api/teacher/files/{id}` and `GET /api/student/files/{id}`.
- Dependencies: PDFBox 3.0.8 and AWS S3 SDK 2.54.17.

- [ ] **Step 1: Add V9 migration and migration assertions**

Create `stored_file`, `document_upload`, `document_page`, `document_region`, `ocr_task` exactly as defined in the design. Store region coordinates as four `decimal(8,7)` columns, not a vendor-specific geometry type. Store OCR JSON as `text` so H2 and MySQL share the migration.

Required constraints include unique `storage_key`, unique `(document_id,page_no)`, unique OCR idempotency key, confidence range checks at service level, and indexes on owner/status/task claim columns.

- [ ] **Step 2: Add failing storage and inspection tests**

Fixtures must cover valid PDF/PNG/JPEG, renamed executable, SVG, truncated PNG, PDF with excessive pages, decompression/pixel bomb metadata, path traversal filename and SHA-256 verification.

- [ ] **Step 3: Implement `FileStorage` adapters**

Use:

```java
public interface FileStorage {
    StoredObject put(StorageWrite write) throws IOException;
    InputStream open(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;
}
```

`LocalFileStorage` resolves and normalizes every key under `app.storage.local-root` and rejects escape. `S3FileStorage` uses a private bucket, explicit endpoint/region/path-style settings and never sets public ACLs.

- [ ] **Step 4: Implement deterministic upload limits**

Defaults:

```yaml
app:
  storage:
    max-file-bytes: 26214400
    max-submission-bytes: 104857600
    max-pages: 40
    max-pixels-per-page: 40000000
```

Validate magic bytes before storage; render PDF pages at 200 DPI for preview/OCR; write normalized pages as PNG. Original files remain unchanged.

- [ ] **Step 5: Implement ownership-safe reads and compensation**

File reads must join through `document_upload` to a teacher-owned assignment or the authenticated student's submission version. If storage succeeds and metadata transaction fails, immediately attempt delete and record a sanitized WARN if cleanup fails.

- [ ] **Step 6: Implement deterministic retention cleanup**

`DocumentCleanupJob` runs from a configurable schedule and processes bounded batches. Delete abandoned `DRAFT`/`FAILED` uploads after 7 days, derived files marked deleted after a 30-day grace period, and superseded submission files 180 days after the assignment reaches `COMPLETED`. Keep database audit rows and SHA-256 metadata after object deletion. A storage delete failure leaves the row pending for the next run and never deletes metadata first.

- [ ] **Step 7: Verify task 5**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=LocalFileStorageTest,UploadFileInspectorTest,DocumentFileApiTest,DocumentCleanupJobTest,H2MigrationTest test
git diff --check
```

---

### Task 6: OCR 服务契约、固定假实现与 Java 任务编排

**Files:**
- Create: `ocr-service/pyproject.toml`
- Create: `ocr-service/app/__init__.py`
- Create: `ocr-service/app/main.py`
- Create: `ocr-service/app/contracts.py`
- Create: `ocr-service/app/pipeline.py`
- Create: `ocr-service/tests/test_contract.py`
- Create: `ocr-service/tests/test_pipeline.py`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrProvider.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrRequest.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrResult.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrRegion.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/HttpOcrProvider.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrProperties.java`
- Create: `backend/src/main/java/com/homework/analysis/ocr/OcrTaskWorker.java`
- Create: `backend/src/test/java/com/homework/analysis/ocr/HttpOcrProviderTest.java`
- Create: `backend/src/test/java/com/homework/analysis/ocr/OcrTaskWorkerTest.java`
- Modify: `compose.yaml`

**Interfaces:**
- Python `POST /v1/documents/analyze` consumes local/internal object references plus document kind.
- Produces versioned JSON with page dimensions, normalized regions, type, text, LaTeX, confidence and crop instructions.
- Java worker persists output but never writes formal questions or answers.

- [ ] **Step 1: Freeze the JSON contract in Python and Java tests**

Contract shape:

```json
{
  "schemaVersion": "v1",
  "engine": "paddleocr",
  "modelVersion": "3.7.0",
  "pages": [{
    "pageNo": 1,
    "width": 2480,
    "height": 3508,
    "regions": [{
      "externalId": "p1-r1",
      "type": "QUESTION_TEXT",
      "x": 0.10, "y": 0.20, "width": 0.70, "height": 0.08,
      "text": "如图，AB∥CD…",
      "latex": null,
      "confidence": 0.93
    }]
  }]
}
```

Reject unknown schema versions, NaN, coordinates outside `0..1`, empty page arrays and duplicate `externalId` values.

- [ ] **Step 2: Build the stateless FastAPI shell**

Pin `fastapi==0.141.1`, `pydantic==2.13.5`, `paddleocr==3.7.0`; keep Paddle runtime selection in deployment extras so CPU and GPU images do not conflict. Expose `/health` and `/v1/documents/analyze`; require an internal bearer token from environment for analyze.

- [ ] **Step 3: Implement the first pipeline adapter**

Pipeline stages are explicit functions:

```python
def preprocess_page(image: NDArray) -> PreprocessedPage: ...
def detect_layout(page: PreprocessedPage) -> list[DetectedRegion]: ...
def recognize_region(page: PreprocessedPage, region: DetectedRegion) -> RecognizedRegion: ...
def normalize_result(pages: list[RecognizedPage]) -> AnalyzeResponse: ...
```

The first production adapter uses PaddleOCR for layout/text/formula candidates. Tests inject a fake model and verify ordering, coordinate normalization and error mapping without model downloads.

- [ ] **Step 4: Implement Java HTTP adapter and DB worker**

Reuse the existing bounded-retry pattern from `AiGradingTaskWorker`, but use separate statuses and configuration. Persist `engine`, `model_version`, `raw_result`, retry count, next attempt and sanitized error code. Do not log input images or OCR text.

- [ ] **Step 5: Verify task 6**

Run:

```powershell
python -m pytest ocr-service/tests -q
mvn -f backend/pom.xml -Dtest=HttpOcrProviderTest,OcrTaskWorkerTest test
```

Expected: contract tests pass with fake OCR; no real model or network is required.

---

### Task 7: 教师整卷导入、候选题目与题图确认后端

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__question_assets.sql`
- Create: `backend/src/main/java/com/homework/analysis/paper/PaperImportController.java`
- Create: `backend/src/main/java/com/homework/analysis/paper/PaperImportService.java`
- Create: `backend/src/main/java/com/homework/analysis/paper/PaperImportView.java`
- Create: `backend/src/main/java/com/homework/analysis/paper/PaperQuestionCandidate.java`
- Create: `backend/src/main/java/com/homework/analysis/paper/PaperQuestionCommand.java`
- Create: `backend/src/main/java/com/homework/analysis/paper/QuestionAssetView.java`
- Modify: `backend/src/main/java/com/homework/analysis/question/QuestionView.java`
- Modify: `backend/src/main/java/com/homework/analysis/question/QuestionService.java`
- Create: `backend/src/test/java/com/homework/analysis/paper/PaperImportApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/paper/PaperConfirmationTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/question/QuestionApiTest.java`

**Interfaces:**
- Produces: create/upload/process/get/update-region/confirm endpoints from the spec.
- Produces: `QuestionView.assets()` without changing text/KaTeX semantics.

- [ ] **Step 1: Add V10 and failing transaction tests**

V10 creates `paper_question_candidate` and `question_asset`. Candidate rows store order, detected code/type/content/score, source page/region, confidence and optimistic `version`. `question_asset` uses `(question_id,sort_order)` uniqueness and roles `STEM_FIGURE`, `SOURCE_CROP`, `REFERENCE_IMAGE` validated in Java enums.

Test that one invalid solution rubric causes zero `question`, `assignment_question`, `rubric_item` and `question_asset` rows to be created.

- [ ] **Step 2: Implement paper import creation and upload**

An import accepts one `EXAM_PAPER` and zero or one `ANSWER_KEY`. Ownership is derived from the authenticated teacher. `process` creates exactly one idempotent OCR task per document/version.

- [ ] **Step 3: Materialize OCR candidates without formal writes**

Convert region groups into editable candidates; preserve every source region. Confidence below `0.85`, missing question code, unmatched answer-key question, overlapping question boxes or ambiguous cross-page grouping adds a structured warning.

- [ ] **Step 4: Implement candidate edits and optimistic locking**

`PATCH` commands include `version`; update succeeds only with `where version=:expectedVersion`, increments version, and returns `409 OCR_REVIEW_CONFLICT` on stale edits.

- [ ] **Step 5: Implement all-or-nothing confirmation**

Reuse `QuestionService` validation for question semantics. In one transaction create questions, knowledge links, rubrics, assets, assignment and ordered links, then set paper import and OCR tasks to `CONFIRMED`. A second confirm call returns the same assignment rather than duplicating rows.

- [ ] **Step 6: Verify task 7**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=PaperImportApiTest,PaperConfirmationTest,QuestionApiTest test
```

---

### Task 8: 教师整卷上传与 OCR 校对界面

**Files:**
- Create: `frontend/src/views/teacher/PaperImportView.vue`
- Create: `frontend/src/views/teacher/PaperReviewView.vue`
- Create: `frontend/src/components/document/DocumentUploadQueue.vue`
- Create: `frontend/src/components/document/PageThumbnailStrip.vue`
- Create: `frontend/src/components/document/RegionOverlay.vue`
- Create: `frontend/src/components/document/PrivateImage.vue`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/TeacherLayout.vue`
- Modify: `frontend/src/views/QuestionView.vue`
- Modify: `frontend/src/styles.css`
- Create: `frontend/src/views/teacher/PaperImportView.test.ts`
- Create: `frontend/src/views/teacher/PaperReviewView.test.ts`
- Modify: `frontend/src/math/MathText.test.ts`

**Interfaces:**
- Consumes: task 7 paper APIs and protected file API.
- Produces: teacher review commands with candidate version numbers.

- [ ] **Step 1: Write component tests for all UI states**

Cover empty, uploading, processing, retryable failure, fatal failure, needs review, stale edit conflict and confirmed states. Verify low-confidence regions have a visible text label in addition to color.

- [ ] **Step 2: Build upload flow**

Require one empty paper; allow one optional answer-key file. Validate client-side extension and configured size for fast feedback while treating server validation as authoritative. Poll task state with exponential backoff capped at 10 seconds and stop on unmount.

- [ ] **Step 3: Build review workspace**

Use three panes at desktop width: page thumbnails, page with overlays, candidate form. On mobile, use tabs rather than shrinking the overlay canvas. Implement split, merge, reorder, crop selection and candidate fields from the spec.

- [ ] **Step 4: Render confirmed question assets**

`QuestionView.vue` renders `item.assets.filter(role==='STEM_FIGURE')` after `MathText`, preserves aspect ratio, sets `loading="lazy"`, and uses authenticated blob loading through `PrivateImage` instead of public URLs.

- [ ] **Step 5: Verify task 8**

Run:

```powershell
npm --prefix frontend run test -- src/views/teacher/PaperImportView.test.ts src/views/teacher/PaperReviewView.test.ts src/math/MathText.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

---

### Task 9: 学生提交版本、页面整理、锁定与退回后端

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__submission_versions_and_answer_assets.sql`
- Create: `backend/src/main/java/com/homework/analysis/submission/SubmissionVersionStatus.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/SubmissionVersionService.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/StudentSubmissionController.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/TeacherSubmissionController.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/SubmissionVersionView.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/SubmissionAuditAction.java`
- Create: `backend/src/test/java/com/homework/analysis/submission/StudentSubmissionApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/submission/SubmissionVersionConcurrencyTest.java`
- Create: `backend/src/test/java/com/homework/analysis/submission/TeacherReturnSubmissionApiTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/testing/TestDatabaseCleaner.java`
- Modify: `backend/src/test/java/com/homework/analysis/integration/H2MigrationTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/integration/MySqlMigrationIT.java`

**Interfaces:**
- Produces: student create/files/order/rotation/delete/submit/get APIs.
- Produces: teacher status/detail/return APIs.
- Produces: one current immutable submitted version per student and assignment.

- [x] **Step 1: Add V11 and database invariants**

Create `submission_version`, `submission_page`, `submission_audit`, `student_answer_asset`. Add nullable `submission_version_id` to `submission` and `invalidated_at`/`invalidated_reason` to `grading_result` and `ai_grading_task`.

MySQL cannot express a portable partial unique index for `is_current=true`; enforce current-version uniqueness by locking the student's assignment row in the service transaction and add unique `(assignment_id,student_id,version_no)`.

- [x] **Step 2: Write state and concurrency tests**

Test two concurrent create-version requests produce sequential version numbers and one current version; a second submit request is idempotent; `GRADING` rejects student writes; return reopens a new version but does not mutate the old one.

- [x] **Step 3: Implement draft upload and page organization**

Only `DRAFT` and `UPLOADED` versions allow file/page changes. Page reorder receives the complete ordered page ID list exactly once each. Rotation accepts `0`, `90`, `180`, `270` only and creates a new derived page image without overwriting the original.

- [x] **Step 4: Implement submit and supersede transaction**

Submission checks at least one page, all files valid, no severe quality failure, assignment published and not past a hard-closed state. It marks the prior current version `SUPERSEDED`, marks the new one current and `PROCESSING`, writes audit events and creates OCR tasks atomically.

- [x] **Step 5: Implement grading lock and teacher return**

Starting grading sets the current confirmed version `LOCKED`. Return requires a nonblank reason, invalidates unconfirmed AI/grading rows for that version, sets `RETURNED`, writes audit, and allows the next version. Confirmed teacher reviews cannot be silently invalidated; return then responds `409 SUBMISSION_ALREADY_FINALIZED`.

- [x] **Step 6: Verify task 9**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=StudentSubmissionApiTest,SubmissionVersionConcurrencyTest,TeacherReturnSubmissionApiTest,H2MigrationTest test
```

H2 侧全绿（19 + 5 + 13 + 19）。

MySQL 侧已在本机 Docker 上真跑过：

```powershell
mvn -f backend/pom.xml verify -Pmysql-it
```

结果：surefire 312 全绿，failsafe 14 全绿（`MySqlMigrationIT` 10 个 + `MySqlCoreWorkflowIT` 4 个）。
`MySqlMigrationIT` 新增 4 个 V11 用例：四张新表与列/类型/默认值/可空性、
`uk_submission_page_source` 与 V9 补上的 `fk_document_upload_version`、
`uk_ocr_task_document_version` 的列序确实是（document_id, processing_version）、
以及旋转角度的 CHECK 在 MySQL 上真的会拦住 45 度。

**第一次跑就抓到一处只在真库上才出现的问题**：`alter table grading_result add column invalidated_reason`
报 `Row size too large ... 65535`。根因是 MySQL 的行宽硬限制按声明宽度全额计算 varchar（utf8mb4 下 4n 字节），
这张表原有的 `score_details(8000)` + `teacher_explanation(4000)` + `student_feedback(4000)` 已占 64000 字节，
再塞一个 varchar(500) 就超了；H2 没有这条限制，所以内存库一路全绿。
已在这两列之前把三个长文本列改成 `text`（只按约 12 字节指针计入，容量反而更大，读写仍是 String），
并在 `MySqlMigrationIT` 里钉住类型与 `score_details` 的 NOT NULL。

---

### Task 10: 学生手机端答卷上传与版本历史

**Files:**
- Create: `frontend/src/views/student/SubmissionUploadView.vue`
- Create: `frontend/src/views/student/SubmissionDetailView.vue`
- Create: `frontend/src/components/document/StudentPageOrganizer.vue`
- Create: `frontend/src/components/document/UploadProgressItem.vue`
- Create: `frontend/src/components/document/uploadQueue.ts`（上传队列的类型与文案，见偏差 5）
- Create: `frontend/src/api/studentSubmission.ts`（学生答卷接口，见偏差 1）
- Modify: `frontend/src/views/student/StudentAssignmentsView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/styles.css`
- Create: `frontend/src/views/student/SubmissionUploadView.test.ts`
- Create: `frontend/src/views/student/SubmissionDetailView.test.ts`
- Create: `frontend/src/api/studentSubmission.test.ts`（替代原计划的 `client.test.ts`）

**Interfaces:**
- Consumes: task 9 student submission APIs.
- Produces: ordered page payloads and final submit confirmation; never exposes OCR edit controls.

- [x] **Step 1: Write mobile upload behavior tests**

Cover multiple image selection, PDF selection, duplicate filename handling, upload progress, one-file retry, page reorder, rotation, deletion, severe-quality block, warning confirmation, submit idempotency, locked state and returned state.

- [x] **Step 2: Implement resumable UI state from the server**

Do not persist file bytes in `localStorage`. After refresh, reload the draft/version and already uploaded page thumbnails from the server. Keep only transient client selection before upload.

- [x] **Step 3: Implement accessible page organizer**

Support pointer drag and keyboard move-up/move-down buttons. Each page has page number, file name, quality status, rotate and delete controls with explicit labels. The submit dialog states that OCR text cannot be edited by the student.

- [x] **Step 4: Implement version history and return flow**

Show version number, submitted time, current/superseded/returned status and teacher return reason. Returned versions expose “重新提交” which creates the next version; locked versions show no upload action.

- [x] **Step 5: Verify task 10**

Run:

```powershell
npm --prefix frontend run test -- src/views/student/SubmissionUploadView.test.ts src/views/student/SubmissionDetailView.test.ts src/api/client.test.ts
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

实际执行（全量而不是只跑这三个文件）：

```powershell
npm --prefix frontend run test        # 20 个文件 170 个用例全绿
npm --prefix frontend run typecheck   # EXIT=0
npm --prefix frontend run build       # 成功，两个新页面各自分包
```

**与原计划的偏差（都已落地，记在这里而不是回头改计划书的历史）：**

1. **学生答卷接口单独成模块**，落在 `frontend/src/api/studentSubmission.ts` + `studentSubmission.test.ts`（12 个用例），
   而不是塞进 `api/client.ts` / `client.test.ts`。理由与 `paperImport.ts` 当初一样：一个流程的契约
   （状态标签表、页数/字节上限、上传与整理动作）集中在一处，`client.ts` 才不会长成什么都往里放的口袋。
2. **`PageView` 增加了 `fileName`**（`SubmissionVersionService.PAGE_COLUMNS` 用子查询取原始上传文件名）。
   手机上同批照片的名字只差一两个字符，页面编号才是身份；没有这个字段，整理台上一行只能显示"第 3 页"。
   实现时特意取 `document_upload.original_file_id` 的名字而不是 `page_file_id` 的，
   否则旋转一次页面就会显示成 `page-3-r90.png`；`StudentSubmissionApiTest` 里钉住了这一点。
3. **`StateTone` 从 `paperImport.ts` 移到 `api/types.ts`**：整卷导入与学生答卷共用的界面词汇只有一个定义处。
4. **新增 `api/client.ts:getMyAssignment`**：答卷页只拿到作业 id，标题要从 `/student/assignments/{id}` 取，
   不从列表页用路由参数捎过来（学生从聊天软件点开链接时没有那个列表）。
5. **新增 `components/document/uploadQueue.ts`**：上传队列的类型、字节格式化与状态文案，
   供上传页与 `UploadProgressItem` 共用。
6. **提交确认不用 `el-dialog`**，改成页面内的一块卡片。弹窗会盖住它正在解释的那几页
   （"第 2 页可能拍得不清楚"），而学生需要一边看页面一边决定；顺带也让测试不必处理 Teleport。
7. **`StudentAssignmentsView` 的入口从禁用按钮改成 `RouterLink`**：点了就是换一页，用 `<a>` 才能长按复制、
   新标签页打开；已提交的作业文案变成"查看答卷"，否则学生会以为上一次没交上去。

---

### Task 11: 答卷模板配准、教师 OCR 校对与正式答案入库

**Files:**
- Create: `backend/src/main/java/com/homework/analysis/submission/AnswerExtractionService.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/AnswerCandidateView.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/AnswerCorrectionCommand.java`
- Create: `backend/src/main/java/com/homework/analysis/submission/SubmissionConfirmationService.java`
- Modify: `backend/src/main/java/com/homework/analysis/ocr/OcrRequest.java`
- Modify: `backend/src/main/java/com/homework/analysis/ocr/OcrResult.java`
- Modify: `backend/src/main/java/com/homework/analysis/submission/TeacherSubmissionController.java`
- Create: `backend/src/test/java/com/homework/analysis/submission/AnswerExtractionServiceTest.java`
- Create: `backend/src/test/java/com/homework/analysis/submission/SubmissionConfirmationTest.java`
- Create: `frontend/src/views/teacher/SubmissionReviewView.vue`
- Create: `frontend/src/views/teacher/SubmissionReviewView.test.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/TeacherLayout.vue`

**Interfaces:**
- Produces: page/template match result, answer candidates and teacher correction API.
- Produces: confirmed `submission`/`student_answer` plus `student_answer_asset` in one transaction.

- [ ] **Step 1: Freeze extraction failure cases in tests**

Test missing page, duplicate page, wrong template, ambiguous question, cross-page answer, blank answer, answer outside expected region and low-confidence formula. Every unresolvable case must remain visible as a warning or unassigned region; no region may be silently discarded.

- [ ] **Step 2: Extend OCR request with template geometry**

Send page anchors and confirmed question boxes using normalized coordinates. OCR returns `templatePageNo`, `alignmentConfidence`, optional transform matrix and answer regions with candidate `questionCode`.

- [ ] **Step 3: Implement answer candidate persistence and edits**

Teachers may change question mapping and OCR text; every edit uses optimistic version and writes `submission_audit` with field names and before/after hashes, not full answer content.

- [ ] **Step 4: Implement confirmation transaction**

Require every assignment question to have exactly one answer candidate or an explicit confirmed blank. Create/update the current version's `submission`, insert one `student_answer` per assignment question, attach answer crops, mark version `CONFIRMED`, and make a repeat confirm idempotent.

- [ ] **Step 5: Build teacher correction UI**

Show student/version list, source page, cropped answer image, OCR/LaTeX candidate, confidence, mapped question and warnings. Allow teacher text edits and mapping changes; do not expose standard answers in the image pane until the teacher opens the separate reference panel, reducing accidental confirmation bias.

- [ ] **Step 6: Verify task 11**

Run:

```powershell
mvn -f backend/pom.xml -Dtest=AnswerExtractionServiceTest,SubmissionConfirmationTest test
npm --prefix frontend run test -- src/views/teacher/SubmissionReviewView.test.ts
npm --prefix frontend run typecheck
```

实际执行（全量而不是只跑这三个文件）：

```powershell
mvn -f backend/pom.xml test                 # 341 个用例全绿（含新增的 V12 迁移断言）
mvn -f backend/pom.xml verify -Pmysql-it    # BUILD SUCCESS：真实 MySQL 8.4 上 MySqlMigrationIT 12/12、MySqlCoreWorkflowIT 4/4
npm --prefix frontend run test              # 21 个文件 191 个用例全绿
npm --prefix frontend run typecheck         # EXIT=0
```

**与原计划的偏差（都已落地，记在这里而不是回头改计划书的历史）：**

1. **新增了计划里没有的 V12 迁移。** 计划把候选与正式答案的关系写成"确认时一次性写入"，
   但没定候选落在哪张表。实现时确认 `student_answer` 的唯一键 `(submission_id, question_id)`
   不允许候选与正式答案并存，所以候选必须另立一张表：`submission_answer_candidate`
   （宿主是 `submission_version_id`，语义是作答），而不是复用 `paper_question_candidate`
   （宿主是整卷导入，语义是出题）。同一迁移还带了两处计划没写但链路必需的列：
   - `submission_version.extraction_warnings`：整份答卷级别的警告（哪一页没交、哪两页对上了同一模板页）。
     存下来而不是每次读时重算，是因为算它要重新取一遍模板几何并与每块区域做几何比较，
     而这些事实在提交之后就定住了（学生改不了已提交的版本，整卷导入确认后不允许重跑识别）。
   - `document_page.template_page_no` / `alignment_confidence`：配准结果记在**识别出的那一页**上，
     不是记在"学生排的第几页"上——学生把第 2 页排到第 1 位，配准结果不该跟着换位置。
2. **文件名是 `AnswerReviewView.java`，不是计划里的 `AnswerCandidateView.java`。**
   这个视图装的是整份答卷（页、每页的区域、每道题的候选、两层警告），名字跟内容走。
3. **题目改派是互换，不是移动。** 计划假设"把候选挪到另一道题"可行，但
   `uk_answer_candidate_question` 要求每道题恒有且只有一条候选，所以目标题永远已经有候选，
   "挪"这种操作在结构上不存在。实现为**互换**（那道题原来的候选换到本题来），并且对面已
   `CONFIRMED` 时返回 409 `ANSWER_COUNTERPART_CONFIRMED`——否则教师会在不知情的情况下
   把一条已确认的答案换走。这是一处计划里的死代码路径：照计划实现，"改派"会永远失败。
4. **计划 Step 3 的 "before/after hashes" 没有实现，也实现不了。** `submission_audit` 没有哈希列，
   审计只记被改的字段名与一句人可读的说明。不补哈希列是刻意的：要比对哈希就得先存下内容，
   而答案内容不该进审计表。
5. **答案图按版本收敛。** 计划没提学生重交后重新确认时旧裁图怎么办。实现会在 `insertAssets` 里
   先删掉"不属于本版本"的 asset 行：留着的话复核页会并排显示两张答案图，其中一张来自学生
   已经不要的那一版，而教师看不出哪张是现在的。
6. **新增教师侧队列接口 `GET /api/teacher/submissions?assignmentId=`。** 计划只有按版本读的接口，
   但教师手上的活是"把这份作业全班批完"，必须能从作业进入。
7. **新增 `OcrTemplateProvider` 接缝。** 答卷配准要读"已确认的整卷导入"的题框几何，但那属于
   `paper` 包，而 `ocr` 包不能依赖它（会成环）。做法是 `ocr` 只定义接口，由 `submission` 包提供实现。
8. **OCR 契约没有升到 v2。** 计划说"扩展 OCR 请求带模板几何"并暗示换 schema 版本，实现选择在 v1 上
   做兼容扩展（模板几何与新增返回字段都可缺省），因为假实现与既有回归夹具都按 v1 写，
   换版本号会让它们全部失效而换不来任何东西。`transformMatrix` 保持 NULL：当前引擎不产出透视配准，
   写单位矩阵进去会让下游以为"已经做过透视校正"。
9. **前端新增了两个计划外的文件。** `frontend/src/api/teacherAnswerReview.ts`（一组接口单独成模块，
   理由同 `paperImport.ts`）与 `frontend/src/document/pages.ts`（`ThumbnailPage` 结构类型，
   与 `regions.ts` 里的 `DrawableRegion` 同一个理由：整卷页与答卷页是两个类型，但缩略图条与
   区域框层只用到其中几个字段，共用一份"画出来需要什么"好过让调用方伪造字段或写第二份实现）。
   相应地 `RegionOverlay` 多了 `readonly` 与 `describeRegion`：答卷页上的区域框是**证据**不是编辑对象。
10. **教师侧状态文案不建第二张表。** 取值清单仍在 `studentSubmission.ts`，`teacherAnswerReview.ts`
    只覆盖师生说法确实不同的那几个（学生看"老师已确认我的答题内容"，教师看"答案已入库"），
    其余回落到共享表——两张清单早晚会分叉。
11. **答卷页显示的是原图（`pageFileId`），不是学生转正后的图（`rotatedFileId`）。** 区域框与答案图
    都来自原图（识别跑在它上面），换成转正图会让框整体偏移。学生转过角度这件事改成一句话说出来，
    否则教师会以为"这些框怎么全都歪了"，然后开始手工挪框——而框本来就不能在这儿挪。
12. **已知局限（未修）：** 区域包含印刷题干时，识别出的 `answer_text` 会带上题干文字。
    教师在校对界面上看到的是识别原文，靠人判断哪些是学生写的。彻底解决要在识别阶段切分
    "题干行 / 作答行"，属于引擎能力，不在本次范围。
13. **测试基础设施：视图测试用 `enableAutoUnmount(afterEach)`。** `SubmissionReviewView` 的 `route`
    是测试里模块级共享的响应式对象，组件又用 `watch(assignmentId/versionId)` 决定何时重新拉数据。
    不卸载的话，前面用例挂出来的组件会继续监听，后面用例一改查询参数它们就一起重新拉一遍，
    表现为"这次操作调了几次接口"里混进十几个别的用例留下的次数——看着像被测代码在反复重拉。
    另外 `region`/`page`/`candidate` 这类夹具刻意做成 `Partial<T>` 覆盖函数，与 `PaperReviewView.test.ts` 一致。

---

### Task 12: 评分锁定、答案图片复核与学生最终反馈

**Files:**
- Modify: `backend/src/main/java/com/homework/analysis/grading/GradingOrchestrator.java`
- Modify: `backend/src/main/java/com/homework/analysis/grading/review/ReviewQueueItem.java`
- Modify: `backend/src/main/java/com/homework/analysis/grading/review/TeacherReviewService.java`
- Create: `backend/src/main/java/com/homework/analysis/grading/StudentResultController.java`
- Create: `backend/src/test/java/com/homework/analysis/grading/SubmissionVersionGradingTest.java`
- Modify: `backend/src/test/java/com/homework/analysis/grading/review/TeacherReviewApiTest.java`
- Create: `backend/src/test/java/com/homework/analysis/grading/StudentResultApiTest.java`
- Modify: `frontend/src/views/ReviewView.vue`
- Modify: `frontend/src/views/ReviewView.test.ts`
- Modify: `frontend/src/views/student/SubmissionDetailView.vue`
- Modify: `frontend/src/views/student/SubmissionDetailView.test.ts`

**Interfaces:**
- Consumes: confirmed current submission version only.
- Produces: review queue `answerAssets` with protected file IDs and page/region metadata.
- Produces: student-safe final result endpoint after teacher review.

- [x] **Step 1: Add failing grading-version tests**

Prove grading rejects `PROCESSING`/`NEEDS_REVIEW`, locks `CONFIRMED`, ignores superseded versions, and does not select invalidated tasks/results. A second run remains idempotent.

- [x] **Step 2: Restrict `GradingOrchestrator` to current confirmed versions**

Join `submission_version` and require `is_current=true`, status `CONFIRMED` or `LOCKED`, and `invalidated_at is null`. Lock versions before inserting rule results or AI tasks in the same transaction.

- [x] **Step 3: Add answer assets to teacher review**

Return file ID, role, page number, normalized box and sort order. Frontend loads private blobs only for the selected queue item and revokes object URLs when switching items or unmounting.

- [x] **Step 4: Add student-safe final results**

Students can read only teacher-confirmed score, final feedback and completion time for their current submission. Do not expose AI suggestion, score details, standard answer, rubric or teacher modification reason.

- [x] **Step 5: Verify task 12**

计划要求定向跑这三个后端类与这两个前端文件。实际执行（全量，而不是只跑这几处）：

```powershell
mvn -f backend/pom.xml test                 # 180 个测试类、361 个用例全绿
mvn -f backend/pom.xml verify -Pmysql-it    # 真实 MySQL 8.4：MySqlMigrationIT 12/12、MySqlCoreWorkflowIT 4/4
npm --prefix frontend run test              # 21 个文件、197 个用例全绿
npm --prefix frontend run typecheck         # EXIT=0
```

定向计数：`SubmissionVersionGradingTest` 7、`TeacherReviewApiTest` 6、`StudentResultApiTest` 10。

**与原计划的偏差（都已落地，记在这里而不是回头改计划书的历史）：**

1. **计划按字面实现会把整卷 Excel 导入那条旧链路整条批不了。** Step 2 要求"join `submission_version`
   且 `is_current=true`"，但导入链路的 `submission.submission_version_id` 是 NULL（V11 刻意不回填假版本号）。
   那条链路里没有"重交"这回事，导入的答案就是唯一的一套，所以它得照老规矩进批改。
   实现取两段并集：`s.submission_version_id is null or s.submission_version_id in (...)`，
   写成 union 而不是在 where 里堆 `or`，是为了让两段各自读起来就是一句完整的话。
2. **新增 `SUBMISSION_NOT_CONFIRMED`（409），计划没定义"一份都没确认"时怎么办。** 三种处境必须分开：
   一份作答都没有 → 返回零（那不是错误，报错只会让教师以为点错了地方）；有作答但一份都批不了 →
   必须说出来。静默返回零，教师会以为批改跑过了，而学生那边永远等不到分数。
3. **"重批"是复活失效的旧行，不是插新行。** `uk_grading_answer` 与 `uk_ai_task_answer` 都建在
   `answer_id` 上，同一道题插不进第二行。计划 Step 2 只说"不选失效的 task/result"，
   没说被选中的失效行该怎么办——照字面实现，学生退回重交后的第一次批改会直接撞唯一键。
   复活时要把上一轮的痕迹清干净（`attempt_count`、`last_error_code`、`sanitized_response`、
   `model_name`）。其中 `confirmed_score` 是非清不可的：学情统计按它取数，
   留着等于把上一版的分数算进这一版。
4. **批改在写任何结果之前先把版本钉成 `LOCKED`，走 `SubmissionVersionService.lockForTeacher`。**
   计划说"lock versions before inserting rule results or AI tasks"，做了；但没有自己拼那句 `update`，
   因为 `lockForTeacher` 是"开始批改"的唯一一处定义（含审计行、含"已经是 LOCKED 就直接返回"），
   在这儿再写一遍迟早会出现"某处锁了但没留痕"。
5. **学生成绩是四态状态机，计划只写了"能读到教师确认的分数与反馈"。** 新增
   `NOT_SUBMITTED` / `PENDING` / `PARTIAL` / `GRADED`，其中三处判断都不是显然的：
   一道都没批完时给 `null` 而不是 0（0 分是一个结论，会让学生以为结果已经出来）；
   分母是"这次提交里有作答的题数"而不是作业题数（没作答的题不该算成"没批完"）；
   `gradedScore` 是**已批完题目**的满分合计，所以显示时得配成"8 / 10（已批 1/3 题）"。
6. **不返回最终错因。** 计划点名的禁区是"AI 建议、评分明细、标准答案、评分项、教师改写原因"，
   最终错因看着像在禁区之外（它是教师确认过的）。但教师"采纳"时最终错因与模型建议一模一样，
   返回它等于在最常见的那条路径上把模型判断原样发给学生，而学生真正能读懂的是教师写的那句反馈。
7. **完成时间取 `teacher_review.created_at`，不是 `grading_result.updated_at`。** 后者会被任何一次
   写回刷新（重新批改、作废标记），拿它当"什么时候批完的"会随时间漂移。
8. **新增 `StudentResultService` / `StudentResultView`，计划的文件清单里只有 `StudentResultController`。**
   取数与安全投影分成两个文件，与 `StudentAssignmentView` 那套一致：字段表本身就是白名单，
   不存在的字段没法泄露，于是不需要在每个使用点做"记得别带上"的人肉检查。
9. **前端答案图只取当前选中那一条，并把这件事钉进测试。** 计划 Step 3 已经写了"只为选中项加载、
   切换与卸载时回收"，实现照做，另补三条用例（只取选中项 / 切过去才取 / 切换与卸载都回收）。
   这里有个坑：`jsdom` 不实现 `createObjectURL`，不补桩的话 `PrivateImage` 会落进"加载失败"分支，
   "取了几张图、什么时候回收"就全都无从断言——测试看着在跑，其实一条都没验到。
10. **答案图丢了来源页就说"来源页未知"，不编一个页码。** 区域会随模板重新配准被替换掉
    （`student_answer_asset.document_region_id` 置空），但答案图本身是批改依据，仍然在。
    编一个"第 1 页"会让教师以为图是从那一页裁出来的，而它可能根本不在那一页。
11. **前端新增 `StudentResult` 一族类型与 `getMyResult`，计划文件清单里没有 `api/types.ts` 与
    `api/studentSubmission.ts`。** 理由与 Task 10 偏差 1 相同：学生答卷这条流程的契约集中在一处。
    顺带在复核页补了一句"这道题没有答案图，只能按识别出的文字判断"——整卷导入那条旧链路的答案
    本来就没有答案图，不说明白的话教师会以为图加载失败了。

---

### Task 13: 演示数据、端到端测试、运行配置与文档收口

**Files:**
- Modify: `backend/src/main/java/com/homework/analysis/DemoDataInitializer.java`
- Modify: `backend/src/main/resources/application-demo.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Modify: `compose.yaml`
- Create: `compose.ocr.yaml`
- Create: `frontend/e2e/student-upload-workflow.spec.ts`
- Modify: `frontend/e2e/teacher-workflow.spec.ts`
- Create: `ocr-service/Dockerfile`
- Create: `ocr-service/README.md`
- Modify: `.env.example`
- Modify: `.gitignore`
- Modify: `README.md`
- Modify: `docs/2026-09-14-项目阶段总结.md`

**Interfaces:**
- Produces: reproducible demo with one teacher account, one student account, one published assignment and fixture OCR.
- Produces: production-like compose profile with private storage and OCR service.

- [ ] **Step 1: Add deterministic demo fixtures**

Create a demo student account with forced first-change disabled only for the documented demo password, one published assignment, one PNG question figure and one fixed OCR submission flow. Demo/test profiles use fake OCR responses; real Paddle models are opt-in through `compose.ocr.yaml`.

- [ ] **Step 2: Add two-browser E2E**

`student-upload-workflow.spec.ts` uses separate teacher and student browser contexts:

```text
teacher publishes assignment
student logs in and uploads ordered fixture pages
student resubmits before grading
teacher confirms OCR and starts grading
student upload becomes locked
teacher returns before final review
student submits next version
teacher confirms, grades and reviews
student sees final score and feedback only
```

Also assert the teacher sees a question figure and answer crop, while the student API response never includes standard answers.

- [ ] **Step 3: Add OCR regression fixtures outside real identities**

Store only synthetic/de-identified small fixtures under `ocr-service/tests/fixtures/`; add source and license notes. Do not commit real student scans. Add hashes for frozen fixtures and compare normalized JSON outputs.

- [ ] **Step 4: Document configuration and operations**

Document:

```text
STORAGE_MODE=local|s3
STORAGE_LOCAL_ROOT=<absolute path outside repository>
S3_ENDPOINT / S3_REGION / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY
OCR_BASE_URL / OCR_INTERNAL_TOKEN / OCR_ENABLED
OCR_CONFIDENCE_THRESHOLD=0.85
```

Explain backup scope, failed/draft cleanup, private URL behavior, model downloads, CPU/GPU profiles and the rule that production credentials and real scans never enter Git.

- [ ] **Step 5: Run the full verification matrix**

Run fresh:

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml verify -Pmysql-it
python -m pytest ocr-service/tests -q
npm --prefix frontend run test
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test:e2e
python -m unittest discover -s evaluation/tests -v
git diff --check
git status --short
```

Expected: every command exits 0; real identity files, storage objects, model weights, credentials and OCR output archives are absent from Git status.

- [ ] **Step 6: Manual mobile and failure-path acceptance**

At 390×844 and 430×932 verify camera/gallery selection, page ordering, progress, retry, return reason, keyboard-accessible reordering and no horizontal overflow. Separately stop the OCR service and verify bounded retry plus a visible actionable failure without partial formal answers.

---

## Plan Self-Review

- **Spec coverage:** student identity, private storage, teacher paper OCR, question figures, student versioned upload, OCR confirmation, grading lock, review images, final feedback, security and verification each map to a task.
- **Dependency order:** tasks 1–4 establish identity and publication; tasks 5–6 establish storage/OCR; tasks 7–8 build teacher import; tasks 9–10 build student submission; tasks 11–12 connect answers and grading; task 13 closes operations and E2E.
- **Type consistency:** `AuthPrincipal`, `Profile`, `submission_version`, OCR schema v1, file IDs and optimistic `version` fields are defined before consumers.
- **No silent data loss:** partial OCR failures, unmapped regions, stale edits, superseded submissions and cleanup failures have explicit states and tests.
- **No automatic repository mutation beyond implementation files:** commits, push, deploy and real-model downloads remain separately authorized actions.

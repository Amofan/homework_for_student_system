-- 文档存储、页面、区域与 OCR 任务。
--
-- 这一层刻意位于正式业务表之前：OCR 与文件只产生候选数据，
-- 未经教师确认的内容不会写进 question / student_answer。
--
-- 三条跨引擎约束贯穿本文件：
--   1. 区域坐标用四个 decimal(8,7) 列，不用厂商特有的几何类型——H2 与 MySQL 都能直接跑；
--   2. OCR 原始 JSON 用 text，避免 JSON 列在 H2 上的方言差异；
--   3. 不在这里声明 submission_version 的外键：那张表由 V11 建立，
--      V9 只留 submission_version_id 列，由 V11 补上外键（见文件末尾注释）。

-- 对象存储中的一个文件。内容不进数据库，只留元数据与完整性信息。
create table stored_file (
    id bigint auto_increment primary key,
    -- 教师始终是归属方；学生上传的文件同时带上 student_id，便于按人校验与清理。
    teacher_id bigint not null,
    student_id bigint,
    -- 服务端生成的存储键，拒绝使用用户文件名作为路径。
    storage_key varchar(512) not null,
    original_name varchar(255) not null,
    mime_type varchar(128) not null,
    size_bytes bigint not null,
    sha256 char(64) not null,
    width int,
    height int,
    -- ACTIVE / DELETED：删除先改状态并记 deleted_at，对象由清理任务异步删除，
    -- 使数据库审计行不会因为对象先被删而失去依据。
    status varchar(32) not null default 'ACTIVE',
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    -- 对象内容真正被删除的时间。保留这一行与 sha256，是为了在文件已经拿不回来的情况下
    -- 仍然能回答"当时上传的是哪一份内容"。清理任务据此跳过已经清过的行。
    purged_at timestamp(3),
    constraint uk_stored_file_key unique (storage_key),
    constraint fk_stored_file_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_stored_file_student foreign key (student_id) references student(id)
);

-- 一次上传。同一份原始文件可能被处理多次（不同 OCR 版本），上传本身只有一条。
create table document_upload (
    id bigint auto_increment primary key,
    -- EXAM_PAPER / ANSWER_KEY / STUDENT_SUBMISSION
    document_kind varchar(32) not null,
    teacher_id bigint not null,
    assignment_id bigint,
    student_id bigint,
    -- 指向 V11 建立的 submission_version，外键在 V11 补；学生答卷必须先有提交版本。
    submission_version_id bigint,
    original_file_id bigint not null,
    -- PENDING / PROCESSING / NEEDS_REVIEW / CONFIRMED / FAILED
    status varchar(32) not null default 'PENDING',
    page_count int not null default 0,
    current_ocr_version int,
    failure_reason_code varchar(64),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    deleted_at timestamp(3),
    constraint fk_document_upload_teacher foreign key (teacher_id) references teacher(id),
    constraint fk_document_upload_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_document_upload_student foreign key (student_id) references student(id),
    constraint fk_document_upload_file foreign key (original_file_id) references stored_file(id)
);

-- 校正后的页面。原始文件不被替换：页面图是派生资产，OCR 与预览都以页面为准。
create table document_page (
    id bigint auto_increment primary key,
    document_id bigint not null,
    page_no int not null,
    page_file_id bigint not null,
    thumbnail_file_id bigint,
    original_width int,
    original_height int,
    corrected_width int,
    corrected_height int,
    -- 取值 0 / 90 / 180 / 270，单位是度。
    rotation_degrees int not null default 0,
    -- 3x3 透视变换矩阵，按行展开成 9 个数字，用逗号分隔。
    transform_matrix varchar(255),
    -- OK / WARNING / BLOCKING，BLOCKING 时不允许最终提交。
    quality_status varchar(32),
    quality_score decimal(5,4),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_document_page_no unique (document_id, page_no),
    constraint fk_document_page_document foreign key (document_id) references document_upload(id),
    constraint fk_document_page_file foreign key (page_file_id) references stored_file(id),
    constraint fk_document_page_thumbnail foreign key (thumbnail_file_id) references stored_file(id)
);

-- 页面上的一个区域：题干块、选项块、题图、或学生的一道答案。
create table document_region (
    id bigint auto_increment primary key,
    page_id bigint not null,
    -- TEXT_BLOCK / FORMULA / FIGURE / ANSWER_BLOCK / OPTION
    region_type varchar(32) not null,
    -- 归一化到 0..1 的坐标，与页面像素尺寸解耦。
    x decimal(8,7) not null,
    y decimal(8,7) not null,
    width decimal(8,7) not null,
    height decimal(8,7) not null,
    ocr_text text,
    ocr_latex text,
    -- 统一归一化到 0..1；阈值只影响提示，不能绕过教师确认。
    confidence decimal(5,4),
    raw_ocr_json text,
    crop_file_id bigint,
    -- PENDING / CONFIRMED / REJECTED，人工确认状态。
    review_status varchar(32) not null default 'PENDING',
    -- 教师每改一次加一，用于发现并发编辑。
    revision int not null default 0,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint fk_document_region_page foreign key (page_id) references document_page(id),
    constraint fk_document_region_crop foreign key (crop_file_id) references stored_file(id),
    constraint ck_document_region_x check (x between 0 and 1),
    constraint ck_document_region_y check (y between 0 and 1),
    constraint ck_document_region_width check (width between 0 and 1),
    constraint ck_document_region_height check (height between 0 and 1)
);

-- OCR 任务。
--
-- 幂等键是（文件哈希, 文档类型, 处理版本）：同一份文件重复提交不会产生第二份识别结果，
-- 换处理版本（模型或参数升级）才会重跑。原始结构化输出原样留档，便于回溯当时的判断依据。
create table ocr_task (
    id bigint auto_increment primary key,
    document_id bigint not null,
    document_kind varchar(32) not null,
    storage_sha256 char(64) not null,
    -- PENDING / RUNNING / NEEDS_REVIEW / CONFIRMED / RETRY_WAIT / FAILED
    status varchar(32) not null default 'PENDING',
    engine varchar(64),
    model_version varchar(64),
    params_version varchar(64),
    processing_version int not null default 1,
    attempt_count int not null default 0,
    claimed_at timestamp(3),
    -- 退避重试的下次可尝试时间。临时网络故障、进程不可用或资源繁忙都走这条路；
    -- 文件损坏、类型非法这类"重试也不会变好"的失败直接进 FAILED，不占用退避时间。
    next_attempt_at timestamp(3),
    started_at timestamp(3),
    finished_at timestamp(3),
    duration_ms bigint,
    failure_code varchar(64),
    raw_output text,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_ocr_task_idempotency unique (storage_sha256, document_kind, processing_version),
    constraint fk_ocr_task_document foreign key (document_id) references document_upload(id)
);

create index idx_stored_file_teacher on stored_file(teacher_id, status);
create index idx_stored_file_student on stored_file(student_id, status);
create index idx_stored_file_sha on stored_file(sha256);
create index idx_document_upload_owner on document_upload(teacher_id, document_kind, status);
create index idx_document_upload_assignment on document_upload(assignment_id);
create index idx_document_upload_student on document_upload(student_id, status);
create index idx_document_page_document on document_page(document_id);
create index idx_document_region_page on document_region(page_id, region_type);
create index idx_document_region_review on document_region(review_status);
-- 任务认领按 (status, claimed_at) 取最旧的一批，索引必须覆盖这两列的顺序。
create index idx_ocr_task_claim on ocr_task(status, claimed_at);
-- 退避重试按 (status, next_attempt_at) 挑选到期任务。
create index idx_ocr_task_retry on ocr_task(status, next_attempt_at);
create index idx_ocr_task_document on ocr_task(document_id);

-- V11 需要补上：alter table document_upload
--   add constraint fk_document_upload_version foreign key (submission_version_id)
--   references submission_version(id);
-- 在此之前该列始终为 NULL（教师试卷类上传不需要提交版本）。

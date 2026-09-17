-- 学生提交版本、页面整理、退回审计与答案资产。
--
-- 为什么是"版本"而不是就地改：学生的作答是证据。重交必须留下旧版本，教师退回后也要能看出
-- 退回的是哪一版、后来改了什么。所以提交只新增版本行，不覆盖旧行；被取代的版本连同它的
-- 页面与审计事件一起冻结在库里。
--
-- 两个"当前"不是一个意思，不能互相替代：
--   * is_current：这一版是不是已经提交、并且占着"当前提交"位置的那一版。草稿永远不是 ——
--     草稿还没交，占住这个位置只会让教师在待批改列表里看到一份学生还在改的东西。
--   * submission.submission_version_id：student_answer 里现存的那套答案来自哪一版，
--     只有在教师确认了答卷 OCR 之后才会挪（见 spec §11）。
-- 两者在三个时刻各自移动，谁都不跟着谁：
--   * 建草稿：都不动。上一版提交仍然是当前提交，老师继续批它，直到学生真的重交。
--   * 提交：is_current 切到新版，旧版置 SUPERSEDED。
--   * 退回：is_current 清空（没有当前提交了），submission_version_id 不动 ——
--     库里那套答案仍然来自被退回的那一版，这正是"退回的是哪一版作答"的凭证。
-- 因此 is_current=true 至多一行的不变量由建版/提交/退回三处共同维护，
-- 不要在任何第四处单独改它。
--
-- "同一学生同一作业只有一个 is_current" 由服务层锁作业行保证：MySQL 与 H2 都表达不了
-- is_current=true 的部分唯一索引，写成生成列虽然可行，但为一个可加锁保证的不变量
-- 引入两套方言都要验证的语法不划算。
--
-- 版本号在 (assignment_id, student_id) 内从 1 连续递增：同一份作业的多次提交是一个递增序列，
-- "第几版"在学生和教师两端都直接可读，不必再翻译一遍 uuid。

create table submission_version (
    id bigint auto_increment primary key,
    submission_id bigint not null,
    assignment_id bigint not null,
    student_id bigint not null,
    version_no int not null,
    -- DRAFT / UPLOADED / PROCESSING / NEEDS_REVIEW / CONFIRMED / LOCKED / SUPERSEDED / RETURNED / FAILED
    --
    -- DRAFT 与 UPLOADED 是学生还能改文件与页面的两个状态；SUBMITTED 之后只读。
    -- 结局只有三种：被退回（RETURNED）、被后续版本取代（SUPERSEDED）、是当前版本（其余）。
    -- RETURNED 是终态，不会被后来的 SUPERSEDED 覆盖 —— 退回原因正是学生要看的那个东西，
    -- 把它改写成"已被取代"等于把提示删了。所以一个版本可以是"已退回且已不是当前版本"。
    status varchar(32) not null default 'DRAFT',
    -- 是不是已经提交、并且占着"当前提交"位置的那一版：同一 (作业, 学生) 下至多一行为真。
    -- 草稿、已取代、已退回的版本都是 false —— 退回会把这一列清空，见文件头注释。
    is_current boolean not null default false,
    submitted_at timestamp(3),
    locked_at timestamp(3),
    returned_at timestamp(3),
    -- 教师退回原因。退回是学生改错的唯一入口，"为什么退回"必须随版本一起留存，
    -- 否则学生只能看到状态变了却不知道要改什么，只能反复重交同一份。
    return_reason varchar(1000),
    superseded_at timestamp(3),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_submission_version_no unique (assignment_id, student_id, version_no),
    constraint fk_submission_version_submission foreign key (submission_id) references submission(id),
    constraint fk_submission_version_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_submission_version_student foreign key (student_id) references student(id)
);

-- 一页 = 学生答卷上的一页。page_no 是学生排定的顺序，唯一键保证同一版本里没有两个第 3 页。
--
-- 与 document_page 的分工：document_page 是识别链路里的页面（由物化阶段在 OCR 之后建立，
-- 区域框挂在它上面），本表是学生看到的、能排序能旋转的那一页。两者靠 (document_id,
-- document_page_no) 对应 —— 不直接存 document_page.id，因为上传时页面行还不存在，
-- 学生却必须立刻看到自己刚交的页面才能排序。
--
-- 旋转不覆盖原图：page_file_id 是按上传内容渲染出来的那一页（PDF 按 DPI 渲染、图片原样归一），
-- rotated_file_id 是转正之后的展示图。学生交的原样与我们看到的样子都在，教师质疑某道题被判错时
-- 能回到证据本身；只留旋转后的图，等于把学生交的东西改掉再存档。
create table submission_page (
    id bigint auto_increment primary key,
    submission_version_id bigint not null,
    page_no int not null,
    -- 这一页来自哪次上传（一次上传 = 一个 document_upload，也是一个 OCR 任务）。
    document_id bigint not null,
    -- 该文档内的第几页：图片上传恒为 1，PDF 从 1 开始。
    document_page_no int not null default 1,
    page_file_id bigint not null,
    rotated_file_id bigint not null,
    thumbnail_file_id bigint,
    rotation_degrees int not null default 0,
    -- 当前展示图（rotated_file_id 那一张）的像素宽高，随旋转一起更新：转过 90 或 270 度宽高互换。
    -- 下游按区域裁剪用的就是这两个数，而区域坐标是归一化的，宽高写错不会报错，只会裁错位置。
    -- 记的是"转完之后的尺寸"，所以回到 0 度时要按原始页面图重新算，不能在旧值上再换一次。
    width int,
    height int,
    -- OK / WARNING / BLOCKING，与 document_page.quality_status 取值一致。
    -- BLOCKING 直接拦下提交（422），WARNING 要学生逐页确认后才放行（409）。
    --
    -- 默认 OK 而不是 PENDING：提交校验拦的是"有明确证据说明拍坏了"的页面，没做过质量评估的
    -- 页面按放行处理。反过来写会让部署环境一旦没有质量评估器，所有学生就永远提交不了。
    quality_status varchar(32) not null default 'OK',
    quality_score decimal(5,4),
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    -- 只允许四个直角旋转。任意角度会让后续按区域裁剪的坐标全都要跟着做三角函数，
    -- 而那些坐标是教师框出来的、人可读的归一化值，不该被一个手滑的 45 度污染。
    constraint ck_submission_page_rotation check (rotation_degrees in (0, 90, 180, 270)),
    constraint uk_submission_page_no unique (submission_version_id, page_no),
    -- 同一份上传里的同一页只能占一个位置：重复入选会让同一道题被算两遍分。
    constraint uk_submission_page_source unique (document_id, document_page_no),
    constraint fk_submission_page_version foreign key (submission_version_id) references submission_version(id),
    constraint fk_submission_page_document foreign key (document_id) references document_upload(id),
    constraint fk_submission_page_file foreign key (page_file_id) references stored_file(id),
    constraint fk_submission_page_rotated foreign key (rotated_file_id) references stored_file(id),
    constraint fk_submission_page_thumbnail foreign key (thumbnail_file_id) references stored_file(id)
);

-- 提交版本上的时间线。
--
-- 只追加不修改：谁、什么时候、做了什么。教师退回、学生重排页面、开始批改都要留痕，
-- 因为"这份答卷到底被谁动过"是申诉时唯一能拿出来的东西，而状态列只保留最后一步。
create table submission_audit (
    id bigint auto_increment primary key,
    submission_version_id bigint not null,
    assignment_id bigint not null,
    student_id bigint not null,
    -- CREATED / PAGE_ADDED / PAGE_REMOVED / PAGE_REORDERED / PAGE_ROTATED /
    -- SUBMITTED / OCR_STATE_CHANGED / OCR_TEXT_CORRECTED / ANSWERS_CONFIRMED /
    -- SUPERSEDED / LOCKED / RETURNED
    action varchar(32) not null,
    -- STUDENT / TEACHER / SYSTEM
    actor_role varchar(16) not null,
    -- 指向 actor_role 那一侧的 id：STUDENT 时是 student.id，TEACHER 时是 teacher.id。
    -- 刻意不写成 app_user.id —— 审计要回答的是"哪名学生""哪位老师"，而 app_user 只是登录账号，
    -- 两者之间还隔着 student/teacher 一行。留错参照物比留空更糟：查出来的名字是别人的。
    -- 刻意不加外键：审计行必须比账号活得久，账号被删不能让历史断档。
    -- SYSTEM 动作（如 OCR 落地、作业关闭）没有操作人，为 NULL。
    actor_id bigint,
    -- 人可读的补充说明，例如"第 3 页移到第 1 页"、"学生重交第 2 版"。
    detail varchar(1000),
    created_at timestamp(3) not null default current_timestamp(3),
    constraint fk_submission_audit_version foreign key (submission_version_id) references submission_version(id),
    constraint fk_submission_audit_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_submission_audit_student foreign key (student_id) references student(id)
);

-- 学生答案图：从某张提交页的某个区域裁出来的那一小块，绑到具体的 student_answer 上。
--
-- 为什么单独建表而不是给 student_answer 加一列 file_id：一道题可能由多张图组成
-- （学生分两处写、跨页续写），复核界面要按顺序并排展示，一个答案对多张图，所以是子表。
create table student_answer_asset (
    id bigint auto_increment primary key,
    answer_id bigint not null,
    submission_version_id bigint not null,
    -- 裁图出自哪一页。页面被学生删掉后重交时，这些行会随旧版本一起冻结，不跟着动。
    submission_page_id bigint,
    -- 来源区域：教师在答卷模板配准阶段框出的那一块。区域随模板重新配准会被替换掉，
    -- 但已经裁出来的答案图是批改依据，必须留存，所以这里删区域只置空来源引用。
    document_region_id bigint,
    file_id bigint not null,
    -- SOURCE_CROP（学生手写原图的裁剪）/ DERIVED（二次处理，如拼接、增强）
    role varchar(32) not null default 'SOURCE_CROP',
    sort_order int not null default 0,
    created_at timestamp(3) not null default current_timestamp(3),
    constraint uk_answer_asset_file unique (answer_id, file_id),
    constraint fk_answer_asset_answer foreign key (answer_id) references student_answer(id),
    constraint fk_answer_asset_version foreign key (submission_version_id) references submission_version(id),
    constraint fk_answer_asset_page foreign key (submission_page_id) references submission_page(id),
    constraint fk_answer_asset_region foreign key (document_region_id) references document_region(id) on delete set null,
    constraint fk_answer_asset_file foreign key (file_id) references stored_file(id)
);

create index idx_submission_version_submission on submission_version(submission_id, version_no);
-- 教师待办按"某份作业里处于某状态的提交"取，索引顺序必须与查询一致。
create index idx_submission_version_assignment on submission_version(assignment_id, status);
create index idx_submission_version_student on submission_version(student_id, status);
create index idx_submission_page_version on submission_page(submission_version_id);
create index idx_submission_page_document on submission_page(document_id);
create index idx_submission_audit_version on submission_audit(submission_version_id, created_at);
create index idx_submission_audit_assignment on submission_audit(assignment_id, created_at);
create index idx_answer_asset_answer on student_answer_asset(answer_id, sort_order);
create index idx_answer_asset_version on student_answer_asset(submission_version_id);

-- student_answer 里现存答案的来源版本。
--
-- 语义与 submission_version.is_current 不同：它跟着"教师确认了哪一版答卷"走，而不是跟着
-- "学生交的是哪一版"走。学生重交后 is_current 立刻切到新版，这一列仍然停在上一版，
-- 因为库里那套 student_answer 还是上一版的 —— 两边一起改才会出现"学生改了、老师没确认，
-- 但成绩已经从新答案算出来了"。
--
-- 可空：V3 时代导入的提交根本没有版本概念，历史行保持 NULL，读取侧按"没有提交版本"处理，
-- 而不是回填一个假版本号 —— 回填会让历史数据看起来像学生提交过。
--
-- 刻意不加外键：submission_version.submission_id 已经反向引用了 submission，两边都加就成环，
-- 结果是任何删除顺序都无解（谁是父谁是子说不清），清理脚本和级联都得绕着走。
-- 版本表的行是"存在性"的真相来源，这条指针只是省一次 join 的便利字段。
alter table submission add column submission_version_id bigint;

-- 先给 grading_result 腾出行空间，否则下面两列加不进去。
--
-- MySQL 的硬限制：一行里所有列的最大宽度之和不得超过 65535 字节，而 varchar(n) 无论实际存了
-- 几个字，都按 4n 字节（utf8mb4）全额计入。这张表原来的 score_details(8000) 加
-- teacher_explanation(4000) 加 student_feedback(4000) 就占了 64000 字节，加起来 64497，
-- 再塞一个 varchar(500) 直接报 "Row size too large ... check the manual"。
-- 这三个都是不限长度的自由文本/JSON，换成 text：只按约 12 字节的指针计入，
-- 容量上限（65535 字节）反而比原来宽，读写两侧都还是 String，行为不变。
-- 顺带一提，H2 没有这条限制，所以这份迁移在内存库上一直全绿 —— 直到在真实 MySQL 上跑第一次。
alter table grading_result modify column score_details text not null;
alter table grading_result modify column teacher_explanation text;
alter table grading_result modify column student_feedback text;

-- 教师退回时，基于旧作答产生的 AI 建议和未确认评分必须失效，但不能删 ——
-- 教师可能已经看过、甚至照着给分，删掉等于把发生过的事抹掉。标记"何时、因何失效"。
--
-- 已确认的教师评分不走这两列：那是人做出的判断，退回后也不允许被静默作废，
-- 这种情况服务层直接拒绝退回（SUBMISSION_ALREADY_FINALIZED），而不是在这里打标记。
alter table grading_result add column invalidated_at timestamp(3);
alter table grading_result add column invalidated_reason varchar(500);
alter table ai_grading_task add column invalidated_at timestamp(3);
alter table ai_grading_task add column invalidated_reason varchar(500);

-- V9 留下的待补外键。学生提交的文档在这里挂上提交版本，教师试卷类上传保持 NULL。
alter table document_upload add constraint fk_document_upload_version
    foreign key (submission_version_id) references submission_version(id);

-- OCR 幂等键从（文件哈希, 文档类型, 处理版本）改成（文档, 处理版本）。
--
-- 旧键把"内容相同"当成"同一次识别"：学生交上来的答卷与空白卷/答案卷哈希相同（例如同一张
-- 空白页被误传、或整卷正是从模板复印出来的）时，第二条任务会被静默跳过，学生的答卷就永远
-- 停在"待识别"。要识别的是"这份文档有没有任务"，而不是"这个字节串有没有任务"。
--
-- storage_sha256 列保留：上传期的同文件提示和事后回溯还用得上，只是不再参与判重。
-- 注意：若历史数据里已经存在同一 (document_id, processing_version) 的多行，加约束会失败，
-- 需要先人工确认那几行是不是同一份文档的重复任务。
--
-- 用 drop index 而不是 drop constraint：MySQL 里唯一约束就是个索引，DROP CONSTRAINT 对这种
-- 由索引背书的约束是有歧义的（文档让改用 DROP INDEX），而 H2 两样都收。写成 DROP INDEX 两边都能跑。
alter table ocr_task drop index uk_ocr_task_idempotency;
alter table ocr_task add constraint uk_ocr_task_document_version unique (document_id, processing_version);

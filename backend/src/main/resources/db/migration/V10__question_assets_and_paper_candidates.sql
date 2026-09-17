-- 整卷导入的候选题与题目配图。
--
-- 这一层的定位与 V9 一致：OCR 与教师的校对结果都先落到候选，未经确认不进 question。
-- 确认是一次全有或全无的事务（见 PaperImportService.confirm），失败时候选与原始文档都保留，
-- 教师修正后可以重试。

-- OCR 检测出的一道题（可能的题）。
--
-- 空白卷与参考答案卷各自产生候选，靠 document_id / document_kind 区分：
--   * EXAM_PAPER 候选是"待成为正式题目"的行，确认时写进 question；
--   * ANSWER_KEY 候选只提供标准答案，按 question_code 与空白卷候选匹配，永远不写 question。
--
-- 两侧都落成候选而不是只存一份，是因为"答案卷里有、空白卷里没有"的题必须有个地方挂警告：
-- 它既不是空白卷候选的字段错误，也不能悄悄丢掉——那往往是空白卷 OCR 漏检的信号。
create table paper_question_candidate (
    id bigint auto_increment primary key,
    assignment_id bigint not null,
    document_id bigint not null,
    -- EXAM_PAPER / ANSWER_KEY
    document_kind varchar(32) not null,
    -- 阅读顺序，从 1 开始，在同一 document 内唯一。
    order_no int not null,
    -- 以下都是"检测到"的值，允许为空：空值正是教师需要补齐的地方。
    question_code varchar(64),
    question_type varchar(32),
    content text,
    standard_answer text,
    -- 可接受答案与评分项都以 JSON 存 text，避免 JSON 列在 H2 与 MySQL 上的方言差异。
    accepted_answers text,
    rubric_items text,
    total_score int,
    difficulty varchar(16),
    primary_knowledge_point_id bigint,
    -- 该候选由哪些 document_region 组成。合并与拆分只改这个列表，不删除任何来源区域。
    source_region_ids varchar(1024) not null,
    -- 默认作为题图的来源区域；教师可在校对界面调整。
    asset_region_ids varchar(1024),
    -- 答案卷候选匹配到的空白卷题号；空白卷候选恒为空。用题号而不是候选主键关联，
    -- 是因为候选会在重新识别时整体重建，主键不稳定而题号是教师看得见的键。
    matched_question_code varchar(64),
    confidence decimal(5,4),
    -- 结构化警告（JSON 数组的字符串列表），例如 LOW_CONFIDENCE / ANSWER_KEY_UNMATCHED。
    warnings text,
    -- PENDING / CONFIRMED / REJECTED，人工校对状态。
    review_status varchar(32) not null default 'PENDING',
    -- 教师每改一次加一；PATCH 用 where version = :expectedVersion 做乐观锁。
    version int not null default 0,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_paper_candidate_order unique (document_id, order_no),
    constraint fk_paper_candidate_assignment foreign key (assignment_id) references assignment(id),
    constraint fk_paper_candidate_document foreign key (document_id) references document_upload(id)
);

-- 题目的配图。
--
-- 三种角色互不替代：题图进题干，来源裁剪用于回溯"这题是从原卷哪一块来的"，
-- 参考答案图只在教师校对时展示。把它们塞进一个字段迟早会有人在渲染时漏判一种。
create table question_asset (
    id bigint auto_increment primary key,
    question_id bigint not null,
    file_id bigint not null,
    -- STEM_FIGURE / SOURCE_CROP / REFERENCE_IMAGE，取值由 Java 枚举校验。
    role varchar(32) not null,
    sort_order int not null,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    constraint uk_question_asset_order unique (question_id, sort_order),
    constraint fk_question_asset_question foreign key (question_id) references question(id),
    constraint fk_question_asset_file foreign key (file_id) references stored_file(id)
);

create index idx_paper_candidate_document on paper_question_candidate(assignment_id, document_kind, order_no);
-- 空白卷与答案卷之间按题号匹配，这条索引服务于"按题号找对侧候选"。
create index idx_paper_candidate_code on paper_question_candidate(assignment_id, question_code);
create index idx_question_asset_file on question_asset(file_id);

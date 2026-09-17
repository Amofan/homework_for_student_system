-- 答卷的答案候选：教师校对的对象，确认之前不写任何正式数据。
--
-- 为什么不是复用 paper_question_candidate：那张表的宿主是"一次整卷导入"（assignment_id + document_id），
-- 列的语义是出题（question_code / total_score / difficulty / rubric_items / standard_answer）。
-- 答卷的宿主是"一版提交"（submission_version_id），语义是作答（映射到哪道题、学生写了什么）。
-- 把学生答案写进 standard_answer 那一列，等于让"这一列到底代表什么"取决于读的是哪一行。
--
-- 为什么不是直接写 student_answer：那张表是正式数据（教师确认后的评分依据），
-- 唯一键 (submission_id, question_id) 也不允许候选与正式答案并存。识别结果必须先在候选区
-- 落一脚，教师校对完再一次性写进去 —— 这是"OCR 只产生候选"这条规则在表结构上的落点。
--
-- 一道题恰好一行，在物化阶段就按 assignment_question 建齐：学生漏答的题也有一行（区域为空、
-- 带 ANSWER_BLANK 警告）。这样"每道题都有候选"是表结构的性质，而不是确认时才发现缺了几道。
create table submission_answer_candidate (
    id bigint auto_increment primary key,
    submission_version_id bigint not null,
    question_id bigint not null,
    -- 与 assignment_question.question_order 一致，冗余存一份是为了教师调整映射后仍能按题目顺序展示，
    -- 不必每次 join 回作业题目表。
    order_no int not null,
    -- 识别出的答案文本（教师可改）。空与 NULL 不是同一件事：
    -- NULL = 还没有识别结果，空串 = 识别到了但那一块是空的。
    answer_text text,
    -- 公式与手写体的 LaTeX 形式。答案区通常只有一小块是公式，所以与文本分开存，
    -- 让前端能把公式单独走 MathText 渲染。
    --
    -- 用 text 不用 varchar(2000)：一道题的公式可能有好几行（分步计算），而 varchar 无论实际
    -- 存几个字都按 4n 字节全额计入 MySQL 的行宽上限（65535），一个列宽取值不当就会在真实
    -- 数据库上以 "Row size too large" 收场 —— H2 没有这条限制，内存库里一直全绿。
    answer_latex text,
    -- 组成这道题答案的区域，JSON 数字数组（与 paper_question_candidate.source_region_ids 同一写法）。
    -- 学生分两处写、跨页续写都会让这道题有多块区域，所以是数组而不是单列外键。
    --
    -- 区域一个都不删：合并与拆分换的是归属，不是区域集合。校对的全部意义是教师对着像素核对，
    -- 丢掉区域等于丢掉唯一的核对依据。
    source_region_ids varchar(1024) not null,
    -- 识别置信度，取组成这道题的区域里的最小值（一道题里有一块看不清，整道题就需要人看一眼）。
    confidence decimal(5,4),
    -- 显式空答：教师确认"这道题学生确实没写"。
    -- 不能用"answer_text 为空"代替：那与"还没校对"完全同形，会让一份没看完的答卷被误确认。
    blank boolean not null default false,
    -- 结构化警告（JSON 数组的字符串列表），取值见 AnswerWarning 枚举。
    -- 用 text 不用 varchar(n)：varchar 无论实际存几个字都按 4n 字节计入 MySQL 的行宽上限（65535）。
    warnings text,
    -- PENDING / CONFIRMED，取值见 AnswerWarning 所在的包。
    --
    -- 这里**没有** REJECTED（与 paper_question_candidate 的三值不同）：整卷导入时"这道题识别错了"
    -- 只能整条否掉，答卷里"这道题的作答分错了"永远有更好的修法——改派到正确的题
    -- （AnswerCorrectionCommand.questionId）。允许否掉一条候选会造出"某道题没有候选"的状态，
    -- 而那正是确认阶段要求不成立的情形。
    review_status varchar(32) not null default 'PENDING',
    -- 乐观锁版本号。教师侧并发校准时以这个数为准，每次编辑 +1（见 AnswerCorrectionCommand）。
    version int not null default 0,
    created_at timestamp(3) not null default current_timestamp(3),
    updated_at timestamp(3) not null default current_timestamp(3),
    -- 一道题只能有一条候选：两条会让"这道题的答案是什么"没有唯一答案，确认时也无从选择。
    constraint uk_answer_candidate_question unique (submission_version_id, question_id),
    -- 展示顺序也要唯一，否则教师调整顺序时会出现两道题都排在 3 号位。
    constraint uk_answer_candidate_order unique (submission_version_id, order_no),
    constraint fk_answer_candidate_version foreign key (submission_version_id)
        references submission_version(id),
    constraint fk_answer_candidate_question foreign key (question_id) references question(id)
);

create index idx_answer_candidate_version on submission_answer_candidate(submission_version_id, review_status);

-- 整份答卷级别的校对警告，JSON 数组（元素 {"code": "...", "detail": "..."}）。
--
-- 为什么存下来而不是每次读的时候算：算它们要重新取一遍模板几何、再把每一块区域与每一个题框
-- 做一次几何比较，而教师每打开一次答卷都会读一次。它们描述的事实（哪一页没交、哪一页对不上模板、
-- 哪些区域落在所有题框之外）在提交之后就定住了——学生改不了已提交的版本，模板来自已确认的
-- 整卷导入，而整卷导入确认之后不允许再重跑识别。所以存下来不会与事实漂移。
--
-- 与 submission_answer_candidate.warnings 的分工：这里回答"整份答卷有什么问题"，
-- 那里回答"这一道题有什么问题"。
alter table submission_version add column extraction_warnings text;

-- 页面配准结果。
--
-- 这两列记的是"这一页对应模板的哪一页、有多像"，是教师判断"学生是不是交错了页"的依据。
-- 记在 document_page 上而不是 submission_page 上：配准是识别链路的产物（挂在识别出的页面上），
-- 与"学生排的页码"是两件事 —— 学生把第 2 页排到第 1 位，配准结果不该跟着换位置。
--
-- V9 建表时就留了 transform_matrix（3x3 矩阵按行展开），当前引擎不产出透视配准，
-- 那一列保持 NULL：写一个单位矩阵进去，下游会以为"已经做过透视校正"。
alter table document_page add column template_page_no int;
alter table document_page add column alignment_confidence decimal(5,4);

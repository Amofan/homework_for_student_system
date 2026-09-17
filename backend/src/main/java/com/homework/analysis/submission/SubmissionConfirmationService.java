package com.homework.analysis.submission;

import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 教师确认答案入库：把校对过的候选一次性写成正式数据。
 *
 * <p>这是整条答卷链路上**唯一**写 {@code student_answer} 的地方。前面所有环节
 * （上传、识别、物化候选、教师逐条校对）产出的都只是候选，只有走到这里，
 * 一份学生作答才变成"库里那套答案"。
 *
 * <p>为什么要有这一道门而不是"教师改一条就写一条"：答案一旦成为正式数据，成绩、AI 批改、
 * 学情统计就都以它为输入。逐条落库会让这些下游在一份只校对了一半的答卷上开始工作，
 * 而中途的分数是没有意义的——教师也没法解释"为什么第 3 题的分先出来了"。
 *
 * <p>确认是**整体**的：要么这份答卷的每一道题都落库，要么一道都不落。所以先做完整性检查，
 * 检查不过就一个字节都不写（同一个事务里，抛异常即整体回滚）。
 */
@Service
public class SubmissionConfirmationService {

    private final JdbcClient jdbc;
    private final SubmissionVersionService versions;
    private final AnswerExtractionService answers;

    SubmissionConfirmationService(JdbcClient jdbc, SubmissionVersionService versions,
                                  AnswerExtractionService answers) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.answers = answers;
    }

    /**
     * 确认这一版的作答入库。
     *
     * <p>可重复调用：已经确认过的版本再调一次不做任何写入，直接返回当前视图。
     * 这一点是必要的——教师点了确认之后网络断掉、或者刷新页面重新点了一次，
     * 不该得到 409 或者一份"第二次确认"的审计行。
     *
     * @return 确认之后的校对视图（{@code confirmed = true}）
     */
    @Transactional
    public AnswerReviewView confirm(long teacherId, long versionId) {
        SubmissionVersionService.VersionRef version = versions.requireForTeacher(teacherId, versionId);
        if (version.status().answersConfirmed()) {
            // 已经入库：不改任何东西。再写一遍会把 student_answer 的 updated_at 推到现在，
            // 让"教师什么时候确认的"这件事从库里消失。
            return answers.get(teacherId, versionId);
        }
        version.requireAnswerReviewable();

        List<CandidateRow> candidates = candidates(version.assignmentId(), versionId);
        List<QuestionRow> questions = questions(version.assignmentId());
        requireComplete(questions, candidates);
        requireReviewed(candidates);
        requireContent(candidates);

        for (CandidateRow candidate : candidates) {
            long answerId = upsertAnswer(version.submissionId(), candidate);
            insertAssets(answerId, version.versionId(), candidate);
        }
        versions.markAnswersConfirmed(version, teacherId);
        return answers.get(teacherId, versionId);
    }

    // ---------- 完整性检查 ----------

    /**
     * 每道作业题都恰好有一条候选。
     *
     * <p>"每道题恰好一条"是物化阶段建立的结构性质（按 {@code assignment_question} 建齐，
     * 唯一键保证不重复），所以这里的不完整只可能来自两件事：识别还没跑过（一条候选都没有），
     * 或者教师在确认之前往作业里加了题。两种都要拦下——按现有候选落库会让新加的那道题
     * 在库里没有答案，而批改会把它当成"学生没交"。
     */
    private static void requireComplete(List<QuestionRow> questions, List<CandidateRow> candidates) {
        if (candidates.isEmpty()) {
            throw new DomainException("ANSWER_CANDIDATES_MISSING",
                "这一版还没有识别结果，请先点识别", HttpStatus.CONFLICT);
        }
        Set<Long> withCandidate = new LinkedHashSet<>();
        candidates.forEach(candidate -> withCandidate.add(candidate.questionId()));
        List<String> missing = new ArrayList<>();
        for (QuestionRow question : questions) {
            if (!withCandidate.contains(question.questionId())) {
                missing.add(display(question));
            }
        }
        if (!missing.isEmpty()) {
            // 逐题报出缺的是哪几道：教师照着这份清单去补识别或改映射，
            // 只说"有题目缺候选"等于让他自己在几十道题里找。
            throw new DomainException("ANSWER_CANDIDATE_INCOMPLETE",
                "这些题还没有可确认的作答：" + String.join("、", missing)
                    + "。请重新识别或手工归类后再确认。", HttpStatus.CONFLICT);
        }
    }

    /**
     * 每条候选都已经被教师明确确认过。
     *
     * <p>这是"OCR 只产生候选"这条规则的最后一米：物化出来的候选默认 {@code PENDING}，
     * 没有教师的逐条确认就在这里停下。少确认一条就整体拒绝，而不是"跳过这条先确认其余的"——
     * 半确认的答卷入库之后，教师无法从库里看出哪道题是机器说的、哪道题是自己看过的。
     */
    private static void requireReviewed(List<CandidateRow> candidates) {
        List<String> pending = new ArrayList<>();
        for (CandidateRow candidate : candidates) {
            if (!AnswerExtractionService.REVIEW_CONFIRMED.equals(candidate.reviewStatus())) {
                pending.add(display(candidate));
            }
        }
        if (!pending.isEmpty()) {
            throw new DomainException("ANSWER_REVIEW_PENDING",
                "这些题还没有校对确认：" + String.join("、", pending)
                    + "。请逐条确认（含确认学生确实没作答的题）后再确认整份答卷。",
                HttpStatus.CONFLICT);
        }
    }

    /**
     * 标为"答了"的候选必须有内容。
     *
     * <p>{@code blank} 与"文本为空"不是同一件事：前者是教师看到的结论，
     * 后者可能是识别没读出字。一条既没标空白、又没有文字与公式的候选落库之后，
     * {@code student_answer.answer_content} 会是一条空串，读到它的人只会得出"学生写了空白"
     * 这个与教师判断相反的结论。所以要教师明确选一边。
     */
    private static void requireContent(List<CandidateRow> candidates) {
        for (CandidateRow candidate : candidates) {
            if (candidate.blank()) {
                continue;
            }
            boolean empty = isBlank(candidate.answerText()) && isBlank(candidate.answerLatex());
            if (empty) {
                throw new DomainException("ANSWER_CONTENT_EMPTY",
                    display(candidate) + "既没有作答内容也没有标为空白，请二选一后再确认",
                    HttpStatus.CONFLICT);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ---------- 落库 ----------

    /**
     * 写一条正式答案，返回它的 id。
     *
     * <p>用"先查后插/改"而不是 {@code insert ... on duplicate key}：{@code uk_answer_submission_question}
     * 只保证唯一，不保证"这一次的写入是教师刚确认的那一版"——本项目同时要跑 MySQL 与 H2，
     * 而两者的 upsert 语法与返回值语义并不一致（H2 的 {@code merge} 与 MySQL 的
     * {@code on duplicate key} 在"受影响行数"上给的东西不同）。先查后写把两边的行为拉平，
     * 代价是多一次查询，而确认是教师点一次的动作，不是热路径。
     */
    private long upsertAnswer(long submissionId, CandidateRow candidate) {
        String content = content(candidate);
        Long existingId = jdbc.sql("""
                select id from student_answer where submission_id = :submissionId and question_id = :questionId
                """)
            .param("submissionId", submissionId)
            .param("questionId", candidate.questionId())
            .query(Long.class)
            .optional()
            .orElse(null);
        if (existingId != null) {
            jdbc.sql("""
                    update student_answer set answer_content = :content, updated_at = current_timestamp(3)
                    where id = :id
                    """)
                .param("content", content)
                .param("id", existingId)
                .update();
            return existingId;
        }
        return GeneratedKeys.insert(jdbc, """
            insert into student_answer(submission_id, question_id, answer_content)
            values (:submissionId, :questionId, :content)
            """, statement -> statement
            .param("submissionId", submissionId)
            .param("questionId", candidate.questionId())
            .param("content", content));
    }

    /**
     * 正式答案的文本。
     *
     * <p>空白作答写成空串而不是 NULL：{@code answer_content} 是 {@code not null}，
     * 而"学生没作答"在这张表里的表达就是空串。标为空白是一个**已经确认的结论**，
     * 与"还没校对"不是同一种不确定。
     *
     * <p>文字与公式拼在一起落库：{@code student_answer} 只有一列正文，批改与展示都读它。
     * 公式在前、文字在后没有意义，所以按教师在校对界面上看到的顺序拼（先文字后公式）。
     */
    private static String content(CandidateRow candidate) {
        if (candidate.blank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!isBlank(candidate.answerText())) {
            parts.add(candidate.answerText().trim());
        }
        if (!isBlank(candidate.answerLatex())) {
            parts.add(candidate.answerLatex().trim());
        }
        return String.join("\n", parts);
    }

    /**
     * 把候选名下的答案图挂到正式答案上。
     *
     * <p><b>一条答案的答案图恒来自它当前那一版。</b>所以进来先清掉别的版本留下的行：
     * 学生退回重交之后重新确认时，答的是同一道题、{@code student_answer} 还是同一行，
     * 上一版裁出来的图若留着，复核页会并排显示两张 —— 其中一张来自学生已经不要的那一版，
     * 而教师看不出哪张是现在的。清理只删"答案 → 图"这条指针，图本身与它在旧版本页面上的
     * 区域都还在（旧版本连同它的页面一起冻结），追查历史照样追得到。
     *
     * <p>同一版的重复确认靠 {@code uk_answer_asset_file} 加"先查后插"兜住：
     * 这里删掉的是别的版本的行，本版的行原样留着，不会再插一份。
     *
     * <p>{@code document_region_id} 与 {@code submission_page_id} 都可空，且都只是来源线索：
     * 答案图是批改依据，必须留存；而区域与页面会随学生重交被替换掉，所以它们的外键是
     * {@code on delete set null}。这里如实写入来源，读的人能看到这张图是从哪一页哪一块裁的。
     */
    private void insertAssets(long answerId, long versionId, CandidateRow candidate) {
        jdbc.sql("""
                delete from student_answer_asset
                where answer_id = :answerId and submission_version_id <> :versionId
                """)
            .param("answerId", answerId)
            .param("versionId", versionId)
            .update();
        int sortOrder = 0;
        for (AssetSource source : assetSources(versionId, candidate)) {
            sortOrder++;
            Long exists = jdbc.sql("""
                    select id from student_answer_asset where answer_id = :answerId and file_id = :fileId
                    """)
                .param("answerId", answerId)
                .param("fileId", source.fileId())
                .query(Long.class)
                .optional()
                .orElse(null);
            if (exists != null) {
                continue;
            }
            int order = sortOrder;
            com.homework.analysis.shared.jdbc.GeneratedKeys.insert(jdbc, """
                insert into student_answer_asset(answer_id, submission_version_id, submission_page_id,
                    document_region_id, file_id, role, sort_order)
                values (:answerId, :versionId, :pageId, :regionId, :fileId, 'SOURCE_CROP', :sortOrder)
                """, statement -> statement
                .param("answerId", answerId)
                .param("versionId", versionId)
                .param("pageId", source.submissionPageId())
                .param("regionId", source.regionId())
                .param("fileId", source.fileId())
                .param("sortOrder", order));
        }
    }

    /**
     * 候选取源区域里**有裁剪图的那几块**，按页码与位置排序。
     *
     * <p>没有裁剪图的区域直接跳过：{@code student_answer_asset.file_id} 是必填外键，
     * 而裁剪失败的区域已经被 {@code ANSWER_CROP_FAILED} 提示过——教师要么重裁后再确认，
     * 要么就是在没有图的情况下确认了（文字仍然是核对过的依据）。两种都由教师决定，
     * 这里不替他把一条没有图可挂的"答案图"写进库。
     *
     * <p>按页与位置排序而不是按区域 id：跨页作答的两块必须按学生看的顺序排，
     * 而区域 id 的顺序取决于识别引擎返回的顺序，与页面顺序无关。
     */
    private List<AssetSource> assetSources(long versionId, CandidateRow candidate) {
        if (candidate.sourceRegionIds() == null || candidate.sourceRegionIds().isBlank()) {
            return List.of();
        }
        // 区域清单的解析留在 AnswerExtractionService：那是唯一写着"这张表里的 source_region_ids
        // 是一串 JSON 数字"的地方。在这里再解一遍，格式一变就会出现两处读法不一致。
        List<Long> regionIds = answers.regionIdsOf(candidate.sourceRegionIds());
        if (regionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select dr.id, dr.crop_file_id, dp.page_no, dr.y, dr.x,
                       (select sp.id from submission_page sp
                         where sp.submission_version_id = :versionId
                           and sp.document_id = dp.document_id
                           and sp.document_page_no = dp.page_no) as submission_page_id
                from document_region dr
                join document_page dp on dp.id = dr.page_id
                where dr.id in (:regionIds) and dr.crop_file_id is not null
                order by dp.page_no, dr.y, dr.x
                """)
            .param("versionId", versionId)
            .param("regionIds", regionIds)
            .query((rs, rowNum) -> new AssetSource(rs.getLong("id"), (Long) rs.getObject("crop_file_id"),
                (Long) rs.getObject("submission_page_id")))
            .list();
    }

    // ---------- 读取 ----------

    private List<QuestionRow> questions(long assignmentId) {
        return jdbc.sql("""
                select q.id, q.question_code, aq.question_order
                from assignment_question aq
                join question q on q.id = aq.question_id
                where aq.assignment_id = :assignmentId
                order by aq.question_order
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new QuestionRow(rs.getLong("id"), rs.getString("question_code"),
                rs.getInt("question_order")))
            .list();
    }

    /**
     * 这一版的候选，带上题号与作业顺序——报错时要指名道姓说"缺的是第几题"。
     *
     * <p>与 {@code AnswerExtractionService.candidates} 读的是同一批行：那边要的是校对视图的
     * 全部字段，这里只要落库与报错用得上的那几个。字段少一份就少一处会过期的读法，
     * 但**题号必须从 {@code question} 表 join 出来**，不靠候选自己存的那一份。
     */
    private List<CandidateRow> candidates(long assignmentId, long versionId) {
        return jdbc.sql("""
                select c.id, c.question_id, q.question_code, aq.question_order, c.order_no, c.answer_text,
                       c.answer_latex, c.blank, c.review_status, c.source_region_ids
                from submission_answer_candidate c
                join question q on q.id = c.question_id
                left join assignment_question aq on aq.assignment_id = :assignmentId
                     and aq.question_id = c.question_id
                where c.submission_version_id = :versionId
                order by c.order_no, c.id
                """)
            .param("assignmentId", assignmentId)
            .param("versionId", versionId)
            .query((rs, rowNum) -> new CandidateRow(rs.getLong("id"), rs.getLong("question_id"),
                rs.getString("question_code"), rs.getInt("question_order"), (Integer) rs.getObject("order_no"),
                rs.getString("answer_text"), rs.getString("answer_latex"), rs.getBoolean("blank"),
                rs.getString("review_status"), rs.getString("source_region_ids")))
            .list();
    }

    /**
     * 报给教师看的题目名称。
     *
     * <p>题号是教师唯一能对上试卷的标识，所以有题号就用题号；没有题号的回退到"第 N 题"。
     * 两条路都不通（题号为空且顺序为 0）时说"一道题"，至少比一个空字符串可读。
     */
    private static String display(QuestionRow question) {
        if (question.questionCode() != null && !question.questionCode().isBlank()) {
            return "第 " + question.questionCode() + " 题";
        }
        return question.questionOrder() > 0 ? "第 " + question.questionOrder() + " 题" : "一道题";
    }

    private static String display(CandidateRow candidate) {
        if (candidate.questionCode() != null && !candidate.questionCode().isBlank()) {
            return "第 " + candidate.questionCode() + " 题";
        }
        return candidate.orderNo() == null ? "一道题" : "第 " + candidate.orderNo() + " 题";
    }

    private record QuestionRow(long questionId, String questionCode, int questionOrder) {
    }

    private record CandidateRow(long candidateId, long questionId, String questionCode, int questionOrder,
                                Integer orderNo, String answerText, String answerLatex, boolean blank,
                                String reviewStatus, String sourceRegionIds) {
    }

    /** 一张要挂到正式答案上的答案图，以及它的来源（都可能为空指针）。 */
    private record AssetSource(long regionId, Long fileId, Long submissionPageId) {
    }
}

package com.homework.analysis.grading;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.question.QuestionType;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.submission.SubmissionVersionService;
import com.homework.analysis.submission.SubmissionVersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 启动一次批改：把库里那套已经确认过的作答变成分数与 AI 任务。
 *
 * <p>它是整条链路上第一条会把作答变成**分数**的地方，所以入口条件比别处严：
 * 只有"教师已经确认答案、而且这一版还是当前提交"的答卷才参与批改。
 * 两个条件缺一不可，原因不同：
 * <ul>
 *   <li><b>已确认</b>：{@code student_answer} 里的作答是教师确认候选时写下的。
 *       学生刚重交、教师还没校对时，库里的答案仍然是上一版的，按它算出来的分数
 *       对不上任何一版作答 —— 而分数会被学生看到、会进学情统计。</li>
 *   <li><b>当前提交</b>：学生重交后 {@code is_current} 立刻切到新版，而
 *       {@code submission.submission_version_id} 要等教师确认才挪。这两者之间的窗口里
 *       库里那套答案就是"学生已经不要了"的那一份，同样不能算分。</li>
 * </ul>
 *
 * <p><b>没有提交版本的作答照批。</b>{@code submission_version_id} 为空的提交来自整卷
 * Excel 导入那条旧链路（见 V11 迁移的注释：不回填假版本号，因为回填会让历史数据看起来
 * 像学生提交过）。那条链路里没有"重交"这回事，导入的答案就是唯一的一套，
 * 所以它按老规矩直接进批改。
 *
 * <p><b>批改开始即钉住。</b>参与批改的版本会被推成 {@code LOCKED}（走
 * {@link SubmissionVersionService#lockForTeacher}，与教师单独点"开始批改"是同一件事、
 * 同一行审计），而且发生在写任何结果之前：如果反过来，教师手上的分数就已经对应着
 * 一版学生还能改掉的作答了。
 *
 * <p><b>失效的结果不算"已经批过"。</b>退回会把基于旧作答的评分标成失效
 * （{@code invalidated_at}），学生的作答回来后要重批的正是这些答案。
 * {@code uk_grading_answer} 与 {@code uk_ai_task_answer} 都建在 {@code answer_id} 上，
 * 同一道题不可能再插第二行，所以重批是把旧行复活，而不是另起一行。
 */
@Service
public class GradingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GradingOrchestrator.class);

    private final JdbcClient jdbc;
    private final AssignmentService assignments;
    private final SubmissionVersionService versions;
    private final ObjectiveRuleGrader ruleGrader;
    private final ObjectMapper objectMapper;

    GradingOrchestrator(JdbcClient jdbc, AssignmentService assignments, SubmissionVersionService versions,
                        ObjectiveRuleGrader ruleGrader, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.versions = versions;
        this.ruleGrader = ruleGrader;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GradingRunResult grade(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        List<Long> gradeableVersions = gradeableVersionIds(assignmentId);
        // 先钉住再写结果。逐版走 lockForTeacher 而不是自己拼一句 update：那是"开始批改"
        // 的唯一一处定义（含审计行、含"已经是 LOCKED 就直接返回"），在这里再写一遍
        // 迟早会出现"某处锁了但没留痕"。
        for (long versionId : gradeableVersions) {
            versions.lockForTeacher(teacherId, versionId);
        }
        List<AnswerForGrading> answers = answers(assignmentId, gradeableVersions);
        requireAnythingToGrade(assignmentId, answers);

        int rule = 0;
        int ai = 0;
        int skipped = 0;
        for (AnswerForGrading answer : answers) {
            if (alreadyQueuedOrGraded(answer.id())) {
                skipped++;
            } else if (answer.type() == QuestionType.SOLUTION) {
                queueAiTask(answer.id());
                ai++;
            } else {
                RuleGrade result = ruleGrader.grade(answer.type(), answer.answer(),
                    parseAnswers(answer.acceptedAnswers()), answer.totalScore());
                writeRuleResult(answer.id(), result);
                rule++;
            }
        }
        if (skipped > 0) {
            log.info("批改跳过了已经有结果的答案：assignmentId={} 跳过={}", assignmentId, skipped);
        }
        return new GradingRunResult(rule, ai, skipped);
    }

    /**
     * 这次批改要处理的答案。
     *
     * <p>两种提交合在一起取：一种是"答案来自某个既确认又是当前提交的版本"，另一种是
     * "根本没有版本"（整卷 Excel 导入）。写成两段 {@code union} 而不是在 where 里堆
     * {@code or}，是为了让两段各自读起来就是一句完整的话。
     *
     * <p>顺序按 {@code student_answer.id}：批改结果与 AI 任务的插入顺序因此稳定，
     * 出问题时按 id 就能回放到同样的顺序。
     */
    private List<AnswerForGrading> answers(long assignmentId, List<Long> gradeableVersions) {
        // 没有可批版本时整段过滤条件换成 false，而不是给 in (...) 传一个空列表：
        // 空列表在很多驱动上直接是语法错误，而"没有任何版本可批"是完全正常的处境。
        String versionFilter = gradeableVersions.isEmpty()
            ? "false"
            : "s.submission_version_id in (:versionIds)";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select sa.id, sa.answer_content, q.type, q.total_score, q.accepted_answers
                from student_answer sa
                join submission s on s.id = sa.submission_id
                join question q on q.id = sa.question_id
                where s.assignment_id = :assignmentId
                  and (s.submission_version_id is null or %s)
                order by sa.id
                """.formatted(versionFilter))
            .param("assignmentId", assignmentId);
        if (!gradeableVersions.isEmpty()) {
            spec = spec.param("versionIds", gradeableVersions);
        }
        return spec
            .query((rs, rowNum) -> new AnswerForGrading(
                rs.getLong("id"), rs.getString("answer_content"), QuestionType.valueOf(rs.getString("type")),
                rs.getInt("total_score"), rs.getString("accepted_answers")))
            .list();
    }

    /**
     * 这份作业有没有能批的作答。
     *
     * <p>三种处境分开：一份答案都没有（作业刚发下去，什么都没有）直接返回零 —— 那不是错误，
     * 报 409 只会让教师以为点错了地方；有答案但一份都批不了，说明学生们交上来的答卷
     * 一份都还没确认，这时**必须**说出来：静默返回零，教师会以为批改跑过了，
     * 而学生那边永远等不到分数。
     */
    private void requireAnythingToGrade(long assignmentId, List<AnswerForGrading> gradeable) {
        if (!gradeable.isEmpty()) {
            return;
        }
        int answers = count("select count(*) from student_answer sa"
            + " join submission s on s.id = sa.submission_id where s.assignment_id = :assignmentId", assignmentId);
        if (answers == 0) {
            return;
        }
        throw new DomainException("SUBMISSION_NOT_CONFIRMED",
            "这份作业的答卷都还没有确认答案，请先在校对页逐份确认后再开始批改", HttpStatus.CONFLICT);
    }

    /**
     * 参与批改的版本：当前提交、而且答案已经确认入库。
     *
     * <p>状态集合从 {@link SubmissionVersionStatus} 取，不在这里抄一遍 {@code 'CONFIRMED', 'LOCKED'}：
     * 枚举改了而这句话没改时，症状是"答案确认了但批改说没有可批的答卷"。
     */
    private List<Long> gradeableVersionIds(long assignmentId) {
        return jdbc.sql("""
                select id from submission_version
                where assignment_id = :assignmentId and is_current = true
                  and status in (%s)
                order by id
                """.formatted(SubmissionVersionStatus.answersConfirmedNamesSql()))
            .param("assignmentId", assignmentId)
            .query(Long.class)
            .list();
    }

    /**
     * 这条答案是不是已经批过了。
     *
     * <p>失效的行（{@code invalidated_at} 非空）不算：那是上一轮批改在退回时被作废的结果，
     * 它对应的作答已经换了一份。把它算成"已批过"会让退回重交的学生永远拿不到新的分数 ——
     * 而且是静默的，教师只会看到"规则评分 0 条"。
     */
    private boolean alreadyQueuedOrGraded(long answerId) {
        int count = jdbc.sql("""
                select (select count(*) from grading_result
                         where answer_id = :answerId and invalidated_at is null)
                     + (select count(*) from ai_grading_task
                         where answer_id = :answerId and invalidated_at is null)
                """)
            .param("answerId", answerId).query(Integer.class).single();
        return count > 0;
    }

    /**
     * 把这条答案送进 AI 队列：失效的那一条复活，没有则新建。
     *
     * <p>复活时把上一轮的痕迹一起清掉（尝试次数、失败码、模型回包、失效标记）：
     * 留着尝试次数会让新任务一上来就被当成"重试了很多次"，而留下的模型回包
     * 对应的是另一份作答，读到它的人会以为模型批的是这一份。
     */
    private void queueAiTask(long answerId) {
        int revived = jdbc.sql("""
                update ai_grading_task
                set status = 'PENDING', attempt_count = 0, next_attempt_at = null, last_error_code = null,
                    sanitized_response = null, model_name = null, invalidated_at = null,
                    invalidated_reason = null, updated_at = current_timestamp(3)
                where answer_id = :answerId and invalidated_at is not null
                """)
            .param("answerId", answerId).update();
        if (revived == 0) {
            jdbc.sql("insert into ai_grading_task(answer_id, status, prompt_version)"
                    + " values (:answerId, 'PENDING', 'v1')")
                .param("answerId", answerId).update();
        }
    }

    /**
     * 写规则评分：失效的那一条复活，没有则新建。
     *
     * <p>{@code confirmed_score} 一起清空：它记录的是"教师确认过的那个分数"，
     * 而那是对上一份作答的确认。不清的话，一条刚回到待复核的结果会带着一个已确认的分数，
     * 学情统计按 {@code confirmed_score} 取数，会把上一版的分数算进这一版。
     */
    private void writeRuleResult(long answerId, RuleGrade result) {
        String explanation = result.errorType() == ErrorType.CORRECT
            ? "答案与可接受答案一致" : "答案与已配置答案不一致";
        String feedback = result.errorType() == ErrorType.CORRECT
            ? "回答正确" : "请检查计算过程和最终答案";
        int revived = jdbc.sql("""
                update grading_result
                set source = 'RULE', suggested_score = :score, confirmed_score = null,
                    error_type = :reason, teacher_explanation = :explanation,
                    student_feedback = :feedback, score_details = '[]', status = 'PENDING_REVIEW',
                    invalidated_at = null, invalidated_reason = null,
                    version = version + 1, updated_at = current_timestamp(3)
                where answer_id = :answerId and invalidated_at is not null
                """)
            .param("score", result.score())
            .param("reason", result.errorType().name())
            .param("explanation", explanation)
            .param("feedback", feedback)
            .param("answerId", answerId).update();
        if (revived > 0) {
            return;
        }
        jdbc.sql("""
                insert into grading_result(answer_id, source, suggested_score, error_type,
                                           teacher_explanation, student_feedback, score_details, status)
                values (:answerId, 'RULE', :score, :reason, :explanation, :feedback, '[]', 'PENDING_REVIEW')
                """)
            .param("answerId", answerId)
            .param("score", result.score())
            .param("reason", result.errorType().name())
            .param("explanation", explanation)
            .param("feedback", feedback)
            .update();
    }

    private int count(String sql, long assignmentId) {
        Integer value = jdbc.sql(sql).param("assignmentId", assignmentId).query(Integer.class).single();
        return value == null ? 0 : value;
    }

    private List<String> parseAnswers(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("题目可接受答案数据损坏", exception);
        }
    }

    private record AnswerForGrading(long id, String answer, QuestionType type, int totalScore,
                                    String acceptedAnswers) {}
}

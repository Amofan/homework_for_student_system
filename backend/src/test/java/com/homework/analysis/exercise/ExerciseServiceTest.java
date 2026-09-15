package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分层组题的领域规则测试。
 *
 * <p>刻意直接写库造数据，而不是走导入接口：本类验证的是"给定画像该怎么分层"，
 * 备课数据由别的测试负责，混在一起会让失败原因难以定位。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExerciseServiceTest {
    private static final long TEACHER = 11L;
    private static final long OTHER_TEACHER = 22L;
    private static final long CLASS_ID = 101L;
    private static final long OTHER_CLASS_ID = 102L;
    private static final long ASSIGNMENT_ID = 501L;
    private static final long OTHER_ASSIGNMENT_ID = 502L;
    private static final long FOREIGN_ASSIGNMENT_ID = 601L;
    private static final int SUBMISSION_ID = 9001;

    @Autowired ExerciseService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled)"
            + " values (1,'a','x','TEACHER',true),(2,'b','x','TEACHER',true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11,1,'教师甲'),(22,2,'教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name)"
            + " values (101,11,'C-1','七年级一班'),(102,11,'C-3','七年级二班'),(202,22,'C-2','八年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001,101,'001','张三')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status)"
            + " values (501,11,101,'第一单元作业','DRAFT'),(502,11,102,'第二单元作业','DRAFT'),"
            + "(601,22,202,'别人的作业','DRAFT')");
        jdbc.update("insert into submission(id, assignment_id, student_id) values (9001,501,1001)");
    }

    @Test
    void 按掌握度分层且每层最多五题() {
        seedThreeTiers(6);

        ExerciseSetView exercise = generate();

        assertThat(exercise.title()).isEqualTo("七年级一班 · 第一单元作业 分层练习");
        assertThat(exercise.status()).isEqualTo(ExerciseStatus.DRAFT);
        assertThat(exercise.notices()).isEmpty();
        assertThat(exercise.items()).hasSize(15);
        assertThat(namesOf(exercise, ExerciseTier.FOUNDATION)).containsOnly("一元一次方程");
        assertThat(namesOf(exercise, ExerciseTier.CORRECTION)).containsOnly("整式乘法");
        assertThat(namesOf(exercise, ExerciseTier.IMPROVEMENT)).containsOnly("全等三角形");
        assertThat(ordersOf(exercise, ExerciseTier.FOUNDATION)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void 层内按难度与题目编号稳定排序() {
        seedThreeTiers(6);

        // 基础巩固层优先取 BASIC，其次 MEDIUM，最后 ADVANCED；同难度内按题目编码升序。
        // 401=ADVANCED 402=BASIC 403=MEDIUM 404=ADVANCED 405=BASIC 406=MEDIUM
        assertThat(idsOf(generate(), ExerciseTier.FOUNDATION))
            .containsExactly(402L, 405L, 403L, 406L, 401L);
    }

    @Test
    void 同一道题不会出现在两个层级() {
        seedThreeTiers(3);

        List<Long> questionIds = generate().items().stream().map(ExerciseItemView::questionId).toList();

        assertThat(questionIds).doesNotHaveDuplicates();
    }

    @Test
    void 重复生成得到完全相同的练习单() {
        seedThreeTiers(6);

        ExerciseSetView first = generate();
        ExerciseSetView second = generate();

        assertThat(idsOf(second, ExerciseTier.FOUNDATION)).isEqualTo(idsOf(first, ExerciseTier.FOUNDATION));
        assertThat(idsOf(second, ExerciseTier.CORRECTION)).isEqualTo(idsOf(first, ExerciseTier.CORRECTION));
        assertThat(idsOf(second, ExerciseTier.IMPROVEMENT)).isEqualTo(idsOf(first, ExerciseTier.IMPROVEMENT));
    }

    @Test
    void 题库不足时给出实际数量与中文提示() {
        knowledgePoint(301, "ALG-EQ", "一元一次方程");
        knowledgePoint(302, "ALG-MUL", "整式乘法");
        knowledgePoint(303, "GEO-CON", "全等三角形");
        question(401, 301, QuestionDifficulty.BASIC);
        question(402, 301, QuestionDifficulty.MEDIUM);
        question(411, 302, QuestionDifficulty.MEDIUM);
        question(412, 302, QuestionDifficulty.BASIC);
        question(413, 302, QuestionDifficulty.ADVANCED);
        question(421, 303, QuestionDifficulty.ADVANCED);
        assignSequentially(ASSIGNMENT_ID, 401, 402, 411, 412, 413, 421);
        confirm(401, 5);
        confirm(411, 7);
        confirm(421, 9);

        ExerciseSetView exercise = generate();

        // 每层只用自己的知识点，三层的题加起来就是题库全部，仍然凑不满每层 5 题：
        // 只给实际数量，既不用别层的题补足，也不编造题目。
        assertThat(exercise.items()).hasSize(6);
        assertThat(tierItems(exercise, ExerciseTier.FOUNDATION)).hasSize(2);
        assertThat(tierItems(exercise, ExerciseTier.CORRECTION)).hasSize(3);
        assertThat(tierItems(exercise, ExerciseTier.IMPROVEMENT)).hasSize(1);
        assertThat(namesOf(exercise, ExerciseTier.FOUNDATION)).containsOnly("一元一次方程");
        assertThat(namesOf(exercise, ExerciseTier.CORRECTION)).containsOnly("整式乘法");
        assertThat(namesOf(exercise, ExerciseTier.IMPROVEMENT)).containsOnly("全等三角形");
        assertThat(exercise.notices()).hasSize(3)
            .anyMatch(notice -> notice.contains("基础巩固层仅生成 2 题"))
            .anyMatch(notice -> notice.contains("方法纠错层仅生成 3 题"))
            .anyMatch(notice -> notice.contains("综合提升层仅生成 1 题"));
    }

    @Test
    void 本层题目不足时不借用其他层级的题目() {
        knowledgePoint(301, "ALG-EQ", "一元一次方程");
        knowledgePoint(303, "GEO-CON", "全等三角形");
        question(401, 301, QuestionDifficulty.BASIC);
        for (int index = 0; index < 6; index++) {
            question(421 + index, 303, QuestionDifficulty.ADVANCED);
        }
        assignSequentially(ASSIGNMENT_ID, 401, 421, 422, 423, 424, 425, 426);
        confirm(401, 5);
        confirm(421, 9);

        ExerciseSetView exercise = generate();

        // 基础巩固层只有 401 一道题，就该只出这一道。
        // 综合提升层拿满 5 题后剩下的 426 同样是提升层的题，绝不拿来填巩固层——
        // 巩固层是给最薄弱的学生降难度的，塞提升层的题会让分层名存实亡。
        assertThat(idsOf(exercise, ExerciseTier.FOUNDATION)).containsExactly(401L);
        assertThat(idsOf(exercise, ExerciseTier.IMPROVEMENT))
            .containsExactly(421L, 422L, 423L, 424L, 425L);
        // 方法纠错层没有任何知识点落在这个区间，也没有题可借，于是为空。
        assertThat(tierItems(exercise, ExerciseTier.CORRECTION)).isEmpty();
        assertThat(exercise.notices()).hasSize(2)
            .anyMatch(notice -> notice.contains("基础巩固层仅生成 1 题"))
            .anyMatch(notice -> notice.contains("方法纠错层没有可用题目"));
    }

    @Test
    void 没有已确认评分时拒绝生成() {
        knowledgePoint(301, "ALG-EQ", "一元一次方程");
        question(401, 301, QuestionDifficulty.BASIC);
        assignSequentially(ASSIGNMENT_ID, 401);
        // 模拟"已评分但教师尚未复核"：模型建议不能决定分层，否则建议一改练习单就变
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (401,9001,401,'作答')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
            + " score_details, status) values (401,401,'RULE',5,'CORRECT','{}','PENDING_REVIEW')");

        assertThatThrownBy(this::generate)
            .isInstanceOf(DomainException.class)
            .satisfies(exception -> assertThat(((DomainException) exception).code())
                .isEqualTo("EXERCISE_NO_CONFIRMED_RESULTS"));
    }

    @Test
    void 来源作业不属于所选班级时拒绝生成() {
        seedThreeTiers(6);

        assertThatThrownBy(() -> service.generate(TEACHER,
                new ExerciseGenerationCommand(CLASS_ID, OTHER_ASSIGNMENT_ID, null)))
            .isInstanceOf(DomainException.class)
            .hasMessage("来源作业不属于所选班级");
    }

    @Test
    void 跨教师访问班级作业与练习单都被拒绝() {
        seedThreeTiers(6);
        long exerciseId = generate().id();

        assertThatThrownBy(() -> service.generate(OTHER_TEACHER,
                new ExerciseGenerationCommand(CLASS_ID, ASSIGNMENT_ID, null))).hasMessage("班级不存在");
        assertThatThrownBy(() -> service.generate(TEACHER,
                new ExerciseGenerationCommand(CLASS_ID, FOREIGN_ASSIGNMENT_ID, null))).hasMessage("作业不存在");
        assertThatThrownBy(() -> service.requireOwned(OTHER_TEACHER, exerciseId)).hasMessage("练习单不存在");
        assertThatThrownBy(() -> service.approve(OTHER_TEACHER, exerciseId)).hasMessage("练习单不存在");
        assertThatThrownBy(() -> service.exportDocx(OTHER_TEACHER, exerciseId)).hasMessage("练习单不存在");
        assertThatThrownBy(() -> service.list(TEACHER, 202L)).hasMessage("班级不存在");
    }

    @Test
    void 草稿不能导出确认后才能导出() {
        seedThreeTiers(6);
        long exerciseId = generate().id();

        assertThatThrownBy(() -> service.exportDocx(TEACHER, exerciseId))
            .isInstanceOf(DomainException.class)
            .satisfies(exception -> assertThat(((DomainException) exception).code())
                .isEqualTo("EXERCISE_NOT_APPROVED"));

        ExerciseSetView approved = service.approve(TEACHER, exerciseId);

        assertThat(approved.status()).isEqualTo(ExerciseStatus.APPROVED);
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(service.exportDocx(TEACHER, exerciseId)).isNotEmpty();
    }

    @Test
    void 已确认的练习单不能重复确认() {
        seedThreeTiers(6);
        long exerciseId = generate().id();
        service.approve(TEACHER, exerciseId);

        assertThatThrownBy(() -> service.approve(TEACHER, exerciseId))
            .isInstanceOf(DomainException.class)
            .satisfies(exception -> assertThat(((DomainException) exception).code())
                .isEqualTo("EXERCISE_NOT_DRAFT"));
    }

    @Test
    void 按班级列出练习单且只列出本人的() {
        seedThreeTiers(6);
        long exerciseId = generate().id();

        assertThat(service.list(TEACHER, CLASS_ID)).singleElement()
            .satisfies(exercise -> assertThat(exercise.id()).isEqualTo(exerciseId));
        assertThat(service.list(TEACHER, OTHER_CLASS_ID)).isEmpty();
    }

    /** 分段边界取闭区间：0.60 与 0.80 都算方法纠错，只有高于 0.80 才算综合提升。 */
    @Test
    void 掌握度分段在边界上取闭区间() {
        assertThat(ExerciseService.tierOf(0.59)).isEqualTo(ExerciseTier.FOUNDATION);
        assertThat(ExerciseService.tierOf(0.60)).isEqualTo(ExerciseTier.CORRECTION);
        assertThat(ExerciseService.tierOf(0.80)).isEqualTo(ExerciseTier.CORRECTION);
        assertThat(ExerciseService.tierOf(0.81)).isEqualTo(ExerciseTier.IMPROVEMENT);
    }

    private ExerciseSetView generate() {
        return service.generate(TEACHER, new ExerciseGenerationCommand(CLASS_ID, ASSIGNMENT_ID, null));
    }

    private static List<ExerciseItemView> tierItems(ExerciseSetView exercise, ExerciseTier tier) {
        return exercise.items().stream().filter(item -> item.tier() == tier).toList();
    }

    private static List<Long> idsOf(ExerciseSetView exercise, ExerciseTier tier) {
        return tierItems(exercise, tier).stream().map(ExerciseItemView::questionId).toList();
    }

    private static List<String> namesOf(ExerciseSetView exercise, ExerciseTier tier) {
        return tierItems(exercise, tier).stream().map(ExerciseItemView::knowledgePointName).toList();
    }

    private static List<Integer> ordersOf(ExerciseSetView exercise, ExerciseTier tier) {
        return tierItems(exercise, tier).stream().map(ExerciseItemView::sortOrder).toList();
    }

    /**
     * 三个知识点分别落在三个层级，各配 {@code questionCount} 道题。
     *
     * <p>难度按题序循环成 ADVANCED、BASIC、MEDIUM，让"层内排序"有可断言的确定结果。
     * 每个知识点只造一条已确认评分，得分即掌握度：5/10、7/10、9/10。
     */
    private void seedThreeTiers(int questionCount) {
        knowledgePoint(301, "ALG-EQ", "一元一次方程");
        knowledgePoint(302, "ALG-MUL", "整式乘法");
        knowledgePoint(303, "GEO-CON", "全等三角形");
        questions(401, 301, questionCount);
        questions(411, 302, questionCount);
        questions(421, 303, questionCount);
        for (int index = 0; index < questionCount; index++) {
            int order = index + 1;
            assign(ASSIGNMENT_ID, 401 + index, order);
            assign(ASSIGNMENT_ID, 411 + index, 100 + order);
            assign(ASSIGNMENT_ID, 421 + index, 200 + order);
        }
        confirm(401, 5);
        confirm(411, 7);
        confirm(421, 9);
    }

    private void questions(long firstId, long knowledgePointId, int count) {
        QuestionDifficulty[] cycle = {
            QuestionDifficulty.ADVANCED, QuestionDifficulty.BASIC, QuestionDifficulty.MEDIUM
        };
        for (int index = 0; index < count; index++) {
            question(firstId + index, knowledgePointId, cycle[index % cycle.length]);
        }
    }

    private void knowledgePoint(long id, String code, String name) {
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active)"
            + " values (?,11,?,?,7,true)", id, code, name);
    }

    private void question(long id, long knowledgePointId, QuestionDifficulty difficulty) {
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, standard_answer,"
                + " total_score, difficulty, primary_knowledge_point_id, accepted_answers)"
                + " values (?,11,?,'FILL_BLANK',?,'标准答案',10,?,?,'[\"标准答案\"]')",
            id, "Q-" + id, "题目" + id, difficulty.name(), knowledgePointId);
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary)"
            + " values (?,?,true)", id, knowledgePointId);
    }

    /** 按传入顺序把题目挂到作业上，题序即数组下标加一。 */
    private void assignSequentially(long assignmentId, long... questionIds) {
        for (int index = 0; index < questionIds.length; index++) {
            assign(assignmentId, questionIds[index], index + 1);
        }
    }

    private void assign(long assignmentId, long questionId, int order) {
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order)"
            + " values (?,?,?)", assignmentId, questionId, order);
    }

    /** 造一条教师已确认的评分；题目总分固定 10，因此得分即该知识点的掌握度。 */
    private void confirm(long questionId, int earned) {
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (?,?,?,'作答')", questionId, SUBMISSION_ID, questionId);
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, confirmed_score,"
                + " error_type, score_details, status) values (?,?,'RULE',?,?,'CORRECT','{}','CONFIRMED')",
            questionId, questionId, earned, earned);
    }
}

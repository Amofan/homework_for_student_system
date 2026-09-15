package com.homework.analysis.exercise;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.RubricView;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class ExerciseRepository {
    private static final String INSERT_SET = """
        insert into exercise_set(teacher_id, class_id, source_assignment_id, title, status)
        values (?, ?, ?, ?, 'DRAFT')
        """;

    private static final String SELECT_SET = """
        select es.id, es.class_id, sc.name as class_name, es.source_assignment_id,
               a.title as assignment_title, es.title, es.status, es.created_at, es.approved_at
        from exercise_set es
        join school_class sc on sc.id = es.class_id
        join assignment a on a.id = es.source_assignment_id
        where es.teacher_id = :teacherId
        """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    ExerciseRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 来源作业中的候选题，按作业内题序返回。
     * 排序在数据库里固定下来，组题逻辑本身不再引入任何随机或时间因素，
     * 因此对同一份数据反复生成本层结果完全一致。
     */
    public List<ExerciseCandidate> candidates(long assignmentId) {
        return jdbc.sql("""
                select q.id, q.question_code, q.content, q.standard_answer, q.total_score, q.difficulty,
                       kp.id as knowledge_point_id, kp.name as knowledge_point_name
                from assignment_question aq
                join question q on q.id = aq.question_id
                join knowledge_point kp on kp.id = q.primary_knowledge_point_id
                where aq.assignment_id = :assignmentId and q.deleted_at is null
                order by aq.question_order
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new ExerciseCandidate(
                rs.getLong("id"), rs.getString("question_code"), rs.getString("content"),
                rs.getString("standard_answer"), rs.getInt("total_score"),
                QuestionDifficulty.valueOf(rs.getString("difficulty")),
                rs.getLong("knowledge_point_id"), rs.getString("knowledge_point_name")))
            .list();
    }

    public long insertSet(long teacherId, long classId, long assignmentId, String title) {
        return GeneratedKeys.insert(jdbcTemplate, INSERT_SET, teacherId, classId, assignmentId, title);
    }

    public void insertItem(long exerciseSetId, ExerciseItemView item) {
        jdbc.sql("""
                insert into exercise_item(exercise_set_id, question_id, tier, sort_order)
                values (:setId, :questionId, :tier, :sortOrder)
                """)
            .param("setId", exerciseSetId)
            .param("questionId", item.questionId())
            .param("tier", item.tier().name())
            .param("sortOrder", item.sortOrder())
            .update();
    }

    public Optional<ExerciseSetView> findOwned(long teacherId, long exerciseId) {
        return jdbc.sql(SELECT_SET + " and es.id = :id")
            .param("teacherId", teacherId)
            .param("id", exerciseId)
            .query((rs, rowNum) -> toView(rs))
            .optional();
    }

    public List<ExerciseSetView> listOwned(long teacherId, long classId) {
        return jdbc.sql(SELECT_SET + " and es.class_id = :classId order by es.id desc")
            .param("teacherId", teacherId)
            .param("classId", classId)
            .query((rs, rowNum) -> toView(rs))
            .list();
    }

    /** 只有仍处于 DRAFT 的行会被更新，返回 0 表示状态已被其他请求改掉。 */
    public int markApproved(long teacherId, long exerciseId) {
        return jdbc.sql("""
                update exercise_set
                set status = 'APPROVED', approved_at = current_timestamp(3), updated_at = current_timestamp(3)
                where id = :id and teacher_id = :teacherId and status = 'DRAFT'
                """)
            .param("id", exerciseId)
            .param("teacherId", teacherId)
            .update();
    }

    private ExerciseSetView toView(ResultSet rs) throws SQLException {
        long exerciseId = rs.getLong("id");
        Timestamp approved = rs.getTimestamp("approved_at");
        List<ExerciseItemView> items = items(exerciseId);
        return new ExerciseSetView(exerciseId, rs.getLong("class_id"), rs.getString("class_name"),
            rs.getLong("source_assignment_id"), rs.getString("assignment_title"),
            rs.getString("title"), ExerciseStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            approved == null ? null : approved.toInstant(),
            items, ExerciseSetView.shortageNotices(items));
    }

    private List<ExerciseItemView> items(long exerciseId) {
        return jdbc.sql("""
                select ei.tier, ei.sort_order, q.id, q.question_code, q.content, q.total_score,
                       q.difficulty, q.standard_answer,
                       kp.id as knowledge_point_id, kp.name as knowledge_point_name
                from exercise_item ei
                join question q on q.id = ei.question_id
                join knowledge_point kp on kp.id = q.primary_knowledge_point_id
                where ei.exercise_set_id = :setId
                """)
            .param("setId", exerciseId)
            .query((rs, rowNum) -> new ExerciseItemView(
                ExerciseTier.valueOf(rs.getString("tier")), rs.getInt("sort_order"),
                rs.getLong("id"), rs.getString("question_code"), rs.getString("content"),
                rs.getInt("total_score"), QuestionDifficulty.valueOf(rs.getString("difficulty")),
                rs.getLong("knowledge_point_id"), rs.getString("knowledge_point_name"),
                rs.getString("standard_answer"), List.of()))
            .list()
            .stream()
            .map(item -> new ExerciseItemView(item.tier(), item.sortOrder(), item.questionId(),
                item.questionCode(), item.content(), item.totalScore(), item.difficulty(),
                item.knowledgePointId(), item.knowledgePointName(), item.standardAnswer(),
                rubrics(item.questionId())))
            // 层级按 FOUNDATION、CORRECTION、IMPROVEMENT 的枚举顺序展示，而不是数据库的字母序
            .sorted(Comparator.comparingInt((ExerciseItemView item) -> item.tier().ordinal())
                .thenComparingInt(ExerciseItemView::sortOrder))
            .toList();
    }

    private List<RubricView> rubrics(long questionId) {
        return jdbc.sql("""
                select id, order_no, title, criteria, max_score from rubric_item
                where question_id = :questionId order by order_no
                """)
            .param("questionId", questionId)
            .query((rs, rowNum) -> new RubricView(rs.getLong("id"), rs.getInt("order_no"),
                rs.getString("title"), rs.getString("criteria"), rs.getInt("max_score")))
            .list();
    }
}

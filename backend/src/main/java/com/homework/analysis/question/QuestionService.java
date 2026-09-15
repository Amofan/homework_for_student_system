package com.homework.analysis.question;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;

@Service
public class QuestionService {
    private final JdbcClient jdbc;
    private final KnowledgePointService knowledgePoints;
    private final ObjectMapper objectMapper;

    QuestionService(JdbcClient jdbc, KnowledgePointService knowledgePoints, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.knowledgePoints = knowledgePoints;
        this.objectMapper = objectMapper;
    }

    public List<QuestionView> list(long teacherId) {
        return jdbc.sql("""
                select id, question_code, type, content, standard_answer, total_score, difficulty,
                       primary_knowledge_point_id, accepted_answers
                from question where teacher_id = :teacherId and deleted_at is null order by id desc
                """)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> toView(rs, teacherId))
            .list();
    }

    public QuestionView requireOwned(long teacherId, long questionId) {
        return jdbc.sql("""
                select id, question_code, type, content, standard_answer, total_score, difficulty,
                       primary_knowledge_point_id, accepted_answers
                from question where id = :id and teacher_id = :teacherId and deleted_at is null
                """)
            .param("id", questionId)
            .param("teacherId", teacherId)
            .query((rs, rowNum) -> toView(rs, teacherId))
            .optional()
            .orElseThrow(() -> new DomainException("QUESTION_NOT_FOUND", "题目不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public QuestionView create(long teacherId, QuestionCommand command) {
        validate(teacherId, command);
        String answersJson = writeAnswers(command.acceptedAnswers());
        try {
            jdbc.sql("""
                    insert into question(teacher_id, question_code, type, content, standard_answer,
                                         total_score, difficulty, primary_knowledge_point_id, accepted_answers)
                    values (:teacherId, :code, :type, :content, :standardAnswer,
                            :totalScore, :difficulty, :primaryKnowledgePointId, :acceptedAnswers)
                    """)
                .param("teacherId", teacherId)
                .param("code", command.questionCode().trim())
                .param("type", command.type().name())
                .param("content", command.content().trim())
                .param("standardAnswer", command.standardAnswer())
                .param("totalScore", command.totalScore())
                .param("difficulty", difficultyOf(command).name())
                .param("primaryKnowledgePointId", command.primaryKnowledgePointId())
                .param("acceptedAnswers", answersJson)
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("QUESTION_CODE_DUPLICATE", "题目编码已存在", HttpStatus.CONFLICT);
        }
        long questionId = jdbc.sql("""
                select id from question where teacher_id = :teacherId and question_code = :code
                """)
            .param("teacherId", teacherId)
            .param("code", command.questionCode().trim())
            .query(Long.class)
            .single();
        linkKnowledgePoints(questionId, command);
        insertRubrics(questionId, safeRubrics(command));
        return requireOwned(teacherId, questionId);
    }

    private void validate(long teacherId, QuestionCommand command) {
        knowledgePoints.requireActiveOwned(teacherId, command.primaryKnowledgePointId());
        List<Long> secondary = safeSecondary(command);
        if (secondary.contains(command.primaryKnowledgePointId()) || new HashSet<>(secondary).size() != secondary.size()) {
            throw new DomainException("KNOWLEDGE_POINT_DUPLICATE", "主、次知识点不能重复");
        }
        secondary.forEach(id -> knowledgePoints.requireActiveOwned(teacherId, id));
        List<String> answers = safeAnswers(command);
        List<RubricCommand> rubrics = safeRubrics(command);
        if (command.type() == QuestionType.SOLUTION) {
            if (rubrics.isEmpty() || rubrics.stream().mapToInt(RubricCommand::maxScore).sum() != command.totalScore()) {
                throw new DomainException("RUBRIC_SCORE_MISMATCH", "评分项分值之和必须等于题目总分");
            }
            if (rubrics.stream().map(RubricCommand::orderNo).distinct().count() != rubrics.size()) {
                throw new DomainException("RUBRIC_ORDER_DUPLICATE", "评分项顺序不能重复");
            }
        } else {
            if (answers.isEmpty()) {
                throw new DomainException("ACCEPTED_ANSWER_REQUIRED", "客观题必须配置可接受答案");
            }
            if (!rubrics.isEmpty()) {
                throw new DomainException("OBJECTIVE_RUBRIC_NOT_ALLOWED", "客观题不能配置过程评分项");
            }
        }
    }

    private void linkKnowledgePoints(long questionId, QuestionCommand command) {
        jdbc.sql("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (:questionId, :knowledgeId, true)")
            .param("questionId", questionId).param("knowledgeId", command.primaryKnowledgePointId()).update();
        safeSecondary(command).forEach(id -> jdbc.sql(
                "insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (:questionId, :knowledgeId, false)")
            .param("questionId", questionId).param("knowledgeId", id).update());
    }

    private void insertRubrics(long questionId, List<RubricCommand> rubrics) {
        rubrics.forEach(rubric -> jdbc.sql("""
                insert into rubric_item(question_id, order_no, title, criteria, max_score)
                values (:questionId, :orderNo, :title, :criteria, :maxScore)
                """)
            .param("questionId", questionId)
            .param("orderNo", rubric.orderNo())
            .param("title", rubric.title().trim())
            .param("criteria", rubric.criteria().trim())
            .param("maxScore", rubric.maxScore())
            .update());
    }

    private QuestionView toView(java.sql.ResultSet rs, long teacherId) throws java.sql.SQLException {
        long questionId = rs.getLong("id");
        List<Long> secondary = jdbc.sql("""
                select qkp.knowledge_point_id from question_knowledge_point qkp
                join knowledge_point kp on kp.id = qkp.knowledge_point_id
                where qkp.question_id = :questionId and qkp.is_primary = false and kp.teacher_id = :teacherId
                order by qkp.knowledge_point_id
                """)
            .param("questionId", questionId).param("teacherId", teacherId).query(Long.class).list();
        List<RubricView> rubrics = jdbc.sql("""
                select id, order_no, title, criteria, max_score from rubric_item
                where question_id = :questionId order by order_no
                """)
            .param("questionId", questionId)
            .query((rubricRs, rowNum) -> new RubricView(rubricRs.getLong("id"), rubricRs.getInt("order_no"),
                rubricRs.getString("title"), rubricRs.getString("criteria"), rubricRs.getInt("max_score")))
            .list();
        return new QuestionView(questionId, rs.getString("question_code"),
            QuestionType.valueOf(rs.getString("type")), rs.getString("content"),
            rs.getString("standard_answer"), rs.getInt("total_score"),
            QuestionDifficulty.valueOf(rs.getString("difficulty")),
            rs.getLong("primary_knowledge_point_id"), secondary,
            readAnswers(rs.getString("accepted_answers")), rubrics);
    }

    private String writeAnswers(List<String> answers) {
        try {
            return objectMapper.writeValueAsString(answers == null ? List.of() : answers);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法序列化可接受答案", exception);
        }
    }

    private List<String> readAnswers(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("题目答案数据损坏", exception);
        }
    }

    /** 未指定难度时按中等处理，避免历史请求因为缺少新字段而被拒绝。 */
    private static QuestionDifficulty difficultyOf(QuestionCommand command) {
        return command.difficulty() == null ? QuestionDifficulty.MEDIUM : command.difficulty();
    }

    private static List<Long> safeSecondary(QuestionCommand command) {
        return command.secondaryKnowledgePointIds() == null ? List.of() : command.secondaryKnowledgePointIds();
    }

    private static List<String> safeAnswers(QuestionCommand command) {
        return command.acceptedAnswers() == null ? List.of() : command.acceptedAnswers();
    }

    private static List<RubricCommand> safeRubrics(QuestionCommand command) {
        return command.rubricItems() == null ? List.of() : command.rubricItems();
    }
}

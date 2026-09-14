package com.homework.analysis.grading;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.question.QuestionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class GradingOrchestrator {
    private final JdbcClient jdbc;
    private final AssignmentService assignments;
    private final ObjectiveRuleGrader ruleGrader;
    private final ObjectMapper objectMapper;

    GradingOrchestrator(JdbcClient jdbc, AssignmentService assignments,
                        ObjectiveRuleGrader ruleGrader, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.ruleGrader = ruleGrader;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GradingRunResult grade(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        List<AnswerForGrading> answers = jdbc.sql("""
                select sa.id, sa.answer_content, q.type, q.total_score, q.accepted_answers
                from student_answer sa
                join submission s on s.id = sa.submission_id
                join question q on q.id = sa.question_id
                where s.assignment_id = :assignmentId
                order by sa.id
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new AnswerForGrading(
                rs.getLong("id"), rs.getString("answer_content"), QuestionType.valueOf(rs.getString("type")),
                rs.getInt("total_score"), rs.getString("accepted_answers")))
            .list();
        int rule = 0;
        int ai = 0;
        int skipped = 0;
        for (AnswerForGrading answer : answers) {
            if (alreadyQueuedOrGraded(answer.id())) {
                skipped++;
            } else if (answer.type() == QuestionType.SOLUTION) {
                jdbc.sql("insert into ai_grading_task(answer_id, status, prompt_version) values (:answerId, 'PENDING', 'v1')")
                    .param("answerId", answer.id()).update();
                ai++;
            } else {
                RuleGrade result = ruleGrader.grade(answer.type(), answer.answer(), parseAnswers(answer.acceptedAnswers()), answer.totalScore());
                jdbc.sql("""
                        insert into grading_result(answer_id, source, suggested_score, error_type,
                                                   teacher_explanation, student_feedback, score_details, status)
                        values (:answerId, 'RULE', :score, :reason, :explanation, :feedback, '[]', 'PENDING_REVIEW')
                        """)
                    .param("answerId", answer.id())
                    .param("score", result.score())
                    .param("reason", result.reasonCode())
                    .param("explanation", result.reasonCode().equals("CORRECT") ? "答案与可接受答案一致" : "答案与已配置答案不一致")
                    .param("feedback", result.reasonCode().equals("CORRECT") ? "回答正确" : "请检查计算过程和最终答案")
                    .update();
                rule++;
            }
        }
        return new GradingRunResult(rule, ai, skipped);
    }

    private boolean alreadyQueuedOrGraded(long answerId) {
        int count = jdbc.sql("""
                select (select count(*) from grading_result where answer_id = :answerId)
                     + (select count(*) from ai_grading_task where answer_id = :answerId)
                """)
            .param("answerId", answerId).query(Integer.class).single();
        return count > 0;
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

package com.homework.analysis.evaluation;

import com.homework.analysis.assignment.AssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class EvaluationExportService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationExportService.class);

    private final JdbcClient jdbc;
    private final AssignmentService assignments;

    EvaluationExportService(JdbcClient jdbc, AssignmentService assignments) {
        this.jdbc = jdbc;
        this.assignments = assignments;
    }

    /**
     * 导出某次作业里可评测的评分样本。
     *
     * <p>只导出教师已复核、且来源是模型的样本：论文比较的是模型建议与教师最终判定，
     * 规则评分和待复核结果都提供不了这一对值。耗时与令牌取自各自的原始记录，
     * 缺就是缺——不填 0，否则省时比例会被算得虚高。
     */
    public String exportGradingCases(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        List<GradingCaseRow> cases = new ArrayList<>();
        int skipped = 0;
        for (ReviewedCase reviewed : reviewedCases(assignmentId)) {
            if (reviewed.aiErrorType() == null) {
                // 模型原始错因缺失：这行记录生成于 V7 之前，教师复核已经覆盖了 error_type，
                // 模型当初判成什么再也查不回来。评测脚本要求 ai_error_type 非空，
                // 与其塞一个编造的标签，不如剔除并让调用方看得见剔了多少条。
                skipped++;
                continue;
            }
            cases.add(new GradingCaseRow("answer-" + reviewed.answerId(), reviewed.totalScore(),
                reviewed.teacherScore(), reviewed.aiScore(), reviewed.teacherErrorType(),
                reviewed.aiErrorType(), reviewed.teacherModified(), reviewed.teacherSeconds(),
                reviewed.aiSeconds(), reviewed.inputTokens(), reviewed.outputTokens()));
        }
        if (skipped > 0) {
            log.warn("评测导出跳过了缺少模型原始错因的样本：assignmentId={} 跳过 {} 条", assignmentId, skipped);
        }
        return GradingCaseCsv.render(cases);
    }

    private List<ReviewedCase> reviewedCases(long assignmentId) {
        return jdbc.sql("""
                select gr.answer_id, gr.suggested_score, gr.ai_error_type, q.total_score,
                       tr.final_score, tr.final_error_type, tr.decision,
                       tr.teacher_seconds, t.ai_seconds, t.input_tokens, t.output_tokens
                from grading_result gr
                join student_answer sa on sa.id = gr.answer_id
                join submission sub on sub.id = sa.submission_id
                join question q on q.id = sa.question_id
                join teacher_review tr on tr.result_id = gr.id
                left join ai_grading_task t on t.answer_id = gr.answer_id
                where sub.assignment_id = :assignmentId and gr.source = 'AI'
                order by gr.answer_id
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new ReviewedCase(
                rs.getLong("answer_id"), rs.getInt("total_score"), rs.getInt("final_score"),
                rs.getInt("suggested_score"), rs.getString("final_error_type"),
                // 只有原样接受模型建议才算“没改”；修改与驳回都算教师改动了模型输出
                rs.getString("ai_error_type"), !"ACCEPT".equals(rs.getString("decision")),
                decimal(rs.getBigDecimal("teacher_seconds")), decimal(rs.getBigDecimal("ai_seconds")),
                integer(rs.getObject("input_tokens")), integer(rs.getObject("output_tokens"))))
            .list();
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private record ReviewedCase(long answerId, int totalScore, int teacherScore, int aiScore,
                                String teacherErrorType, String aiErrorType, boolean teacherModified,
                                Double teacherSeconds, Double aiSeconds,
                                Integer inputTokens, Integer outputTokens) {}
}

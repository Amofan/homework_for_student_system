package com.homework.analysis.analytics;

import com.homework.analysis.assignment.AssignmentService;
import com.homework.analysis.classroom.ClassroomService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalyticsService {
    private final JdbcClient jdbc;
    private final ClassroomService classrooms;
    private final AssignmentService assignments;

    AnalyticsService(JdbcClient jdbc, ClassroomService classrooms, AssignmentService assignments) {
        this.jdbc = jdbc;
        this.classrooms = classrooms;
        this.assignments = assignments;
    }

    public List<KnowledgeMastery> mastery(long teacherId, long classId, long assignmentId) {
        classrooms.get(teacherId, classId);
        var assignment = assignments.requireOwned(teacherId, assignmentId);
        if (assignment.classId() != classId) return List.of();
        return jdbc.sql("""
                select kp.id, kp.code, kp.name,
                       sum(gr.confirmed_score) as earned_score,
                       sum(q.total_score) as possible_score,
                       count(*) as answer_count
                from grading_result gr
                join student_answer sa on sa.id = gr.answer_id
                join submission sub on sub.id = sa.submission_id
                join question q on q.id = sa.question_id
                join knowledge_point kp on kp.id = q.primary_knowledge_point_id
                where sub.assignment_id = :assignmentId and gr.status = 'CONFIRMED'
                group by kp.id, kp.code, kp.name
                order by kp.code
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> {
                long earned = rs.getLong("earned_score");
                long possible = rs.getLong("possible_score");
                double ratio = possible == 0 ? 0 : (double) earned / possible;
                return new KnowledgeMastery(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                    earned, possible, ratio, rs.getLong("answer_count"));
            }).list();
    }

    public List<ErrorRanking> errorRanking(long teacherId, long assignmentId) {
        assignments.requireOwned(teacherId, assignmentId);
        return jdbc.sql("""
                select gr.error_type, count(*) as error_count
                from grading_result gr
                join student_answer sa on sa.id = gr.answer_id
                join submission sub on sub.id = sa.submission_id
                where sub.assignment_id = :assignmentId and gr.status = 'CONFIRMED'
                  and gr.error_type <> 'CORRECT'
                group by gr.error_type order by error_count desc, gr.error_type
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> new ErrorRanking(rs.getString("error_type"), rs.getLong("error_count")))
            .list();
    }
}

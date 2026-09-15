package com.homework.analysis.assignment;

import com.homework.analysis.shared.importing.ImportError;
import com.homework.analysis.shared.importing.ImportResult;
import com.homework.analysis.shared.importing.ParsedWorkbook;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SubmissionImportService {
    private final AnswerWorkbookParser parser;
    private final AssignmentService assignments;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SubmissionImportService(AnswerWorkbookParser parser, AssignmentService assignments, JdbcClient jdbc,
                            PlatformTransactionManager transactionManager) {
        this.parser = parser;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImportResult importWorkbook(long teacherId, long assignmentId, InputStream input) throws IOException {
        AssignmentView assignment = assignments.requireOwned(teacherId, assignmentId);
        ParsedWorkbook<AnswerWorkbookParser.AnswerRow> parsed = parser.parse(input);
        if (!parsed.errors().isEmpty()) return ImportResult.failure(parsed.errors());

        Map<String, Long> students = jdbc.sql("""
                select student_no, id from student where class_id = :classId and deleted_at is null
                """)
            .param("classId", assignment.classId())
            .query((rs, rowNum) -> Map.entry(rs.getString("student_no"), rs.getLong("id")))
            .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, Long> questions = jdbc.sql("""
                select q.question_code, q.id from assignment_question aq
                join question q on q.id = aq.question_id
                where aq.assignment_id = :assignmentId and q.deleted_at is null
                """)
            .param("assignmentId", assignmentId)
            .query((rs, rowNum) -> Map.entry(rs.getString("question_code"), rs.getLong("id")))
            .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        List<ImportError> errors = new ArrayList<>();
        Set<String> rowKeys = new HashSet<>();
        for (var row : parsed.rows()) {
            if (!students.containsKey(row.studentNo())) {
                errors.add(error(row.row(), "student_no", "STUDENT_NOT_FOUND", "学生不属于该作业班级"));
            }
            if (!questions.containsKey(row.questionCode())) {
                errors.add(error(row.row(), "question_code", "QUESTION_NOT_IN_ASSIGNMENT", "题目不属于该作业"));
            }
            if (!rowKeys.add(row.studentNo() + "\u0000" + row.questionCode())) {
                errors.add(error(row.row(), "row", "ANSWER_DUPLICATE", "学生与题目的组合重复"));
            }
        }
        if (!errors.isEmpty()) return ImportResult.failure(errors);
        return transactions.execute(status -> persist(assignmentId, parsed.rows(), students, questions));
    }

    private ImportResult persist(long assignmentId, List<AnswerWorkbookParser.AnswerRow> rows,
                                 Map<String, Long> students, Map<String, Long> questions) {
        Map<Long, Long> submissions = new HashMap<>();
        for (var row : rows) {
            long studentId = students.get(row.studentNo());
            long submissionId = submissions.computeIfAbsent(studentId,
                ignored -> findOrCreateSubmission(assignmentId, studentId));
            jdbc.sql("""
                    insert into student_answer(submission_id, question_id, answer_content)
                    values (:submissionId, :questionId, :answer)
                    """)
                .param("submissionId", submissionId)
                .param("questionId", questions.get(row.questionCode()))
                .param("answer", row.answer())
                .update();
        }
        jdbc.sql("update assignment set status = 'IMPORTED', updated_at = current_timestamp(3) where id = :id")
            .param("id", assignmentId).update();
        return ImportResult.success(rows.size());
    }

    private long findOrCreateSubmission(long assignmentId, long studentId) {
        return jdbc.sql("""
                select id from submission where assignment_id = :assignmentId and student_id = :studentId
                """)
            .param("assignmentId", assignmentId).param("studentId", studentId)
            .query(Long.class).optional()
            .orElseGet(() -> GeneratedKeys.insert(jdbc, """
                insert into submission(assignment_id, student_id, status)
                values (:assignmentId, :studentId, 'IMPORTED')
                """, statement -> statement
                .param("assignmentId", assignmentId).param("studentId", studentId)));
    }

    private static ImportError error(int row, String field, String code, String message) {
        return new ImportError("answers", row, field, code, message);
    }
}

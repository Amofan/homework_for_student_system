package com.homework.analysis.analytics;

import com.homework.analysis.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsReviewApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        jdbc.update("delete from grading_audit");
        jdbc.update("delete from teacher_review");
        jdbc.update("delete from grading_result");
        jdbc.update("delete from ai_grading_task");
        jdbc.update("delete from student_answer");
        jdbc.update("delete from submission");
        jdbc.update("delete from assignment_question");
        jdbc.update("delete from assignment");
        jdbc.update("delete from rubric_item");
        jdbc.update("delete from question_knowledge_point");
        jdbc.update("delete from question");
        jdbc.update("delete from knowledge_point");
        jdbc.update("delete from student");
        jdbc.update("delete from school_class");
        jdbc.update("delete from teacher");
        jdbc.update("delete from app_user");
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C1', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三'), (1002, 101, '002', '李四')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '一元一次方程', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '[\"2\"]'), (402, 11, 'Q2', 'FILL_BLANK', '题2', 10, 301, '[\"3\"]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401,301,true),(402,301,true)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '作业', 'IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501,401,1),(501,402,2)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601,501,1001,'IMPORTED'),(602,501,1002,'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (611,601,401,'2'),(612,602,402,'4')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type, score_details, status) values (701,611,'AI',8,'METHOD_ERROR','[]','PENDING_REVIEW'),(702,612,'RULE',6,'ANSWER_MISMATCH','[]','PENDING_REVIEW')");
    }

    @Test
    void analyticsIgnoreUnconfirmedResultsAndIncludeReviewedResult() throws Exception {
        mvc.perform(post("/api/grading/results/702/review")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ACCEPT\",\"feedback\":\"请复习计算\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.finalScore").value(6));

        mvc.perform(get("/api/analytics/classes/101/mastery?assignmentId=501")
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].earnedScore").value(6))
            .andExpect(jsonPath("$.data[0].possibleScore").value(10))
            .andExpect(jsonPath("$.data[0].answerCount").value(1))
            .andExpect(jsonPath("$.data[0].masteryRatio").value(0.6));

        mvc.perform(get("/api/grading/assignments/501/review-queue").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].resultId").value(701));
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }
}

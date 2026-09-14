package com.homework.analysis.question;

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
class QuestionApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        jdbc.update("delete from rubric_item");
        jdbc.update("delete from question_knowledge_point");
        jdbc.update("delete from question");
        jdbc.update("delete from knowledge_point");
        jdbc.update("delete from student");
        jdbc.update("delete from school_class");
        jdbc.update("delete from teacher");
        jdbc.update("delete from app_user");
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG-EQ', '一元一次方程', 7, true), (302, 22, 'GEO', '几何', 7, true)");
    }

    @Test
    void createsSolutionQuestionWithOrderedRubric() throws Exception {
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-001","type":"SOLUTION","content":"解方程 $2x+1=5$", "standardAnswer":"x=2", "totalScore":10,
                     "primaryKnowledgePointId":301,"secondaryKnowledgePointIds":[],"acceptedAnswers":[],
                     "rubricItems":[{"orderNo":1,"title":"列方程","criteria":"方程正确","maxScore":4},{"orderNo":2,"title":"求解","criteria":"过程正确","maxScore":6}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.questionCode").value("Q-001"))
            .andExpect(jsonPath("$.data.rubricItems.length()").value(2));

        mvc.perform(get("/api/questions").header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void rejectsRubricScoreMismatchAndForeignKnowledgePoint() throws Exception {
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-BAD","type":"SOLUTION","content":"题目","standardAnswer":"答案","totalScore":10,
                     "primaryKnowledgePointId":301,"secondaryKnowledgePointIds":[],"acceptedAnswers":[],
                     "rubricItems":[{"orderNo":1,"title":"步骤","criteria":"正确","maxScore":9}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("RUBRIC_SCORE_MISMATCH"));

        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-FOREIGN","type":"FILL_BLANK","content":"填空","standardAnswer":"2","totalScore":5,
                     "primaryKnowledgePointId":302,"secondaryKnowledgePointIds":[],"acceptedAnswers":["2"],"rubricItems":[]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_POINT_INVALID"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}

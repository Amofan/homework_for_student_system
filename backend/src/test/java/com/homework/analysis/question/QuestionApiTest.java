package com.homework.analysis.question;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
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
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (22, 2, '教师乙')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values"
            + " (301, 11, 'ALG-EQ', '一元一次方程', 7, true),"
            + " (302, 22, 'GEO', '几何', 7, true),"
            + " (303, 11, 'ALG-OLD', '已停用知识点', 7, false)");
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
    void rejectsRubricScoreMismatch() throws Exception {
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
    }

    @Test
    void anotherTeachersKnowledgePointIsHiddenAsNotFound() throws Exception {
        // 302 属于教师乙。返回 404 而不是 400：若两者的状态码不同，
        // 单凭状态码就能试探出哪些知识点编号存在。
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-FOREIGN","type":"FILL_BLANK","content":"填空","standardAnswer":"2","totalScore":5,
                     "primaryKnowledgePointId":302,"secondaryKnowledgePointIds":[],"acceptedAnswers":["2"],"rubricItems":[]}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_POINT_NOT_FOUND"));
    }

    @Test
    void foreignSecondaryKnowledgePointIsAlsoHiddenAsNotFound() throws Exception {
        // 主知识点走的是同一个校验方法，但分支不同，必须单独确认
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-FOREIGN-2","type":"FILL_BLANK","content":"填空","standardAnswer":"2","totalScore":5,
                     "primaryKnowledgePointId":301,"secondaryKnowledgePointIds":[302],"acceptedAnswers":["2"],"rubricItems":[]}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_POINT_NOT_FOUND"));

        mvc.perform(get("/api/questions").header("Authorization", bearerFor(11)))
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void inactiveOwnKnowledgePointIsAValidationErrorNotAMissingResource() throws Exception {
        // 303 是自己的知识点，但已停用。这不泄露别人的任何信息，而且是教师能改回来的输入问题，
        // 所以要给出可操作的原因，不能也压成 404。
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-INACTIVE","type":"FILL_BLANK","content":"填空","standardAnswer":"2","totalScore":5,
                     "primaryKnowledgePointId":303,"secondaryKnowledgePointIds":[],"acceptedAnswers":["2"],"rubricItems":[]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_POINT_INACTIVE"));
    }

    /**
     * 没有配图的题目返回空数组，而不是缺字段。
     *
     * <p>前端是按 {@code item.assets.filter(...)} 直接取用的：字段缺失只会在渲染时炸，
     * 而"没有配图"与"空数组"本来就是同一件事，不必让每个调用方再判一次空。
     *
     * <p>同时确认 {@code content} 原样返回：题干里的公式是纯文本，服务端不解析、不改写，
     * 排版完全交给前端，否则 KaTeX 的定界符会被服务端处理得面目全非。
     */
    @Test
    void 没有配图的题目返回空数组且题干文本原样保留() throws Exception {
        mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-PLAIN","type":"FILL_BLANK","content":"计算 $2x+1=5$ 中的 x","standardAnswer":"2","totalScore":5,
                     "primaryKnowledgePointId":301,"secondaryKnowledgePointIds":[],"acceptedAnswers":["2"],"rubricItems":[]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assets").isArray())
            .andExpect(jsonPath("$.data.assets.length()").value(0))
            .andExpect(jsonPath("$.data.content").value("计算 $2x+1=5$ 中的 x"));
    }

    /** 配图按角色与顺序返回：同一种角色内先看的图排前面。 */
    @Test
    void 题目配图按角色与顺序返回() throws Exception {
        String body = mvc.perform(post("/api/questions")
                .header("Authorization", bearerFor(11))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"questionCode":"Q-FIGURE","type":"SOLUTION","content":"看图作答","standardAnswer":"略","totalScore":10,
                     "primaryKnowledgePointId":301,"secondaryKnowledgePointIds":[],"acceptedAnswers":[],
                     "rubricItems":[{"orderNo":1,"title":"步骤","criteria":"正确","maxScore":10}]}
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long questionId = objectMapper.readTree(body).get("data").get("id").asLong();

        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (7001, 11, 'teachers/11/uploads/crop-1.png', '题图.png', 'image/png', 128, ?)
            """, "d".repeat(64));
        jdbc.update("""
            insert into stored_file(id, teacher_id, storage_key, original_name, mime_type, size_bytes, sha256)
            values (7002, 11, 'teachers/11/uploads/crop-2.png', '来源.png', 'image/png', 128, ?)
            """, "e".repeat(64));
        jdbc.update("""
            insert into question_asset(question_id, file_id, role, sort_order) values
              (?, 7001, 'STEM_FIGURE', 1), (?, 7002, 'SOURCE_CROP', 2)
            """, questionId, questionId);

        mvc.perform(get("/api/questions/" + questionId).header("Authorization", bearerFor(11)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assets.length()").value(2))
            .andExpect(jsonPath("$.data.assets[0].role").value("SOURCE_CROP"))
            .andExpect(jsonPath("$.data.assets[0].fileId").value(7002))
            .andExpect(jsonPath("$.data.assets[1].role").value("STEM_FIGURE"));
    }

    private String bearerFor(long teacherId) {
        return "Bearer " + jwtService.issue(teacherId, teacherId, "TEACHER");
    }
}

package com.homework.analysis.grading.review;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 教师复核接口的论文取数契约：复核耗时如何落库，以及复核改写错因时
 * 模型原始错因是否仍然保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherReviewApiTest {
    private static final long RESULT_ID = 701L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C1', '七年级一班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301, 11, 'ALG', '一元一次方程', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score, primary_knowledge_point_id, accepted_answers) values (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '[\"2\"]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401, 301, true)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501, 11, 101, '作业', 'IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501, 401, 1)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601, 501, 1001, 'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (611, 601, 401, '2')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type, ai_error_type,"
            + " score_details, status) values (701, 611, 'AI', 8, 'CALCULATION_ERROR', 'METHOD_ERROR', '[]', 'PENDING_REVIEW')");
    }

    @Test
    void 前端上报的复核耗时按秒记录() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"feedback\":\"请复习计算\",\"teacherSeconds\":12.345}");

        assertThat(teacherSeconds()).isEqualTo(12.345);
    }

    @Test
    void 未上报耗时时记为空而不是零() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"feedback\":\"请复习计算\"}");

        // 缺失与 0 必须区分：0 会被统计成“教师一瞬间批完”，把省时比例算得虚高
        assertThat(teacherSeconds()).isNull();
    }

    @Test
    void 耗时超出上限时记为空且不阻断复核() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"teacherSeconds\":7200}");

        assertThat(teacherSeconds()).isNull();
        assertThat(jdbc.queryForObject("select status from grading_result where id = 701", String.class))
            .isEqualTo("CONFIRMED");
    }

    @Test
    void 耗时是负数时记为空() throws Exception {
        review("{\"decision\":\"ACCEPT\",\"teacherSeconds\":-3}");

        assertThat(teacherSeconds()).isNull();
    }

    @Test
    void 复核改写错因但模型原始错因仍留在ai_error_type() throws Exception {
        review("{\"decision\":\"MODIFY\",\"finalScore\":6,\"errorType\":\"CONCEPT_ERROR\","
            + "\"reason\":\"概念理解有误\"}");

        Map<String, Object> row = jdbc.queryForMap(
            "select error_type, ai_error_type, confirmed_score from grading_result where id = 701");
        // error_type 被教师复核覆盖，ai_error_type 必须留着模型原判，否则错因一致率无从统计
        assertThat(row.get("error_type")).isEqualTo("CONCEPT_ERROR");
        assertThat(row.get("ai_error_type")).isEqualTo("METHOD_ERROR");
        assertThat(row.get("confirmed_score")).isEqualTo(6);
    }

    @Test
    void 修改复核不能写入标签全集以外的错因() throws Exception {
        mvc.perform(post("/api/grading/results/" + RESULT_ID + "/review")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"MODIFY","finalScore":6,"errorType":"手滑写错",\
                     "reason":"修正模型判断"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("REVIEW_ERROR_TYPE_INVALID"));

        assertThat(jdbc.queryForObject(
            "select status from grading_result where id = 701", String.class))
            .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 待复核的答案带着它的答案图() throws Exception {
        seedAnswerAssets();

        mvc.perform(get("/api/grading/assignments/501/review-queue").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].answerAssets.length()").value(2))
            // 教师判分看的是学生写的那几个字，所以第一张图必须能取到，而且要说清它从哪来。
            .andExpect(jsonPath("$.data[0].answerAssets[0].fileId").value(902))
            .andExpect(jsonPath("$.data[0].answerAssets[0].role").value("SOURCE_CROP"))
            .andExpect(jsonPath("$.data[0].answerAssets[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].answerAssets[0].x").value(0.1))
            .andExpect(jsonPath("$.data[0].answerAssets[0].sortOrder").value(1))
            // 跨页续写的第二块排在后头，界面要按学生写的顺序并排显示。
            .andExpect(jsonPath("$.data[0].answerAssets[1].sortOrder").value(2))
            .andExpect(jsonPath("$.data[0].answerAssets[1].pageNo").value(2));
    }

    @Test
    void 区域被重新配准换掉之后答案图还在只是没了位置() throws Exception {
        seedAnswerAssets();
        // 模板重新配准会替换区域，student_answer_asset.document_region_id 被置空
        // （外键是 on delete set null）。但答案图是批改依据，必须留着。
        jdbc.update("update student_answer_asset set document_region_id = null where answer_id = 611");
        jdbc.update("update student_answer_asset set submission_page_id = null where answer_id = 611");

        mvc.perform(get("/api/grading/assignments/501/review-queue").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].answerAssets.length()").value(2))
            .andExpect(jsonPath("$.data[0].answerAssets[0].fileId").value(902))
            // 位置没了就是没了：编一个默认框会让教师以为图是从那一块裁出来的。
            .andExpect(jsonPath("$.data[0].answerAssets[0].pageNo").doesNotExist())
            .andExpect(jsonPath("$.data[0].answerAssets[0].x").doesNotExist());
    }

    @Test
    void 没有答案图的答案照常排在待复核里() throws Exception {
        // 整卷 Excel 导入那条旧链路没有答案图。缺图不该让这一条从列表里消失，
        // 否则教师看不到它就等于漏批了一道题。
        mvc.perform(get("/api/grading/assignments/501/review-queue").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].answerAssets.length()").value(0));
    }

    private void review(String body) throws Exception {
        mvc.perform(post("/api/grading/results/" + RESULT_ID + "/review")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.resultId").value(RESULT_ID));
    }

    private Double teacherSeconds() {
        return jdbc.queryForObject("select teacher_seconds from teacher_review where result_id = 701", Double.class);
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }

    /**
     * 这道题的答案图：两页各一块，指的是一份已经确认入库的答卷。
     *
     * <p>直插 {@code student_answer_asset} 而不是跑一遍上传识别确认：
     * 那个链路已经在 {@code SubmissionConfirmationTest} 里冻结过了，这里要验的只是
     * "待复核列表怎么把答案图读出来"，把整条链路再铺一遍只会让这个类慢十倍。
     */
    private void seedAnswerAssets() {
        jdbc.update("insert into stored_file(id, teacher_id, storage_key, original_name, mime_type,"
            + " size_bytes, sha256) values"
            + " (900, 11, 'submission/page-1.png', '答卷.png', 'image/png', 3, 'a'),"
            + " (902, 11, 'answer/611-1.png', '答案图1.png', 'image/png', 3, 'b'),"
            + " (903, 11, 'answer/611-2.png', '答案图2.png', 'image/png', 3, 'c')");
        jdbc.update("insert into submission_version(id, submission_id, assignment_id, student_id, version_no,"
            + " status, is_current) values (1101, 601, 501, 1001, 1, 'LOCKED', true)");
        jdbc.update("update submission set submission_version_id = 1101 where id = 601");
        jdbc.update("insert into document_upload(id, document_kind, teacher_id, assignment_id, student_id,"
            + " submission_version_id, original_file_id, status, page_count)"
            + " values (700, 'STUDENT_SUBMISSION', 11, 501, 1001, 1101, 900, 'CONFIRMED', 2)");
        jdbc.update("insert into document_page(id, document_id, page_no, page_file_id)"
            + " values (701, 700, 1, 900), (702, 700, 2, 900)");
        jdbc.update("insert into submission_page(id, submission_version_id, page_no, document_id,"
            + " document_page_no, page_file_id, rotated_file_id) values"
            + " (8001, 1101, 1, 700, 1, 900, 900), (8002, 1101, 2, 700, 2, 900, 900)");
        jdbc.update("insert into document_region(id, page_id, region_type, x, y, width, height)"
            + " values (801, 701, 'ANSWER_BLOCK', 0.1, 0.2, 0.3, 0.1),"
            + " (802, 702, 'ANSWER_BLOCK', 0.1, 0.3, 0.3, 0.1)");
        jdbc.update("insert into student_answer_asset(id, answer_id, submission_version_id,"
            + " submission_page_id, document_region_id, file_id, role, sort_order) values"
            + " (901, 611, 1101, 8001, 801, 902, 'SOURCE_CROP', 1),"
            + " (904, 611, 1101, 8002, 802, 903, 'SOURCE_CROP', 2)");
    }
}

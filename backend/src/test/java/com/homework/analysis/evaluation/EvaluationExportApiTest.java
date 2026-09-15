package com.homework.analysis.evaluation;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 论文评测导出的取数契约。导出物要能被 {@code evaluation/evaluate_grading.py} 直接读入，
 * 因此这里逐列核对内容，而不只是看接口返回 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluationExportApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled)"
            + " values (1, 'a', 'x', 'TEACHER', true), (2, 'b', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲'), (12, 2, '教师乙')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name)"
            + " values (101, 11, 'C1', '七年级一班'), (102, 12, 'C2', '七年级二班')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001, 101, '001', '张三'),"
            + " (1002, 101, '002', '李四')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active)"
            + " values (301, 11, 'ALG', '一元一次方程', 7, true), (302, 12, 'GEO', '几何', 7, true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, total_score,"
            + " primary_knowledge_point_id, accepted_answers) values"
            + " (401, 11, 'Q1', 'FILL_BLANK', '题1', 10, 301, '[\"2\"]'),"
            + " (402, 11, 'Q2', 'FILL_BLANK', '题2', 10, 301, '[\"3\"]'),"
            + " (403, 12, 'Q3', 'FILL_BLANK', '题3', 10, 302, '[\"4\"]'),"
            + " (404, 11, 'Q4', 'FILL_BLANK', '题4', 10, 301, '[\"5\"]')");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status)"
            + " values (501, 11, 101, '作业', 'IMPORTED'), (502, 12, 102, '别班作业', 'IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order)"
            + " values (501, 401, 1), (501, 402, 2), (501, 404, 3), (502, 403, 1)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status)"
            + " values (601, 501, 1001, 'IMPORTED'), (602, 502, 1001, 'IMPORTED'), (603, 501, 1002, 'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (611, 601, 401, '2'), (612, 601, 402, '4'), (613, 602, 403, '4')");
        // 611：已复核的模型样本，耗时与用量齐全
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
            + " ai_error_type, score_details, status) values (701, 611, 'AI', 8, 'CALCULATION_ERROR',"
            + " 'METHOD_ERROR', '[]', 'CONFIRMED')");
        jdbc.update("insert into teacher_review(result_id, teacher_id, decision, final_score,"
            + " final_error_type, feedback, teacher_seconds) values (701, 11, 'MODIFY', 6,"
            + " 'CALCULATION_ERROR', '再看一遍', 42.5)");
        jdbc.update("insert into ai_grading_task(id, answer_id, status, model_name, prompt_version,"
            + " input_tokens, output_tokens, ai_seconds) values (801, 611, 'SUCCEEDED', 'qwen', 'v1',"
            + " 513, 604, 3.799)");
        // 612：已复核，但前端没能计时，任务也没返回用量——这些列必须是空而不是 0
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
            + " ai_error_type, score_details, status) values (702, 612, 'AI', 10, 'CORRECT',"
            + " 'CORRECT', '[]', 'CONFIRMED')");
        jdbc.update("insert into teacher_review(result_id, teacher_id, decision, final_score,"
            + " final_error_type, feedback) values (702, 11, 'ACCEPT', 10, 'CORRECT', '很好')");
        // prompt_version 有库默认值 'v1'，所以这一行不写它也会被补成 v1
        jdbc.update("insert into ai_grading_task(id, answer_id, status, model_name)"
            + " values (802, 612, 'SUCCEEDED', 'qwen')");
        // 616：教师驳回。驳回既不是采纳也不是普通修改，论文里要单独成一项，
        // 若被折叠进“修改”，教师与模型的分歧程度会被系统性低估
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (616, 603, 404, '5')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
            + " ai_error_type, score_details, status) values (705, 616, 'AI', 8, 'METHOD_ERROR',"
            + " 'METHOD_ERROR', '[]', 'CONFIRMED')");
        jdbc.update("insert into teacher_review(result_id, teacher_id, decision, final_score,"
            + " final_error_type, feedback, reason, teacher_seconds) values (705, 11, 'REJECT', 5,"
            + " 'CONCEPT_ERROR', '重做', '解法完全不对', 70.25)");
        jdbc.update("insert into ai_grading_task(id, answer_id, status, model_name, prompt_version,"
            + " input_tokens, output_tokens, ai_seconds) values (803, 616, 'SUCCEEDED', 'qwen',"
            + " 'v1', 530, 120, 4.5)");
    }

    @Test
    void 只导出已复核的模型样本且列序与脚本契约一致() throws Exception {
        String[] lines = export(501).split("\n");

        assertThat(lines[0]).isEqualTo("case_id,total_score,teacher_score,ai_score,teacher_error_type,"
            + "ai_error_type,teacher_modified,teacher_seconds,ai_seconds,input_tokens,output_tokens,"
            + "review_decision,model_name,prompt_version");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).isEqualTo("answer-611,10,6,8,CALCULATION_ERROR,METHOD_ERROR,true,"
            + "42.5,3.799,513,604,MODIFY,qwen,v1");
        // 中间四个空单元格：耗时与用量缺失时留空，不写 0
        assertThat(lines[2]).isEqualTo("answer-612,10,10,10,CORRECT,CORRECT,false,,,,,ACCEPT,qwen,v1");
        // 驳回原样保留，不被折叠成 MODIFY
        assertThat(lines[3]).isEqualTo("answer-616,10,5,8,CONCEPT_ERROR,METHOD_ERROR,true,"
            + "70.25,4.5,530,120,REJECT,qwen,v1");
    }

    @Test
    void 导出物不含学生身份信息() throws Exception {
        String csv = export(501);

        assertThat(csv).doesNotContain("张三", "1001", "七年级一班", "作业");
    }

    @Test
    void 未复核与规则评分的结果不进导出() throws Exception {
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content)"
            + " values (614, 603, 401, '9'), (615, 603, 402, '9')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, error_type,"
            + " ai_error_type, score_details, status) values"
            // 规则评分：没有模型建议，谈不上与模型比较。它也做了复核，
            // 因此只有来源过滤能把它挡住——少写这个条件本用例就会失败
            + " (703, 614, 'RULE', 0, 'ANSWER_MISMATCH', 'ANSWER_MISMATCH', '[]', 'CONFIRMED'),"
            // 待复核：教师还没给出最终判定，真值不存在
            + " (704, 615, 'AI', 8, 'METHOD_ERROR', 'METHOD_ERROR', '[]', 'PENDING_REVIEW')");
        jdbc.update("insert into teacher_review(result_id, teacher_id, decision, final_score,"
            + " final_error_type, feedback) values (703, 11, 'MODIFY', 3, 'ANSWER_MISMATCH', '看过程')");

        assertThat(export(501).split("\n")).hasSize(4);
    }

    @Test
    void 缺少模型原始错因的样本被剔除而不是编造标签() throws Exception {
        // V7 之前生成的结果没有 ai_error_type，教师复核已经覆盖了 error_type，原判无法还原
        jdbc.update("update grading_result set ai_error_type = null where id = 702");

        String body = mvc.perform(get("/api/evaluation/assignments/501/grading-cases.csv")
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            // 剔除了几条必须让页面看得见：只写服务端日志的话，教师导出后无从知道少了几条
            .andExpect(header().string("X-Evaluation-Reviewed", "3"))
            .andExpect(header().string("X-Evaluation-Exported", "2"))
            .andExpect(header().string("X-Evaluation-Skipped-Missing-Ai-Error", "1"))
            // 跨域部署时浏览器默认不把自定义响应头交给前端脚本，必须显式暴露
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                "Content-Disposition, X-Evaluation-Reviewed, X-Evaluation-Exported, "
                    + "X-Evaluation-Skipped-Missing-Ai-Error"))
            .andReturn().getResponse().getContentAsString();

        String[] lines = body.split("\n");

        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith("answer-611,");
        assertThat(lines[2]).startsWith("answer-616,");
    }

    @Test
    void 模型任务行缺失时溯源字段留空而不是编造模型名() throws Exception {
        // ai_grading_task 是左连接，任务行不存在时模型名与提示词版本都取不到。
        // 留空让脚本按“缺失”统计；写占位值会被当成某个真实模型的观测值。
        jdbc.update("delete from ai_grading_task where answer_id = 611");

        String[] lines = export(501).split("\n");

        assertThat(lines[1]).isEqualTo("answer-611,10,6,8,CALCULATION_ERROR,METHOD_ERROR,true,"
            + "42.5,,,,MODIFY,,");
    }

    @Test
    void 不能导出别的教师的作业() throws Exception {
        mvc.perform(get("/api/evaluation/assignments/502/grading-cases.csv").header("Authorization", bearer()))
            .andExpect(status().isNotFound());
    }

    @Test
    void 导出以附件下载且声明为UTF8() throws Exception {
        mvc.perform(get("/api/evaluation/assignments/501/grading-cases.csv").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"grading-cases-501.csv\""))
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"));
    }

    private String export(long assignmentId) throws Exception {
        return mvc.perform(get("/api/evaluation/assignments/" + assignmentId + "/grading-cases.csv")
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }
}

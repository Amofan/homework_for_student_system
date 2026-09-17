package com.homework.analysis.submission;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学生答卷接口：建版、传页、排序、旋转、删除、提交。
 *
 * <p>上传的是真正的 PNG 与 PDF：页面渲染、缩略图、宽高记录这几步只有走真实图片才会被执行，
 * 而它们正是"教师看到的第几页是不是学生排的第几页"的前提。用假字节省下的那点时间，
 * 换来的是这条路径在真实上传下第一次运行时才出问题。
 *
 * <p>种子数据里没有 {@code submission} 行：真实场景中学生第一次进入作答页才会建出来，
 * 用例跟着这条路径走，顺带覆盖了"提交记录与版本一起懒建"这件事。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentSubmissionApiTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final long ASSIGNMENT_ID = 501L;
    private static final long STUDENT_ID = 1001L;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("""
            insert into app_user(id, username, password_hash, role, enabled) values
              (1, 'teacher', 'x', 'TEACHER', true),
              (3, 'stu_one', 'x', 'STUDENT', true),
              (4, 'stu_two', 'x', 'STUDENT', true)
            """);
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name) values (101, 11, 'C-101', '七年级一班')");
        jdbc.update("""
            insert into student(id, class_id, student_no, name, user_id) values
              (1001, 101, '001', '张三', 3),
              (1002, 101, '002', '李四', 4)
            """);
        jdbc.update("""
            insert into assignment(id, teacher_id, class_id, title, status, published_at, due_at, version) values
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1),
              (502, 11, 101, '已经结束的作业', 'COMPLETED', current_timestamp(3), '2020-01-01 00:00:00', 3)
            """);
    }

    // ---------- 建版 ----------

    @Test
    void 建草稿是幂等的不会攒出一堆空版本() throws Exception {
        long first = draftVersionId();
        long second = draftVersionId();

        assertThat(second).isEqualTo(first);
        assertThat(countOf("select count(*) from submission_version")).isEqualTo(1);
        assertThat(countOf("select count(*) from submission where assignment_id = 501 and student_id = 1001"))
            .isEqualTo(1);
    }

    @Test
    void 草稿不出现在当前提交的位置上() throws Exception {
        long versionId = draftVersionId();

        mvc.perform(get("/api/student/submissions/" + versionId).header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.current").value(false))
            .andExpect(jsonPath("$.data.editable").value(true))
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.pages.length()").value(0));
    }

    @Test
    void 历史列表按版本倒序并标出哪一版是当前提交() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, png(80, 40), "作业.png");
        submit(versionId);

        String body = mvc.perform(get("/api/student/assignments/501/submission/history")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> data = dataOf(body);
        assertThat(data.get("currentVersionId")).isEqualTo((int) versionId);
        assertThat(data.get("canStartNewVersion")).isEqualTo(true);
        List<Map<String, Object>> versions = versionsOf(data);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("status")).isEqualTo("PROCESSING");
        assertThat(versions.get(0).get("pageCount")).isEqualTo(1);
        assertThat(versions.get(0).get("editable")).isEqualTo(false);
    }

    @Test
    void 作业结束后不能再提交() throws Exception {
        mvc.perform(post("/api/student/assignments/502/submission").header("Authorization", studentToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_CLOSED"));
    }

    @Test
    void 别的学生的版本返回404而不是403() throws Exception {
        long versionId = draftVersionId();

        // 版本 id 是连续数字，区分"不存在"与"不是你的"等于把别人的提交变成可枚举的信息。
        mvc.perform(get("/api/student/submissions/" + versionId).header("Authorization", otherStudentToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_VERSION_NOT_FOUND"));
    }

    // ---------- 页面整理 ----------

    @Test
    void 一次上传多份文件按上传顺序编号且每页都有缩略图() throws Exception {
        long versionId = draftVersionId();

        String body = upload(versionId, List.of(image("作业.png", png(80, 40)),
            pdfFile("附加页.pdf", pdf(3))));
        Map<String, Object> data = dataOf(body);
        List<Map<String, Object>> pages = pagesOf(data);

        assertThat(pages).hasSize(4);
        assertThat(data.get("status")).isEqualTo("UPLOADED");
        // 页码从 1 连续排到 4，PDF 自己那三页按 documentPageNo 落在同一份文档里。
        assertThat(pages.stream().map(page -> number(page, "pageNo"))).containsExactly(1, 2, 3, 4);
        assertThat(pages.stream().map(page -> number(page, "documentPageNo"))).containsExactly(1, 1, 2, 3);
        assertThat(pages.get(0).get("documentId")).isNotEqualTo(pages.get(1).get("documentId"));
        assertThat(number(pages.get(0), "width")).isEqualTo(80);
        assertThat(number(pages.get(0), "height")).isEqualTo(40);
        assertThat(pages).allSatisfy(page -> assertThat(number(page, "thumbnailFileId")).isPositive());
        // 图片的页面图就是学生交的那份字节，不另存一份；PDF 的页面图是渲染出来的新文件。
        assertThat(pages.get(0).get("pageFileId")).isEqualTo(pages.get(0).get("rotatedFileId"));
        // 文件名随页面一起返回：一份 PDF 拆出的三页都带同一个文件名，学生靠它在整理页上
        // 认出"这几页是我刚传的那份"。缺了它，页面就只剩缩略图和页码。
        assertThat(pages.stream().map(page -> page.get("fileName")))
            .containsExactly("作业.png", "附加页.pdf", "附加页.pdf", "附加页.pdf");
    }

    @Test
    void 顺序必须恰好包含全部页面每个一次() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> data = dataOf(upload(versionId,
            List.of(image("a.png", png(80, 40)), image("b.png", png(80, 40)))));
        List<Map<String, Object>> pages = pagesOf(data);
        long first = ((Number) pages.get(0).get("id")).longValue();
        long second = ((Number) pages.get(1).get("id")).longValue();

        // 缺一页：会让没提到的页面落在一个说不清的位置上。
        reorder(versionId, List.of(first))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_PAGE_ORDER_INVALID"));
        // 重复一页：另一页会凭空消失。
        reorder(versionId, List.of(first, first))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_PAGE_ORDER_INVALID"));
        // 别人的页面 id。
        reorder(versionId, List.of(first, 999999L))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_PAGE_ORDER_INVALID"));

        String body = reorder(versionId, List.of(second, first))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> reordered = pagesOf(dataOf(body));
        assertThat(((Number) reordered.get(0).get("id")).longValue()).isEqualTo(second);
        assertThat(number(reordered.get(0), "pageNo")).isEqualTo(1);
        assertThat(number(reordered.get(1), "pageNo")).isEqualTo(2);
    }

    @Test
    void 旋转生成新的派生图且不覆盖原图() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> data = dataOf(upload(versionId, png(80, 40), "a.png"));
        Map<String, Object> page = pagesOf(data).get(0);
        long pageId = ((Number) page.get("id")).longValue();
        long pageFileId = ((Number) page.get("pageFileId")).longValue();

        String rotatedBody = rotate(versionId, pageId, 90)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> rotated = pagesOf(dataOf(rotatedBody)).get(0);

        assertThat(number(rotated, "rotationDegrees")).isEqualTo(90);
        assertThat(number(rotated, "rotatedFileId")).isNotEqualTo(pageFileId);
        assertThat(rotated.get("pageFileId")).isEqualTo((int) pageFileId);
        // 文件名仍然是学生交的那张，不是旋转生成的 page-1-r90.png：
        // 转过一次就换成派生图的文件名，学生就认不出哪张是自己拍的了。
        assertThat(rotated.get("fileName")).isEqualTo("a.png");
        // 转过四分之一圈，宽高互换：后面按区域裁剪要用到这两个数。
        assertThat(number(rotated, "width")).isEqualTo(40);
        assertThat(number(rotated, "height")).isEqualTo(80);

        // 传目标角度而不是增量：再点一次 90 还是 90 度，不是 180 度。
        String againBody = rotate(versionId, pageId, 90)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> again = pagesOf(dataOf(againBody)).get(0);
        assertThat(number(again, "rotationDegrees")).isEqualTo(90);
        assertThat(number(again, "width")).isEqualTo(40);
        assertThat(number(again, "height")).isEqualTo(80);

        // 转回原样：指回原始页面图，不再存一份一模一样的副本。
        String resetBody = rotate(versionId, pageId, 0)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> reset = pagesOf(dataOf(resetBody)).get(0);
        assertThat(number(reset, "rotationDegrees")).isZero();
        assertThat(number(reset, "rotatedFileId")).isEqualTo(pageFileId);
        assertThat(number(reset, "width")).isEqualTo(80);
        assertThat(number(reset, "height")).isEqualTo(40);
    }

    @Test
    void 只接受四个直角() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> page = pagesOf(dataOf(upload(versionId, png(80, 40), "a.png"))).get(0);
        long pageId = ((Number) page.get("id")).longValue();

        rotate(versionId, pageId, 45)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_ROTATION_INVALID"));
    }

    @Test
    void 删除一页后页码保持连续() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> data = dataOf(upload(versionId, List.of(
            image("a.png", png(80, 40)), image("b.png", png(80, 40)), image("c.png", png(80, 40)))));
        List<Map<String, Object>> pages = pagesOf(data);
        long middle = ((Number) pages.get(1).get("id")).longValue();
        long last = ((Number) pages.get(2).get("id")).longValue();

        String body = mvc.perform(delete("/api/student/submissions/" + versionId + "/pages/" + middle)
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> remaining = pagesOf(dataOf(body));
        assertThat(remaining.stream().map(page -> number(page, "pageNo"))).containsExactly(1, 2);
        // 留下的是原来第 1、3 页，不是重新编号过的另一批行。
        assertThat(remaining.stream().map(page -> number(page, "id")))
            .containsExactly(number(pages.get(0), "id"), (int) last);
    }

    @Test
    void 删掉全部页面后退回草稿状态() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> page = pagesOf(dataOf(upload(versionId, png(80, 40), "a.png"))).get(0);
        long pageId = ((Number) page.get("id")).longValue();

        mvc.perform(delete("/api/student/submissions/" + versionId + "/pages/" + pageId)
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            // UPLOADED 留着会让"还没交过任何东西"和"交了一半"看起来一样。
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.pages.length()").value(0));
    }

    // ---------- 提交 ----------

    @Test
    void 提交后版本只读且重复提交按重试处理() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, png(80, 40), "a.png");

        submit(versionId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.current").value(true))
            .andExpect(jsonPath("$.data.editable").value(false))
            .andExpect(jsonPath("$.data.submittedAt").isNotEmpty());

        // 客户端没收到响应时会再发一次：学生刚点的那一下不该看起来像失败。
        submit(versionId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        // 提交之后不能改文件与页面。
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(file("files", "b.png", "image/png", png(80, 40)))
                .header("Authorization", studentToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_EDITABLE"));
        assertThat(countOf("select count(*) from submission_page where submission_version_id = " + versionId))
            .isEqualTo(1);
    }

    @Test
    void 没有页面的版本不能提交() throws Exception {
        long versionId = draftVersionId();

        submit(versionId)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NO_PAGE"));
    }

    @Test
    void 提交会为每份上传建一条识别任务且不会重复建() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, List.of(image("作业.png", png(80, 40)), pdfFile("附加页.pdf", pdf(2))));
        submit(versionId);

        // 两份上传（图片 + PDF）各一条任务，PDF 的两页不会变成两条。
        assertThat(countOf("select count(*) from ocr_task where status = 'PENDING'")).isEqualTo(2);
        assertThat(countOf("""
            select count(*) from ocr_task t
            join document_upload du on du.id = t.document_id
            where du.document_kind = 'STUDENT_SUBMISSION' and du.student_id = 1001
            """)).isEqualTo(2);
        assertThat(countOf("select count(*) from document_upload where submission_version_id = "
            + versionId)).isEqualTo(2);

        // 重试一次不会再造一份任务：同一份文档只该排一次队。
        submit(versionId).andExpect(status().isOk());
        assertThat(countOf("select count(*) from ocr_task")).isEqualTo(2);
    }

    @Test
    void 删掉的页面不会被送去识别() throws Exception {
        long versionId = draftVersionId();
        Map<String, Object> data = dataOf(upload(versionId,
            List.of(image("作业.png", png(80, 40)), pdfFile("附加页.pdf", pdf(2)))));
        List<Map<String, Object>> pages = pagesOf(data);
        // 删掉 PDF 那两页，只留图片页面。
        for (Map<String, Object> page : pages.subList(1, pages.size())) {
            mvc.perform(delete("/api/student/submissions/" + versionId + "/pages/" + page.get("id"))
                    .header("Authorization", studentToken()))
                .andExpect(status().isOk());
        }
        submit(versionId);

        assertThat(countOf("select count(*) from ocr_task")).isEqualTo(1);
        assertThat(countOf("""
            select count(*) from ocr_task t
            join document_upload du on du.id = t.document_id
            where du.original_file_id in (
                select sf.id from stored_file sf where sf.original_name = '附加页.pdf')
            """)).isZero();
    }

    @Test
    void 重交会取代上一版并保留旧版() throws Exception {
        long first = draftVersionId();
        upload(first, png(80, 40), "第一版.png");
        submit(first);

        long second = draftVersionId();
        assertThat(second).isNotEqualTo(first);
        upload(second, png(80, 40), "第二版.png");
        submit(second);

        assertThat(countOf("select count(*) from submission_version")).isEqualTo(2);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(statusOf(second)).isEqualTo("PROCESSING");
        assertThat(countOf("select count(*) from submission_version where is_current = true")).isEqualTo(1);
        // 旧版的页面还在：学生改了什么、老师当时批的是什么，都要能查回来。
        assertThat(countOf("select count(*) from submission_page where submission_version_id = " + first))
            .isEqualTo(1);
        assertThat(countOf("select count(*) from submission_audit where submission_version_id = " + first
            + " and action = 'SUPERSEDED'")).isEqualTo(1);
    }

    @Test
    void 批改开始后学生不能再改也不能再交() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, png(80, 40), "a.png");
        submit(versionId);
        // 教师确认答案并开始批改：作业进入 GRADING，版本被锁住，这是学生动不了的硬闸门。
        jdbc.update("update submission_version set status = 'CONFIRMED' where id = " + versionId);
        jdbc.update("update assignment set status = 'GRADING' where id = 501");
        mvc.perform(post("/api/teacher/submissions/" + versionId + "/lock")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("LOCKED"));

        mvc.perform(post("/api/student/assignments/501/submission").header("Authorization", studentToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_LOCKED"));
        submit(versionId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_EDITABLE"));
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(file("files", "b.png", "image/png", png(80, 40)))
                .header("Authorization", studentToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_NOT_EDITABLE"));
        assertThat(countOf("select count(*) from submission_version")).isEqualTo(1);
    }

    @Test
    void 教师令牌不能访问学生答卷接口() throws Exception {
        long versionId = draftVersionId();

        mvc.perform(get("/api/student/submissions/" + versionId).header("Authorization", teacherToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 非法文件在上传时就被拒绝() throws Exception {
        long versionId = draftVersionId();

        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(file("files", "作业.exe", "application/octet-stream", new byte[] {1, 2, 3}))
                .header("Authorization", studentToken()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("FILE_TYPE_NOT_ALLOWED"));

        // 扩展名对但内容不是 PNG：签名这一关挡住"把可执行文件改名成 .png"。
        mvc.perform(multipart("/api/student/submissions/" + versionId + "/pages")
                .file(file("files", "作业.png", "image/png", "不是图片".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", studentToken()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("FILE_CONTENT_MISMATCH"));

        assertThat(countOf("select count(*) from submission_page where submission_version_id = " + versionId))
            .isZero();
    }

    // ---------- 请求辅助 ----------

    private long draftVersionId() throws Exception {
        String body = mvc.perform(post("/api/student/assignments/501/submission")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) dataOf(body).get("id")).longValue();
    }

    private String upload(long versionId, byte[] content, String filename) throws Exception {
        return upload(versionId, List.of(image(filename, content)));
    }

    /** 一次请求带多份文件：真实场景里学生勾选多张照片就是一个请求。 */
    private String upload(long versionId, List<MockMultipartFile> files) throws Exception {
        var request = multipart("/api/student/submissions/" + versionId + "/pages");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        ResultActions actions = mvc.perform(request.header("Authorization", studentToken()));
        actions.andExpect(status().isOk());
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ResultActions reorder(long versionId, List<Long> pageIds) throws Exception {
        return mvc.perform(patch("/api/student/submissions/" + versionId + "/pages/order")
            .header("Authorization", studentToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pageIds\":[" + pageIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b)
                .orElse("") + "]}"));
    }

    private ResultActions rotate(long versionId, long pageId, int degrees) throws Exception {
        return mvc.perform(post("/api/student/submissions/" + versionId + "/pages/" + pageId + "/rotation")
            .header("Authorization", studentToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"degrees\":" + degrees + "}"));
    }

    private ResultActions submit(long versionId) throws Exception {
        return mvc.perform(post("/api/student/submissions/" + versionId + "/submit")
            .header("Authorization", studentToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    }

    private static MockMultipartFile image(String filename, byte[] content) {
        return file("files", filename, "image/png", content);
    }

    private static MockMultipartFile pdfFile(String filename, byte[] content) {
        return file("files", filename, "application/pdf", content);
    }

    private static MockMultipartFile file(String part, String filename, String contentType, byte[] content) {
        return new MockMultipartFile(part, filename, contentType, content);
    }

    // ---------- 断言辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(String body) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pagesOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("pages");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> versionsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("versions");
    }

    private static int number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private int countOf(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private String statusOf(long versionId) {
        return jdbc.queryForObject("select status from submission_version where id = ?", String.class, versionId);
    }

    private String studentToken() {
        return "Bearer " + jwtService.issueStudent(3, STUDENT_ID, false);
    }

    private String otherStudentToken() {
        return "Bearer " + jwtService.issueStudent(4, 1002, false);
    }

    private String teacherToken() {
        return "Bearer " + jwtService.issueTeacher(1, 11);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] pdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /** 存储根目录。真实写文件，所以不能用 application-test.yml 里那个占位值。 */
    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-submission-storage-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}

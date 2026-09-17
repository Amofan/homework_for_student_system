package com.homework.analysis.submission;

import com.homework.analysis.auth.JwtService;
import com.homework.analysis.testing.TestDatabaseCleaner;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本号、{@code is_current} 与"开始批改"这三件事在并发与状态推进下的表现。
 *
 * <p>这些用例并发跑真实请求，而不是直接调服务方法：序列化靠的是数据库上的
 * {@code select ... for update}，如果只在单线程里调服务，那把锁从来没有被真正争用过，
 * "并发建版只会建出一版"就成了一句没有验证过的断言。
 *
 * <p>与 {@link StudentSubmissionApiTest} 的分工：那边逐条验证接口行为，这边只钉住三件
 * 一旦出错就会让学生与教师看到不同事实的事 —— 版本号不重号、当前提交唯一、批改一旦开始
 * 学生这一侧就彻底安静。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionVersionConcurrencyTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    private static final long ASSIGNMENT_ID = 501L;

    /** 并发数。够挤在同一段临界区里，又不会把 H2 的行锁等超时。 */
    private static final int THREADS = 4;

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
              (501, 11, 101, '第一单元作业', 'PUBLISHED', current_timestamp(3), '2030-01-01 00:00:00', 1)
            """);
    }

    // ---------- 建版与重交 ----------

    @Test
    void 并发建版只会建出一版草稿() throws Exception {
        List<String> bodies = concurrently(THREADS, this::startDraftRequest);

        Set<Long> ids = new HashSet<>();
        for (String body : bodies) {
            ids.add(idOf(dataOf(body)));
        }

        // 四个请求两个结果都算错：建出四版空草稿，或者后到的请求因为撞唯一键报 500。
        assertThat(ids).hasSize(1);
        assertThat(countOf("select count(*) from submission_version")).isEqualTo(1);
        assertThat(countOf("select count(*) from submission_version where version_no = 1")).isEqualTo(1);
        assertThat(countOf("select count(*) from submission where assignment_id = 501 and student_id = 1001"))
            .isEqualTo(1);
    }

    @Test
    void 重交后版本号顺次递增且始终只有一版是当前提交() throws Exception {
        long first = draftVersionId();
        upload(first, "第一版.png");
        submitOk(first);
        assertThat(currentVersionIds()).containsExactly(first);

        long second = draftVersionId();
        assertThat(second).isNotEqualTo(first);
        assertThat(versionNoOf(second)).isEqualTo(2);
        // 草稿不占"当前提交"的位置：还没交上来的一版不是老师要批的那一份。
        assertThat(currentVersionIds()).containsExactly(first);

        upload(second, "第二版.png");
        submitOk(second);

        assertThat(currentVersionIds()).containsExactly(second);
        assertThat(statusOf(first)).isEqualTo("SUPERSEDED");
        assertThat(statusOf(second)).isEqualTo("PROCESSING");
        assertThat(countOf("select count(*) from submission_version")).isEqualTo(2);
    }

    // ---------- 并发提交与重试 ----------

    @Test
    void 并发提交同一版只会入队一次识别任务() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, List.of(image("a.png", png(80, 40)), image("b.png", png(80, 40))));

        List<String> bodies = concurrently(THREADS, () -> submitRequest(versionId));

        for (String body : bodies) {
            assertThat(dataOf(body).get("status")).isEqualTo("PROCESSING");
        }
        assertThat(statusOf(versionId)).isEqualTo("PROCESSING");
        assertThat(countOf("select count(*) from submission_version where is_current = true")).isEqualTo(1);
        // 两份上传 = 两个文档 = 两条识别任务。多一条意味着同一份答卷会被识别两遍，
        // 教师的校对列表里就会出现重复的候选。
        assertThat(countOf("select count(*) from ocr_task where processing_version = 1")).isEqualTo(2);
        assertThat(countOf("select count(*) from submission_audit where action = 'SUBMITTED'")).isEqualTo(1);
    }

    @Test
    void 重复提交按重试处理不会重复入队() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, "作业.png");
        submitOk(versionId);
        // 客户端没收到响应时会再发一次，学生连点两下也一样。
        submitOk(versionId);
        submitOk(versionId);

        assertThat(countOf("select count(*) from ocr_task")).isEqualTo(1);
        assertThat(countOf("select count(*) from submission_audit where action = 'SUBMITTED'")).isEqualTo(1);
        assertThat(statusOf(versionId)).isEqualTo("PROCESSING");
    }

    // ---------- 开始批改 ----------

    /**
     * 作业进入 {@code GRADING} 不等于锁住学生，教师点"开始批改"才是。
     *
     * <p>作业状态与提交版本状态是两件事：前者是教师推进的流程节点，后者是学生能不能动手。
     * 作业到了 {@code GRADING}、答案也确认了，学生改主意想重交仍然可以 —— 批改还没开始，
     * 老师手上还没有对应某一版作答的评分。锁定之后反过来：学生手上那版还没提交的草稿
     * 也不能再动，因为一交上来就会把老师正在批的那一份顶成 {@code SUPERSEDED}。
     */
    @Test
    void 作业进入批改阶段不等于锁住学生开始批改才锁() throws Exception {
        long versionId = draftVersionId();
        upload(versionId, "作业.png");
        submitOk(versionId);
        // 教师确认答案：作业进入 GRADING，但还没开始批改。
        jdbc.update("update submission_version set status = 'CONFIRMED' where id = " + versionId);
        jdbc.update("update assignment set status = 'GRADING' where id = " + ASSIGNMENT_ID);

        // 学生手上还开着的那一版照样能改能交，旧版在新版提交时被取代。
        long openDraft = draftVersionId();
        assertThat(openDraft).isNotEqualTo(versionId);
        upload(openDraft, "改好的.png");

        // 教师开始批改：锁的是当前提交（第一版），学生手上那版草稿还是未提交状态。
        lockAsTeacher(versionId);

        mvc.perform(get("/api/student/assignments/" + ASSIGNMENT_ID + "/submission/history")
                .header("Authorization", studentToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.canStartNewVersion").value(false));

        // 每一个写入口都要拦下，包括在没有锁定版本时就已经建好的那版草稿。
        mvc.perform(post("/api/student/assignments/" + ASSIGNMENT_ID + "/submission")
                .header("Authorization", studentToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_LOCKED"));
        uploadRequest(openDraft, List.of(image("再改一页.png", png(80, 40))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_LOCKED"));
        submitRequest(openDraft)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_LOCKED"));

        // 锁住的那一版还是当前提交，学生那版草稿原地不动，也没有多出版本。
        assertThat(currentVersionIds()).containsExactly(versionId);
        assertThat(statusOf(versionId)).isEqualTo("LOCKED");
        assertThat(statusOf(openDraft)).isEqualTo("UPLOADED");
        assertThat(countOf("select count(*) from submission_version")).isEqualTo(2);
        assertThat(countOf("select count(*) from submission_page where submission_version_id = " + openDraft))
            .isEqualTo(1);
    }

    // ---------- 请求辅助 ----------

    private ResultActions startDraftRequest() throws Exception {
        return mvc.perform(post("/api/student/assignments/" + ASSIGNMENT_ID + "/submission")
            .header("Authorization", studentToken()));
    }

    private long draftVersionId() throws Exception {
        String body = startDraftRequest().andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return idOf(dataOf(body));
    }

    private ResultActions uploadRequest(long versionId, List<MockMultipartFile> files) throws Exception {
        var request = multipart("/api/student/submissions/" + versionId + "/pages");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return mvc.perform(request.header("Authorization", studentToken()));
    }

    private ResultActions upload(long versionId, String filename) throws Exception {
        return upload(versionId, List.of(image(filename, png(80, 40)))).andExpect(status().isOk());
    }

    private ResultActions upload(long versionId, List<MockMultipartFile> files) throws Exception {
        return uploadRequest(versionId, files).andExpect(status().isOk());
    }

    private ResultActions submitRequest(long versionId) throws Exception {
        return mvc.perform(post("/api/student/submissions/" + versionId + "/submit")
            .header("Authorization", studentToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    }

    private void submitOk(long versionId) throws Exception {
        submitRequest(versionId).andExpect(status().isOk());
    }

    /** 教师开始批改当前提交。 */
    private void lockAsTeacher(long versionId) throws Exception {
        mvc.perform(post("/api/teacher/submissions/" + versionId + "/lock")
                .header("Authorization", teacherToken()))
            .andExpect(status().isOk());
    }

    /**
     * 同时发起 {@code count} 个请求，返回每个响应的响应体。
     *
     * <p>用一道闸门让线程尽量同时进到请求里：依次排队发的话，临界区从来没被争用过，
     * 用例也就测不出并发问题 —— 它会稳定通过，而且什么也没验证。断言写在请求线程内，
     * 任何一个请求不是 200 都会让测试失败。
     */
    private List<String> concurrently(int count, Callable<ResultActions> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.call().andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                }));
            }
            start.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> future : futures) {
                bodies.add(future.get(30, TimeUnit.SECONDS));
            }
            return bodies;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 断言辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(String body) throws Exception {
        return (Map<String, Object>) objectMapper.readValue(body, Map.class).get("data");
    }

    private static long idOf(Map<String, Object> data) {
        return ((Number) data.get("id")).longValue();
    }

    /** 当前提交是哪几版。多于一条就是错的，所以返回列表而不是单个 id。 */
    private List<Long> currentVersionIds() {
        return jdbc.queryForList("select id from submission_version where is_current = true order by id", Long.class);
    }

    private int versionNoOf(long versionId) {
        Integer versionNo = jdbc.queryForObject(
            "select version_no from submission_version where id = ?", Integer.class, versionId);
        return versionNo == null ? 0 : versionNo;
    }

    private String statusOf(long versionId) {
        return jdbc.queryForObject("select status from submission_version where id = ?", String.class, versionId);
    }

    private int countOf(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private String studentToken() {
        return "Bearer " + jwtService.issueStudent(3, 1001, false);
    }

    private String teacherToken() {
        return "Bearer " + jwtService.issueTeacher(1, 11);
    }

    private static MockMultipartFile image(String filename, byte[] content) {
        return new MockMultipartFile("files", filename, "image/png", content);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** 存储根目录。真实写文件，所以不能用 application-test.yml 里那个占位值。 */
    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("homework-submission-concurrency-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试用存储目录", exception);
        }
    }
}

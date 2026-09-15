package com.homework.analysis.shared.jdbc;

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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发创建时主键归属的回归护栏。
 *
 * <p>被保护的属性只有一条：一次创建请求拿到的编号，必须指向它自己刚插入的那一行。
 * 曾经的做法是插入之后回查编号（{@code max(id)} 或业务字段），回查得到的只是一个
 * "猜测"——同教师的第三个请求先提交时，回查会取到它的行，子表明细就挂到了别人的主表行上。
 * 由驱动直接回传本次插入生成的主键，任何交错下都取不到别人的行。
 *
 * <p>请注意本类的强度边界：编号回传改为驱动回传之后，这些用例在任何交错下都不该失败；
 * 但若有人把实现改回回查，它们只是**很可能**失败，取决于线程交错。
 * 因此"本类通过"证明的是这条路径没有退回回查写法，而不是"证明了不存在竞态"。
 * 要断言竞态不存在，靠的是 {@link GeneratedKeys} 的实现方式，不是这几个用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratedKeysConcurrencyTest {

    /** 并发线程数与每线程轮数：轮数越多交错机会越多，但也越慢，取一个够用的小值。 */
    private static final int THREADS = 6;
    private static final int ROUNDS = 3;

    private static final long CLASS_ID = 101;
    private static final long QUESTION_ID = 401;
    private static final long KNOWLEDGE_POINT_ID = 301;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedOneTeacherWithClassAndQuestion() {
        TestDatabaseCleaner.clean(jdbc);
        jdbc.update("insert into app_user(id, username, password_hash, role, enabled)"
            + " values (1, 'a', 'x', 'TEACHER', true)");
        jdbc.update("insert into teacher(id, user_id, display_name) values (11, 1, '教师甲')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name)"
            + " values (?, 11, '2026-7-1', '七年级一班')", CLASS_ID);
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active)"
            + " values (?, 11, 'ALG-EQ', '一元一次方程', 7, true)", KNOWLEDGE_POINT_ID);
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, standard_answer,"
            + " total_score, primary_knowledge_point_id, accepted_answers)"
            + " values (?, 11, 'Q-1', 'FILL_BLANK', '解方程', 'x=2', 5, ?, '[\"2\"]')",
            QUESTION_ID, KNOWLEDGE_POINT_ID);
    }

    @Test
    void 并发建作业时每个请求拿到的编号都属于自己() throws Exception {
        List<Created> created = concurrentCreates("/api/assignments",
            key -> "{\"classId\":" + CLASS_ID + ",\"title\":\"" + key
                + "\",\"questionIds\":[" + QUESTION_ID + "]}",
            "title");

        assertThat(created).hasSize(THREADS * ROUNDS);
        // 编号重复即意味着有一个请求拿到了别人刚提交的那一行
        assertThat(created).extracting(Created::id).doesNotHaveDuplicates();
        // 回传的标题就是自己提交的标题，也就是回传的编号确实指向自己那一行
        assertThat(created).allSatisfy(item ->
            assertThat(item.responseKey()).isEqualTo(item.requestKey()));
        // 子表跟着挂在自己那一行上：每条作业恰好一条题目明细，且就是请求里那道题
        assertThat(created).allSatisfy(item -> assertThat(jdbc.queryForList(
            "select question_id from assignment_question where assignment_id = " + item.id() + " order by question_order",
            Long.class)).containsExactly(QUESTION_ID));
        assertThat(jdbc.queryForObject("select count(*) from assignment", Integer.class))
            .isEqualTo(THREADS * ROUNDS);
    }

    @Test
    void 并发建题目时每个请求拿到的编号都属于自己() throws Exception {
        List<Created> created = concurrentCreates("/api/questions",
            key -> "{\"questionCode\":\"" + key + "\""
                + ",\"type\":\"FILL_BLANK\""
                + ",\"content\":\"解方程\""
                + ",\"standardAnswer\":\"x=2\""
                + ",\"totalScore\":5"
                + ",\"primaryKnowledgePointId\":" + KNOWLEDGE_POINT_ID
                + ",\"secondaryKnowledgePointIds\":[]"
                + ",\"acceptedAnswers\":[\"2\"]"
                + ",\"rubricItems\":[]}",
            "questionCode");

        assertThat(created).extracting(Created::id).doesNotHaveDuplicates();
        assertThat(created).allSatisfy(item ->
            assertThat(item.responseKey()).isEqualTo(item.requestKey()));
        // 主知识点关联同样要挂在自己那一行上
        assertThat(created).allSatisfy(item -> assertThat(jdbc.queryForList(
            "select knowledge_point_id from question_knowledge_point where question_id = " + item.id()
                + " and is_primary = true", Long.class)).containsExactly(KNOWLEDGE_POINT_ID));
    }

    @Test
    void 并发建班级时每个请求拿到的编号都属于自己() throws Exception {
        List<Created> created = concurrentCreates("/api/classes",
            key -> "{\"classCode\":\"" + key + "\",\"name\":\"班级" + key
                + "\",\"grade\":7,\"semester\":\"2026春\"}",
            "classCode");

        assertThat(created).extracting(Created::id).doesNotHaveDuplicates();
        assertThat(created).allSatisfy(item ->
            assertThat(item.responseKey()).isEqualTo(item.requestKey()));
    }

    /**
     * 让 {@link #THREADS} 个线程在每一轮同时发出请求，收集每个请求自报的业务键与回传的编号。
     *
     * <p>用栅栏把同一轮的请求挤到同一时刻，是为了让线程真正交错，而不是被线程池串行化。
     */
    private List<Created> concurrentCreates(String path, Function<String, String> bodyFor,
                                            String responseKeyField) throws Exception {
        CyclicBarrier roundStart = new CyclicBarrier(THREADS);
        List<Created> created = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int index = thread;
                futures.add(pool.submit(() -> {
                    for (int round = 0; round < ROUNDS; round++) {
                        String key = "T" + index + "R" + round;
                        roundStart.await(10, TimeUnit.SECONDS);
                        created.add(create(path, bodyFor.apply(key), key, responseKeyField));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return created;
    }

    private Created create(String path, String body, String requestKey, String responseKeyField)
        throws Exception {
        MvcResult result = mvc.perform(post(path)
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Created(requestKey, data.path("id").asLong(), data.path(responseKeyField).asText());
    }

    private String bearer() {
        return "Bearer " + jwtService.issue(11, 11, "TEACHER");
    }

    /** 一次创建请求：请求方自报的业务唯一键，以及服务端回传的主键与业务键。 */
    private record Created(String requestKey, long id, String responseKey) {
    }
}

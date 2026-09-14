package com.homework.analysis;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    DemoDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        if (count != null && count > 0) return;

        jdbc.update("insert into app_user(id, username, password_hash, role, enabled) values (1, ?, ?, 'TEACHER', true)",
            "demo", passwordEncoder.encode("MathDemo!2026"));
        jdbc.update("insert into teacher(id, user_id, display_name, school_name) values (11, 1, '林老师', '城南实验中学')");
        jdbc.update("insert into school_class(id, teacher_id, class_code, name, grade, semester) values (101,11,'2026-7-1','七年级一班',7,'2026-2027-1'),(102,11,'2026-7-2','七年级二班',7,'2026-2027-1')");
        jdbc.update("insert into student(id, class_id, student_no, name) values (1001,101,'070101','张晨'),(1002,101,'070102','李沐'),(1003,101,'070103','王宁'),(1004,102,'070201','周可'),(1005,102,'070202','陈思')");
        jdbc.update("insert into knowledge_point(id, teacher_id, code, name, grade, active) values (301,11,'ALG-EQ','一元一次方程',7,true),(302,11,'ALG-EXP','整式运算',7,true),(303,11,'GEO-LINE','相交线与平行线',7,true)");
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score, primary_knowledge_point_id, accepted_answers) values (401,11,'Q-ALG-001','FILL_BLANK','解方程 $2x+1=5$，则 $x=$____。','x=2',5,301,'[\"2\",\"x=2\"]'),(402,11,'Q-ALG-002','SINGLE_CHOICE','下列计算正确的是（ ）。','A',5,302,'[\"A\"]'),(403,11,'Q-ALG-003','SOLUTION','某数的 3 倍减 2 等于 16，求这个数。','设这个数为 x，3x-2=16，解得 x=6。',10,301,'[]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401,301,true),(402,302,true),(403,301,true)");
        jdbc.update("insert into rubric_item(id, question_id, order_no, title, criteria, max_score) values (801,403,1,'设未知数','正确设未知数并说明含义',2),(802,403,2,'列方程','依据题意列出 3x-2=16',4),(803,403,3,'求解与作答','解得 x=6 并完成作答',4)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501,11,101,'一元一次方程课堂巩固','IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501,401,1),(501,402,2),(501,403,3)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601,501,1001,'IMPORTED'),(602,501,1002,'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (611,601,401,'2'),(612,601,403,'设 x，3x-2=16，所以 x=6'),(613,602,401,'3')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, confirmed_score, error_type, teacher_explanation, student_feedback, score_details, status) values (701,611,'RULE',5,5,'CORRECT','答案与可接受答案一致','回答正确','[]','CONFIRMED'),(702,612,'AI',9,null,'INCOMPLETE','方程与结果正确，作答说明略简','补充完整的答句会更规范','[{\"rubricId\":801,\"score\":2,\"evidence\":\"已设未知数\"},{\"rubricId\":802,\"score\":4,\"evidence\":\"方程正确\"},{\"rubricId\":803,\"score\":3,\"evidence\":\"答案略简\"}]','PENDING_REVIEW'),(703,613,'RULE',0,0,'ANSWER_MISMATCH','答案与可接受答案不一致','请检查移项计算','[]','CONFIRMED')");
        jdbc.update("insert into teacher_review(id, result_id, teacher_id, decision, final_score, final_error_type, feedback, reason) values (901,701,11,'ACCEPT',5,'CORRECT','回答正确',null),(902,703,11,'ACCEPT',0,'ANSWER_MISMATCH','请检查移项计算',null)");
    }
}

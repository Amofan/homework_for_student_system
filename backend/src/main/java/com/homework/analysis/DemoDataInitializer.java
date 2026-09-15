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
        // 三个知识点各配 5 道题。分层练习每层取 5 题，题库刚好够，演示时三层都是满的。
        jdbc.update("insert into question(id, teacher_id, question_code, type, content, standard_answer, total_score, difficulty, primary_knowledge_point_id, accepted_answers) values (404,11,'Q-ALG-004','FILL_BLANK','解方程 $3(x-1)=9$，则 $x=$____。','x=4',5,'BASIC',301,'[\"4\",\"x=4\"]'),(405,11,'Q-ALG-005','SINGLE_CHOICE','方程 $2x+5=11$ 的解是（ ）。','C',5,'MEDIUM',301,'[\"C\"]'),(406,11,'Q-ALG-006','FILL_BLANK','若 $x=2$ 是方程 $ax-3=5$ 的解，则 $a=$____。','a=4',5,'ADVANCED',301,'[\"4\",\"a=4\"]'),(407,11,'Q-ALG-007','FILL_BLANK','计算 $(-2x)^2$ 的结果是____。','4x^2',5,'BASIC',302,'[\"4x^2\"]'),(408,11,'Q-ALG-008','SINGLE_CHOICE','下列各式中，与 $2x^2y$ 是同类项的是（ ）。','B',5,'MEDIUM',302,'[\"B\"]'),(409,11,'Q-ALG-009','FILL_BLANK','化简 $3a-2a+5a$ 的结果是____。','6a',5,'BASIC',302,'[\"6a\"]'),(410,11,'Q-ALG-010','SINGLE_CHOICE','计算 $(x+3)(x-3)$ 的结果是（ ）。','D',5,'ADVANCED',302,'[\"D\"]'),(411,11,'Q-GEO-001','FILL_BLANK','两直线平行，同位角____。','相等',5,'BASIC',303,'[\"相等\"]'),(412,11,'Q-GEO-002','SINGLE_CHOICE','下列说法正确的是（ ）。','A',5,'MEDIUM',303,'[\"A\"]'),(413,11,'Q-GEO-003','FILL_BLANK','两条直线相交所成的四个角中，对顶角____。','相等',5,'BASIC',303,'[\"相等\"]'),(414,11,'Q-GEO-004','SINGLE_CHOICE','同一平面内，过直线外一点画已知直线的垂线，可以画（ ）条。','B',5,'MEDIUM',303,'[\"B\"]'),(415,11,'Q-GEO-005','FILL_BLANK','如图，$AB \\parallel CD$，$\\angle 1=65°$，则 $\\angle 2=$____。','65°',5,'ADVANCED',303,'[\"65°\"]')");
        jdbc.update("insert into question_knowledge_point(question_id, knowledge_point_id, is_primary) values (401,301,true),(402,302,true),(403,301,true),(404,301,true),(405,301,true),(406,301,true),(407,302,true),(408,302,true),(409,302,true),(410,302,true),(411,303,true),(412,303,true),(413,303,true),(414,303,true),(415,303,true)");
        jdbc.update("insert into rubric_item(id, question_id, order_no, title, criteria, max_score) values (801,403,1,'设未知数','正确设未知数并说明含义',2),(802,403,2,'列方程','依据题意列出 3x-2=16',4),(803,403,3,'求解与作答','解得 x=6 并完成作答',4)");
        jdbc.update("insert into assignment(id, teacher_id, class_id, title, status) values (501,11,101,'一元一次方程课堂巩固','IMPORTED')");
        jdbc.update("insert into assignment_question(assignment_id, question_id, question_order) values (501,401,1),(501,402,2),(501,403,3),(501,404,4),(501,405,5),(501,406,6),(501,407,7),(501,408,8),(501,409,9),(501,410,10),(501,411,11),(501,412,12),(501,413,13),(501,414,14),(501,415,15)");
        jdbc.update("insert into submission(id, assignment_id, student_id, status) values (601,501,1001,'IMPORTED'),(602,501,1002,'IMPORTED')");
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (611,601,401,'2'),(612,601,403,'设 x，3x-2=16，所以 x=6'),(613,602,401,'3')");
        // 614-616 只为铺出三段掌握度：整式运算 4/5=80%（纠错层）、相交线 9/10=90%（提升层），
        // 一元一次方程维持 5/10=50%（巩固层）。三层都有题，演示与 E2E 才能走完三级题目。
        jdbc.update("insert into student_answer(id, submission_id, question_id, answer_content) values (614,601,402,'B'),(615,601,411,'相等'),(616,602,412,'B')");
        // ai_error_type 只有模型评分才有值（702 那行）；规则评分没有模型原判，留空。
        // 教师复核会覆盖 error_type，这一列留着模型当初的判断，供评测统计错因一致率。
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, confirmed_score, error_type, ai_error_type, teacher_explanation, student_feedback, score_details, status) values (701,611,'RULE',5,5,'CORRECT',null,'答案与可接受答案一致','回答正确','[]','CONFIRMED'),(702,612,'AI',9,null,'INCOMPLETE','INCOMPLETE','方程与结果正确，作答说明略简','补充完整的答句会更规范','[{\"rubricId\":801,\"score\":2,\"evidence\":\"已设未知数\"},{\"rubricId\":802,\"score\":4,\"evidence\":\"方程正确\"},{\"rubricId\":803,\"score\":3,\"evidence\":\"答案略简\"}]','PENDING_REVIEW'),(703,613,'RULE',0,0,'ANSWER_MISMATCH',null,'答案与可接受答案不一致','请检查移项计算','[]','CONFIRMED')");
        jdbc.update("insert into grading_result(id, answer_id, source, suggested_score, confirmed_score, error_type, teacher_explanation, student_feedback, score_details, status) values (704,614,'RULE',0,4,'ANSWER_MISMATCH','同类项判断正确，合并系数出错','再看一遍合并同类项的符号','[]','CONFIRMED'),(705,615,'RULE',5,5,'CORRECT','答案与可接受答案一致','回答正确','[]','CONFIRMED'),(706,616,'RULE',0,4,'ANSWER_MISMATCH','平行线性质判断正确，选项选择有误','对照平行线的三条性质再选一次','[]','CONFIRMED')");
        jdbc.update("insert into teacher_review(id, result_id, teacher_id, decision, final_score, final_error_type, feedback, reason) values (901,701,11,'ACCEPT',5,'CORRECT','回答正确',null),(902,703,11,'ACCEPT',0,'ANSWER_MISMATCH','请检查移项计算',null)");
        jdbc.update("insert into teacher_review(id, result_id, teacher_id, decision, final_score, final_error_type, feedback, reason) values (903,704,11,'MODIFY',4,'ANSWER_MISMATCH','再看一遍合并同类项的符号','部分给分'),(904,705,11,'ACCEPT',5,'CORRECT','回答正确',null),(905,706,11,'MODIFY',4,'ANSWER_MISMATCH','对照平行线的三条性质再选一次','部分给分')");
    }
}

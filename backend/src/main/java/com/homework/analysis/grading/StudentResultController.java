package com.homework.analysis.grading;

import com.homework.analysis.auth.CurrentStudent;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端成绩接口。
 *
 * <p>挂在 {@code /api/student/**} 下，安全配置里这条前缀只放行学生角色，
 * 所以"教师看不到学生的这个投影"由角色规则保证，而不是靠这里判断。
 * 路径按作业组织，与 {@code StudentAssignmentController} 同一个前缀：
 * 学生嘴里的"这次作业我考了多少"就是作业维度的事，他不需要知道版本 id。
 *
 * <p>返回的是 {@link StudentResultView} 而不是教师端的 {@code ReviewView}：
 * 两边的字段表不一样（学生看不到建议分、评分明细、标准答案），共用一条记录必然会
 * 在某个字段上做"教师传 true、学生传 false"的分支，而那种分支迟早会被漏掉一处。
 */
@RestController
@RequestMapping("/api/student/assignments")
final class StudentResultController {

    private final StudentResultService service;
    private final CurrentStudent currentStudent;

    StudentResultController(StudentResultService service, CurrentStudent currentStudent) {
        this.service = service;
        this.currentStudent = currentStudent;
    }

    /** 我在这次作业上的成绩：老师复核过的那些题，以及他对每道题写的反馈。 */
    @GetMapping("/{assignmentId}/result")
    ApiResponse<StudentResultView> result(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.forAssignment(currentStudent.id(authentication), assignmentId));
    }
}

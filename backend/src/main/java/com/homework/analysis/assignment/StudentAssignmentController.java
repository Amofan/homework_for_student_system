package com.homework.analysis.assignment;

import com.homework.analysis.auth.CurrentStudent;
import com.homework.analysis.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端作业接口。
 *
 * <p>两个接口都只返回 {@link StudentAssignmentView}：标准答案、评分项和跨学生统计都不在这里出现，
 * 因此不需要在每个字段上做“记得别带上答案”的人肉检查——不存在的字段没法泄露。
 */
@RestController
@RequestMapping("/api/student/assignments")
final class StudentAssignmentController {

    private final AssignmentService service;
    private final CurrentStudent currentStudent;

    StudentAssignmentController(AssignmentService service, CurrentStudent currentStudent) {
        this.service = service;
        this.currentStudent = currentStudent;
    }

    @GetMapping
    ApiResponse<List<StudentAssignmentView>> list(Authentication authentication) {
        return ApiResponse.ok(service.listForStudent(currentStudent.id(authentication)));
    }

    @GetMapping("/{assignmentId}")
    ApiResponse<StudentAssignmentView> get(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.findForStudent(currentStudent.id(authentication), assignmentId));
    }
}

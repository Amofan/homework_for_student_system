package com.homework.analysis.classroom.account;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.classroom.ClassroomService;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 教师端学生账号接口。
 *
 * <p>路径挂在 {@code /api/teacher/**} 下，由安全配置限定为教师角色；
 * 业务层再按 {@code teacher_id} 过滤一次，两道检查缺一不可。
 */
@RestController
@RequestMapping("/api/teacher")
final class StudentAccountController {

    private final StudentAccountService service;
    private final StudentCredentialWorkbook workbook;
    private final ClassroomService classrooms;
    private final CurrentTeacher currentTeacher;

    StudentAccountController(StudentAccountService service, StudentCredentialWorkbook workbook,
                             ClassroomService classrooms, CurrentTeacher currentTeacher) {
        this.service = service;
        this.workbook = workbook;
        this.classrooms = classrooms;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping("/classes/{classId}/student-accounts/provision")
    ApiResponse<List<ProvisionedStudentAccount>> provision(
        @PathVariable long classId,
        @Valid @RequestBody ProvisionRequest request,
        Authentication authentication) {
        return ApiResponse.ok(service.provision(currentTeacher.id(authentication), classId, request.studentIds()));
    }

    @PostMapping("/students/{studentId}/password/reset")
    ApiResponse<ProvisionedStudentAccount> resetPassword(@PathVariable long studentId,
                                                         Authentication authentication) {
        return ApiResponse.ok(service.resetPassword(currentTeacher.id(authentication), studentId));
    }

    /**
     * 把刚拿到的明文凭据渲染成 XLSX。
     *
     * <p>为什么不用一个 GET 下载链接：明文密码只在开通/重置的那一次响应里存在，
     * 之后数据库里只有哈希，服务端不可能再生成一份“同样的”文件。所以下载必须由
     * 持有明文的页面把数据回传，本接口只做渲染、不写库。
     */
    @PostMapping("/student-accounts/credentials")
    ResponseEntity<byte[]> credentials(@Valid @RequestBody CredentialWorkbookRequest request,
                                       Authentication authentication) throws IOException {
        long teacherId = currentTeacher.id(authentication);
        classrooms.get(teacherId, request.classId());
        byte[] body = workbook.write(request.students());
        return ResponseEntity.ok()
            // 响应体含明文密码：禁止任何中间层缓存，也不让浏览器把它留在磁盘缓存里。
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=student-credentials-" + request.classId() + ".xlsx")
            .contentType(MediaType.parseMediaType(StudentCredentialWorkbook.CONTENT_TYPE))
            .body(body);
    }

    record ProvisionRequest(
        @NotEmpty @Size(max = 2000) List<@NotNull Long> studentIds) {}

    record CredentialWorkbookRequest(
        @NotNull Long classId,
        @NotEmpty @Size(max = 2000) List<StudentCredentialWorkbook.CredentialRow> students) {}
}

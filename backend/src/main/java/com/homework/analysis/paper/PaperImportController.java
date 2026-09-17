package com.homework.analysis.paper;

import com.homework.analysis.assignment.AssignmentView;
import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 教师整卷导入接口。
 *
 * <p>路径里的 {@code {assignmentId}} 是作业 id——一次导入就是一份 {@code OCR_REVIEW} 状态的作业，
 * 它与它下面的试卷文档共同构成"这次导入"。这样学生提交、发布、评分这些后续环节不需要
 * 再引入第二套标识。
 *
 * <p>所有接口都在 {@code /api/teacher/**} 下，由安全配置按角色放行；学生令牌在这里会被直接拒绝，
 * 不进入业务代码。
 */
@RestController
@RequestMapping("/api/teacher/paper-imports")
final class PaperImportController {

    private final PaperImportService service;
    private final CurrentTeacher currentTeacher;

    PaperImportController(PaperImportService service, CurrentTeacher currentTeacher) {
        this.service = service;
        this.currentTeacher = currentTeacher;
    }

    @PostMapping
    ApiResponse<PaperImportView> create(@Valid @RequestBody PaperImportService.CreateCommand command,
                                        Authentication authentication) {
        return ApiResponse.ok(service.create(currentTeacher.id(authentication), command));
    }

    /**
     * 上传空白试卷或参考答案。
     *
     * <p>{@code kind} 只接受 {@code EXAM_PAPER} 与 {@code ANSWER_KEY}；具体的扩展名、签名、
     * 解码与大小校验全部由 {@code StoredFileService} 统一执行，这里不重复实现一套——
     * 两个地方各有一套规则时，攻击面等于两套规则的并集。
     */
    @PostMapping("/{assignmentId}/files")
    ApiResponse<PaperImportView> uploadFile(@PathVariable long assignmentId,
                                           @RequestParam("kind") String kind,
                                           @RequestParam("file") MultipartFile file,
                                           Authentication authentication) throws IOException {
        return ApiResponse.ok(service.attachFile(currentTeacher.id(authentication), assignmentId,
            kind, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @PostMapping("/{assignmentId}/process")
    ApiResponse<PaperImportView> process(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.process(currentTeacher.id(authentication), assignmentId));
    }

    @GetMapping("/{assignmentId}")
    ApiResponse<PaperImportView> get(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.get(currentTeacher.id(authentication), assignmentId));
    }

    @PatchMapping("/{assignmentId}/regions/{regionId}")
    ApiResponse<PaperImportView> patchRegion(@PathVariable long assignmentId, @PathVariable long regionId,
                                            @Valid @RequestBody PaperRegionPatchCommand command,
                                            Authentication authentication) {
        return ApiResponse.ok(service.patchRegion(currentTeacher.id(authentication), assignmentId,
            regionId, command));
    }

    /** 确认入库，返回创建好的作业。重复调用返回同一份作业，不会再插一遍题目。 */
    @PostMapping("/{assignmentId}/confirm")
    ApiResponse<AssignmentView> confirm(@PathVariable long assignmentId, Authentication authentication) {
        return ApiResponse.ok(service.confirm(currentTeacher.id(authentication), assignmentId));
    }
}

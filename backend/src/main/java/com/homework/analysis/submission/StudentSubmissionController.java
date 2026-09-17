package com.homework.analysis.submission;

import com.homework.analysis.auth.CurrentStudent;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 学生答卷接口。
 *
 * <p>路径分两段：{@code /assignments/{assignmentId}/submission} 是"从作业进入作答"，
 * {@code /submissions/{versionId}/...} 是"对某一版做点什么"。后者一律显式带版本 id，
 * 而不是让服务端去猜"当前那一版"：学生在两个页面上同时作答时，"当前"会给出两个答案，
 * 而其中一个是他没在看的那个。
 *
 * <p>客户端能表达的只有"我传了哪些文件""顺序是什么""转多少度"。版本号由服务端生成，
 * 状态只由动作推进 —— 没有任何接口接受一个状态值，因此不存在"客户端把版本设成已确认"这条路。
 */
@RestController
@RequestMapping("/api/student")
final class StudentSubmissionController {

    private final SubmissionVersionService service;
    private final CurrentStudent currentStudent;

    StudentSubmissionController(SubmissionVersionService service, CurrentStudent currentStudent) {
        this.service = service;
        this.currentStudent = currentStudent;
    }

    /**
     * 开始或继续作答。
     *
     * <p>POST 而不是 GET：它会建出版本。幂等，所以手机上返回上传页、刷新、连点两下
     * 都拿到同一版草稿，而不是攒出一堆空版本。
     */
    @PostMapping("/assignments/{assignmentId}/submission")
    ApiResponse<SubmissionVersionView> startDraft(@PathVariable long assignmentId,
                                                  Authentication authentication) {
        return ApiResponse.ok(service.createDraft(currentStudent.id(authentication), assignmentId));
    }

    /** 我在这份作业上交过哪几版。含草稿：学生要能看到"我正在改的那一版"也在列表里。 */
    @GetMapping("/assignments/{assignmentId}/submission/history")
    ApiResponse<SubmissionVersionView.History> history(@PathVariable long assignmentId,
                                                       Authentication authentication) {
        return ApiResponse.ok(service.history(currentStudent.id(authentication), assignmentId));
    }

    @GetMapping("/submissions/{versionId}")
    ApiResponse<SubmissionVersionView> get(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(service.detailForStudent(currentStudent.id(authentication), versionId));
    }

    /**
     * 追加页面。一次请求可以带多个文件，也可以是一个多页 PDF。
     *
     * <p>逐页返回，而不是"上传成功"：学生要立刻看到自己刚交的每一页长什么样，
     * 靠文件名列表他分不清第几页是第几页。校验（扩展名、签名、能否解码、大小与页数上限）
     * 全部在服务层与 {@code UploadFileInspector} 里，这里不重复实现一套。
     */
    @PostMapping("/submissions/{versionId}/pages")
    ApiResponse<SubmissionVersionView> addPages(@PathVariable long versionId,
                                                @RequestParam("files") List<MultipartFile> files,
                                                Authentication authentication) throws IOException {
        List<SubmissionVersionService.UploadFile> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            uploads.add(new SubmissionVersionService.UploadFile(
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        return ApiResponse.ok(service.addPages(currentStudent.id(authentication), versionId, uploads));
    }

    /** 重排页面。列表必须恰好是当前全部页面、每个一次，服务层会核对。 */
    @PatchMapping("/submissions/{versionId}/pages/order")
    ApiResponse<SubmissionVersionView> reorderPages(@PathVariable long versionId,
                                                    @Valid @RequestBody PageOrderCommand command,
                                                    Authentication authentication) {
        return ApiResponse.ok(
            service.reorderPages(currentStudent.id(authentication), versionId, command.pageIds()));
    }

    /**
     * 旋转一页。
     *
     * <p>传的是目标角度而不是增量：客户端不需要知道当前是多少度才能算下一次该传什么，
     * 也不会因为丢掉一次响应就把页面转成 180 度。
     */
    @PostMapping("/submissions/{versionId}/pages/{pageId}/rotation")
    ApiResponse<SubmissionVersionView> rotatePage(@PathVariable long versionId, @PathVariable long pageId,
                                                  @Valid @RequestBody RotationCommand command,
                                                  Authentication authentication) {
        return ApiResponse.ok(service.rotatePage(currentStudent.id(authentication), versionId,
            pageId, command.degrees()));
    }

    @DeleteMapping("/submissions/{versionId}/pages/{pageId}")
    ApiResponse<SubmissionVersionView> deletePage(@PathVariable long versionId, @PathVariable long pageId,
                                                  Authentication authentication) {
        return ApiResponse.ok(service.deletePage(currentStudent.id(authentication), versionId, pageId));
    }

    /**
     * 确认提交。
     *
     * <p>提交之后这一版只读。重复提交按重试处理：客户端没收到响应时会再发一次，
     * 学生刚点的那一下不该看起来像失败。
     *
     * <p>请求体可以为空：没有需要确认的页面时，"提交"就是一个不带参数的按钮。
     */
    @PostMapping("/submissions/{versionId}/submit")
    ApiResponse<SubmissionVersionView> submit(@PathVariable long versionId,
                                              @RequestBody(required = false) SubmitCommand command,
                                              Authentication authentication) {
        return ApiResponse.ok(service.submit(currentStudent.id(authentication), versionId,
            command == null ? List.of() : command.acknowledgedPageIds()));
    }

    /** 完整的新顺序：{@code pageIds} 按学生要的顺序排列，每页恰好出现一次。 */
    public record PageOrderCommand(@NotNull List<Long> pageIds) {
    }

    /** 目标角度，取值 0 / 90 / 180 / 270，与迁移里的检查约束一致。 */
    public record RotationCommand(@NotNull Integer degrees) {
    }

    /**
     * 提交时学生对"可能不清楚"的页面做的确认。
     *
     * <p>为空或省略表示什么都没确认：服务层只对质量检测给出警告的页面要求确认，
     * 没有警告时这个字段填不填都一样。
     */
    public record SubmitCommand(List<Long> acknowledgedPageIds) {
    }
}

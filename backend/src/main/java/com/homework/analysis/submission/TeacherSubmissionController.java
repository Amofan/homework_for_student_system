package com.homework.analysis.submission;

import com.homework.analysis.auth.CurrentTeacher;
import com.homework.analysis.question.QuestionView;
import com.homework.analysis.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师侧提交版本接口：查看、锁定、退回，以及答案校对（识别 → 逐条校对 → 确认入库）。
 *
 * <p>归属校验一律走"这份提交挂在不是我名下的作业上吗"，而不是只看版本 id ——
 * 版本 id 是连续数字，按 id 放行等于让任意教师翻遍全班的答卷。
 *
 * <p>这里没有"改学生答案"的接口。教师能改的是识别结果与题目映射（校对链路），
 * 学生自己写的内容只读：批改的对象是学生交的那一份。
 *
 * <p>校对链路宁可多几个接口也不合并成一个"保存"：识别是一次可能很慢的动作（会调外部的
 * OCR 服务），改一条候选是一次很小的写，确认入库是不可逆的分界线。三件事的失败代价与重试
 * 方式完全不同，合成一个接口就意味着"改一个字也要重跑一次识别"。
 */
@RestController
@RequestMapping("/api/teacher/submissions")
final class TeacherSubmissionController {

    private final SubmissionVersionService service;
    private final AnswerExtractionService answers;
    private final SubmissionConfirmationService confirmation;
    private final CurrentTeacher currentTeacher;

    TeacherSubmissionController(SubmissionVersionService service, AnswerExtractionService answers,
                                SubmissionConfirmationService confirmation, CurrentTeacher currentTeacher) {
        this.service = service;
        this.answers = answers;
        this.confirmation = confirmation;
        this.currentTeacher = currentTeacher;
    }

    /**
     * 一份作业下的待处理提交，按学号排。
     *
     * <p>从"某份作业"进入而不是从"某个学生"进入：教师手上的活是"把这份作业全班批完"，
     * 而学号顺序正是他对着名单核对时的顺序。
     */
    @GetMapping
    ApiResponse<SubmissionVersionView.TeacherQueue> queue(@RequestParam long assignmentId,
                                                          Authentication authentication) {
        return ApiResponse.ok(service.teacherQueue(currentTeacher.id(authentication), assignmentId));
    }

    @GetMapping("/{versionId}")
    ApiResponse<SubmissionVersionView> get(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(service.detailForTeacher(currentTeacher.id(authentication), versionId));
    }

    /**
     * 答案校对视图：学生交的每一页、识别出的每一块区域、每道题一条候选。
     *
     * <p>标准答案不在这里（见 {@link #referenceAnswers}）：对着标准答案看学生写的字会让人
     * "看出"那个答案，判分就不再独立。
     */
    @GetMapping("/{versionId}/answers")
    ApiResponse<AnswerReviewView> answers(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(answers.get(currentTeacher.id(authentication), versionId));
    }

    /**
     * 跑识别并物化答案候选。
     *
     * <p>POST 而不是 GET：它会让服务去调 OCR 引擎，是有副作用的动作。
     * 它是幂等的（重复调用不会重跑已完成的识别，也不会覆盖教师改过的候选），
     * 所以教师点两次不会出问题。
     */
    @PostMapping("/{versionId}/process")
    ApiResponse<AnswerReviewView> process(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(answers.process(currentTeacher.id(authentication), versionId));
    }

    /**
     * 改一条候选：改题目映射、改识别文字、标为空白、确认这条校对完成。
     *
     * <p>PATCH 而不是 PUT：请求里只要写要改的字段，没写的保持原样 —— 教师在同一屏上改文字、
     * 在另一屏上改映射，用 PUT 的话后一次请求会把前一次的结果覆盖回旧值。
     */
    @PatchMapping("/{versionId}/answers/{candidateId}")
    ApiResponse<AnswerReviewView> correct(@PathVariable long versionId, @PathVariable long candidateId,
                                          @Valid @RequestBody AnswerCorrectionCommand command,
                                          Authentication authentication) {
        return ApiResponse.ok(answers.correct(currentTeacher.id(authentication), versionId, candidateId, command));
    }

    /**
     * 确认整份答卷的作答入库。
     *
     * <p>这是分界线：从这里开始 {@code student_answer} 里就有这套答案了，成绩与学情统计
     * 都以它为输入。所以它整体成功或整体失败，不做部分入库。
     */
    @PostMapping("/{versionId}/confirm")
    ApiResponse<AnswerReviewView> confirm(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(confirmation.confirm(currentTeacher.id(authentication), versionId));
    }

    /**
     * 标准答案参考面板，单独一次请求。
     *
     * <p>刻意不并进校对视图：教师要看标准答案得自己点开，这是一个保留的摩擦。
     */
    @GetMapping("/{versionId}/reference-answers")
    ApiResponse<List<QuestionView>> referenceAnswers(@PathVariable long versionId,
                                                     Authentication authentication) {
        return ApiResponse.ok(answers.referenceAnswers(currentTeacher.id(authentication), versionId));
    }

    /** 开始批改：锁定当前提交，学生从此不能再改也不能新建版本。 */
    @PostMapping("/{versionId}/lock")
    ApiResponse<SubmissionVersionView> lock(@PathVariable long versionId, Authentication authentication) {
        return ApiResponse.ok(service.lockForTeacher(currentTeacher.id(authentication), versionId));
    }

    /**
     * 退回，让学生重新提交。
     *
     * <p>原因必填：学生会看到它。没有原因的退回等于把"重做"两个字丢给学生，
     * 他只能原样再交一次。
     */
    @PostMapping("/{versionId}/return")
    ApiResponse<SubmissionVersionView> returnToStudent(@PathVariable long versionId,
                                                       @Valid @RequestBody ReturnCommand command,
                                                       Authentication authentication) {
        return ApiResponse.ok(service.returnToStudent(
            currentTeacher.id(authentication), versionId, command.reason()));
    }

    /** 上限与 {@code submission_version.return_reason} 的列宽一致。 */
    public record ReturnCommand(@NotBlank @Size(max = 1000) String reason) {
    }
}

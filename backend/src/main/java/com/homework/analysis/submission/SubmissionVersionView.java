package com.homework.analysis.submission;

import java.time.Instant;
import java.util.List;

/**
 * 提交版本投影。
 *
 * <p>{@code current} 对应 {@code submission_version.is_current}：这一版是不是已经提交、并且被
 * 批改链路认领的那一版。学生手上的草稿是 {@code false} —— 草稿还没提交，不该占用"当前提交"
 * 这个位置，否则教师会在待批改列表里看到一份学生还在改的东西。
 *
 * <p>{@code editable} 由服务层按状态算好一起给出，而不是让前端各自实现一套"什么时候能改"：
 * 两处判断迟早会分叉，而分叉的表现是"界面上能点，点了报错"。
 */
public record SubmissionVersionView(
    long id,
    long assignmentId,
    long studentId,
    int versionNo,
    SubmissionVersionStatus status,
    boolean current,
    boolean editable,
    Instant submittedAt,
    Instant lockedAt,
    Instant returnedAt,
    String returnReason,
    Instant createdAt,
    List<PageView> pages) {

    /**
     * 一页学生答卷。
     *
     * <p>{@code pageFileId} 是页面原样，{@code rotatedFileId} 是学生旋转后的展示图。两个都给出去，
     * 前端才能在"按学生排的样子展示"之外，仍然能回到未经旋转的那一页。
     *
     * <p>{@code documentId} 与 {@code documentPageNo} 指向它在识别链路里的位置：答案区域就是从
     * 那一页上框出来的，教师复核时要靠它跳回原图。
     *
     * <p>{@code fileName} 是这一页来自哪张上传文件。学生整理页面时看的是缩略图与页码，
     * 但手机相册里同一批照片的文件名往往只差一两个字符，"这张是不是我漏传的那张"要靠它回答。
     * 一个 PDF 拆成多页时，每一页都带同一个文件名。
     */
    public record PageView(
        long id,
        int pageNo,
        long documentId,
        int documentPageNo,
        String fileName,
        long pageFileId,
        Long thumbnailFileId,
        long rotatedFileId,
        int rotationDegrees,
        String qualityStatus,
        Integer width,
        Integer height) {
    }

    /** 历史列表用：不带页面，学生交过几版就有几条。 */
    public record Summary(
        long id,
        int versionNo,
        SubmissionVersionStatus status,
        boolean current,
        boolean editable,
        Instant submittedAt,
        Instant returnedAt,
        String returnReason,
        int pageCount) {
    }

    /**
     * 某个作业下这个学生的全部版本。
     *
     * <p>{@code canStartNewVersion} 也由服务层给出：能不能新建版本要看当前版本是不是卡在
     * {@code LOCKED}，这个判断分散到前端就会变成"按钮亮着但提交被拒"。
     */
    public record History(
        long assignmentId,
        Long currentVersionId,
        boolean canStartNewVersion,
        List<Summary> versions) {
    }

    /**
     * 教师侧待办列表：某份作业下学生的提交。
     *
     * @param title 作业标题。列表页从作业 id 进来（教师可能在别处拿到这个 id），
     *              标题必须随列表一起给，否则页头上只能显示一串数字。
     */
    public record TeacherQueue(
        long assignmentId,
        String title,
        List<TeacherQueueItem> items) {
    }

    /**
     * 待办列表里的一行。
     *
     * <p>{@code pendingCount} / {@code candidateCount} 是教师决定"先看哪一份"的依据：
     * 一份 12 道题里还有 11 道没校对，与一份只剩 1 道没校对，工作量的差别不体现在状态上——
     * 两者的状态都是 {@code NEEDS_REVIEW}。
     *
     * <p>{@code returned} 的版本也留在列表里：学生会重交，教师得看得见自己退的是哪一版、
     * 退的时候说了什么。
     */
    public record TeacherQueueItem(
        long versionId,
        int versionNo,
        long studentId,
        String studentNo,
        String studentName,
        SubmissionVersionStatus status,
        boolean current,
        Instant submittedAt,
        Instant returnedAt,
        String returnReason,
        int pageCount,
        int candidateCount,
        int pendingCount) {
    }
}

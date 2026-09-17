package com.homework.analysis.submission;

import java.util.List;

/**
 * 教师校对一份学生答卷时看到的东西：学生交的页、识别出的区域、以及每道题的答案候选。
 *
 * <p>与 {@code PaperImportView} 的结构刻意保持一致（页 → 区域 → 候选），
 * 但有三处是答卷独有的，都不该被"统一"掉：
 *
 * <ol>
 *   <li><b>候选是按题目长出来的，不是识别出来的。</b>整卷导入的候选来自 OCR 分组，可能多也可能少；
 *       答卷的候选在物化时**为每道作业题各建一行**，包括学生没作答的那些。所以这里的候选列表
 *       长度恒等于作业题数，"少了一道题"这种事在结构上不可能发生。</li>
 *   <li><b>警告带说明文字。</b>整卷导入的警告都是"这条候选不牢靠"，一个码就够了；
 *       而这里的警告有一半是关于**整份答卷**的（第 3 页没交、第 2 页和第 5 页都对上了模板第 2 页），
 *       只给一个码等于让教师自己去猜是哪一页。</li>
 *   <li><b>标准答案不在这里。</b>它由单独的一个接口按需返回。教师对着标准答案看学生写的字，
 *       会不自觉地"看出"那个答案，判分就不再独立——所以它不能在同一个响应里顺带送出去。</li>
 * </ol>
 *
 * @param confirmed 答案是否已经确认入库。确认之后不允许再改候选，只能退回重交。
 * @param warnings  需要教师处理的整版问题，去重后按发现顺序排列。
 */
public record AnswerReviewView(
    long versionId,
    long assignmentId,
    Long studentId,
    String studentName,
    int versionNo,
    SubmissionVersionStatus status,
    boolean confirmed,
    List<WarningView> warnings,
    List<AnswerPageView> pages,
    List<AnswerCandidateView> candidates) {

    /**
     * 一条需要教师处理的问题。
     *
     * @param code   稳定的警告码，前端按它决定样式与图标。
     * @param detail 人可读的说明，必须说清"是哪一页/哪道题"。界面上直接显示这一句。
     */
    public record WarningView(String code, String detail) {
    }

    /**
     * 学生答卷上的一页。
     *
     * <p>{@code pageNo} 是学生排定的顺序（{@code submission_page.page_no}），也是教师嘴里说的"第几页"；
     * {@code documentPageNo} 是这一页在它所属上传文件里的位置。图片上传恒为 1，
     * 一个 PDF 拆成多页时才会有别的值。两者不同时才需要前端分别显示。
     *
     * <p>{@code templatePageNo} 与 {@code alignmentConfidence} 是配准结果：
     * 这一页对上了模板的第几页、对得有多像。为空表示没对上（这会带
     * {@code TEMPLATE_MISMATCH} 警告，那一页的区域留作未归属，由教师手工归类）。
     *
     * <p>这一页自己的问题不在这里重复一份，而是统一放在最外层的 {@code warnings} 里：
     * 那些警告的说明文字里已经写明了是哪一页，同一个问题在两处出现只会让教师怀疑
     * "是不是有两处问题"。
     *
     * <p>{@code regions} 是**这一页上识别出的全部区域**，不是"这道题的区域"：
     * 归属关系在每一块区域自己的 {@code candidateId} 上，为空的就是还没归到题的。
     * 未归属的区域只有在这里才看得见（候选列表按定义装不下它们），
     * 而"识别出来了却没算进任何一道题"正是教师最需要看到的那一类。
     */
    public record AnswerPageView(
        long submissionPageId,
        int pageNo,
        long documentId,
        int documentPageNo,
        String fileName,
        long pageFileId,
        long rotatedFileId,
        Integer width,
        Integer height,
        Integer templatePageNo,
        Double alignmentConfidence,
        List<AnswerRegionView> regions) {
    }

    /**
     * 一道题的答案候选；物化时按作业题逐题建立，所以每道题恒有且只有一条。
     *
     * @param questionCode    题库里的题号，教师用它把界面上的这道题与试卷对上。
     * @param questionOrder   这道题在作业里的顺序，界面的排序依据。
     * @param blank           学生没有可识别作答。**这不等同于"答案文本为空"**：
     *                        文本为空也可能是识别失败，而 {@code blank} 是物化阶段算出来的结论。
     * @param reviewStatus    {@code PENDING} / {@code CONFIRMED}。没确认的候选进不了正式答案。
     * @param version         乐观锁版本号，教师提交修改时必须带上。
     * @param regions         组成这道作答的区域；空白作答为空列表。区域带裁剪图 id，
     *                        教师的复核对象是那张图，不是识别出来的文字。
     */
    public record AnswerCandidateView(
        long candidateId,
        long questionId,
        String questionCode,
        int questionOrder,
        Integer orderNo,
        String answerText,
        String answerLatex,
        boolean blank,
        Double confidence,
        String reviewStatus,
        int version,
        List<AnswerRegionView> regions,
        List<String> warnings) {
    }

    /**
     * 一个识别区域，以及它裁出来的答案图。
     *
     * <p>{@code pageNo} 已换算成学生排定的页码：库里存的是 {@code document_page.page_no}
     * （它在原上传文件里的位置），直接给出去会让"第 3 页"在界面上指错一页。
     *
     * <p>{@code cropFileId} 为空表示这一块还没裁出答案图。裁图会在物化时自动做，
     * 失败的（区域太窄）会带 {@code ANSWER_CROP_FAILED} 警告留在候选上——区域本身不删，
     * 教师至少还能读到它的识别文字并手工修框。
     *
     * @param candidateId 认领了这一块的题；为空表示它不属于任何题（落在所有题框之外，
     *                    或那一页没对上模板）。**这一类区域必须出现在界面上**：
     *                    识别出来的东西没有"悄悄不算"这个结局，要么挂在某道题下，
     *                    要么留在这里等教师归类。
     */
    public record AnswerRegionView(
        long regionId,
        long pageNo,
        String regionType,
        double x,
        double y,
        double width,
        double height,
        String ocrText,
        String ocrLatex,
        Double confidence,
        Long cropFileId,
        Long candidateId) {
    }
}

package com.homework.analysis.paper;

import com.homework.analysis.assignment.AssignmentStatus;

import java.util.List;

/**
 * 整卷导入的读取视图。
 *
 * <p>一次导入 = 一份 {@code assignment}（状态 {@code OCR_REVIEW}）+ 它的文档、页面、区域与候选题。
 * 整卷导入之所以复用作业主表而不是新建一张"导入"表：作业本来就是它的生命周期宿主
 * （导入完成 → 教师发布 → 学生提交），另起一张表只会让状态在多处漂移。
 * 因此接口里的 {@code {id}} 就是作业 id。
 *
 * @param confirmed 是否已经确认入库。已确认的导入不允许再重跑识别或改候选。
 * @param warnings  未解决问题的汇总（去重后的警告码 + 文档级失败码），供界面在顶部提示。
 */
public record PaperImportView(
    long assignmentId,
    long classId,
    String title,
    AssignmentStatus status,
    boolean confirmed,
    List<DocumentView> documents,
    List<PaperQuestionCandidate> candidates,
    List<String> warnings) {

    public record DocumentView(
        long documentId,
        String documentKind,
        String status,
        String failureCode,
        /**
         * 最新一条 OCR 任务的状态。
         *
         * <p>必须单独暴露，不能只看 {@code status}：可重试失败（识别服务暂时不可用）时
         * 文档状态会停在 {@code PENDING}，与"还没开始识别"完全一样。界面要区分
         * "可以重试"与"等待开始"，否则教师会以为系统卡住了而重新上传一份文件。
         *
         * <p>注意重试**不是自动的**：`OcrTaskWorker` 只由 `process` 触发，
         * 项目里没有排空 OCR 任务的调度器（AI 评分任务同样如此）。
         * 因此界面上的文案必须是"可重试"，不能写成"稍后会自动重试"。
         */
        String ocrStatus,
        String ocrFailureCode,
        int pageCount,
        List<PageView> pages) {
    }

    /**
     * 页面视图。
     *
     * <p>{@code width}/{@code height} 是识别坐标所依据的那一份像素尺寸：区域坐标是归一化值，
     * 前端要把它换算成像素位置就必须知道这两个值，缺了会让框整体偏移。
     */
    public record PageView(
        long pageId,
        int pageNo,
        long pageFileId,
        Long thumbnailFileId,
        Integer width,
        Integer height,
        List<RegionView> regions) {
    }

    /**
     * 区域视图。
     *
     * @param candidateId 反向查出的归属候选，供界面把区域与题号连起来；为空表示尚未归属任何候选。
     */
    public record RegionView(
        long regionId,
        String regionType,
        double x,
        double y,
        double width,
        double height,
        String ocrText,
        String ocrLatex,
        Double confidence,
        Long cropFileId,
        String reviewStatus,
        Long candidateId) {
    }
}

package com.homework.analysis.grading.review;

import java.util.List;

public record ReviewQueueItem(
    long resultId,
    long answerId,
    String studentNo,
    String studentName,
    String questionCode,
    String questionContent,
    String answerContent,
    String source,
    int suggestedScore,
    int totalScore,
    String errorType,
    String teacherExplanation,
    String studentFeedback,
    String scoreDetails,
    List<AnswerAssetView> answerAssets) {

    /**
     * 一条正式答案名下的一张答案图。
     *
     * <p>教师复核界面上的主角是学生写的那几个字，不是识别出来的文本：文本只是识别的结论，
     * 而分数要落在人真正写了什么上。所以每条待复核的答案都要带上它的答案图文件 id，
     * 由前端取一次私有 Blob 渲染出来。
     *
     * <p>{@code pageNo} 与归一化坐标是**这句话从哪来**：学生分两处写、跨页续写时，
     * 教师需要知道这几张图分别是哪一页的哪一块，才能判断有没有漏看。它们可空 ——
     * 区域会随模板重新配准被替换掉，那时 {@code student_answer_asset.document_region_id}
     * 被置空，但答案图本身是批改依据，仍然在。
     */
    public record AnswerAssetView(
        long fileId,
        String role,
        Integer pageNo,
        Double x,
        Double y,
        Double width,
        Double height,
        int sortOrder) {
    }
}

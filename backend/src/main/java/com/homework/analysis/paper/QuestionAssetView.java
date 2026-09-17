package com.homework.analysis.paper;

/**
 * 题目配图的读取视图。
 *
 * <p>只暴露 {@code fileId} 而不给 URL：这些文件只能通过带鉴权的
 * {@code /api/teacher/files/{id}} 读取，返回一个可以直接放进 {@code <img src>} 的地址，
 * 等于把"图片可被无凭据访问"这件事写进了接口契约。
 */
public record QuestionAssetView(long id, long fileId, QuestionAssetRole role, int sortOrder) {
}

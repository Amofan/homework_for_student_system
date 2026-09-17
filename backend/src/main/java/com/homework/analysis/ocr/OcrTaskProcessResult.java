package com.homework.analysis.ocr;

/**
 * 单次 OCR 任务处理的结果。
 *
 * <p>与 {@code AiTaskProcessResult} 保持同一形状：两条异步链路一个记法，
 * 前端与日志都不必为它们各写一套解析。
 */
public record OcrTaskProcessResult(boolean processed, Long taskId, String status, String message) {
}

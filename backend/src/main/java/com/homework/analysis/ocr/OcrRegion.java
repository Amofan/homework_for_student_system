package com.homework.analysis.ocr;

/**
 * OCR 识别出的一个区域。
 *
 * <p>坐标是归一化到 {@code 0..1} 的相对值，与页面像素尺寸解耦：同一份版面在 200 DPI 与
 * 300 DPI 下识别出的像素坐标完全不同，归一化之后才能和页面记录一起稳定存储与比较。
 *
 * <p>{@code confidence} 同样归一化到 {@code 0..1}。低于阈值只影响"是否提示教师复核"，
 * 不能绕过教师确认——OCR 结果在任何情况下都只是候选数据。
 *
 * <p>{@code questionCode} 是这一块区域归属的题号，来自答卷与模板的配准结果。可空：
 * 没带模板、模板里认不出这一页、或这块区域落在所有题目框之外时都是空。
 * 空不是"丢掉这一块"的意思——归属不明的区域照样落库，由教师在校对界面里手工归类。
 */
public record OcrRegion(
    String externalId,
    String type,
    double x,
    double y,
    double width,
    double height,
    String text,
    String latex,
    double confidence,
    String questionCode) {

    /** 没有配准结果时用这个：试卷类识别（整卷导入）不跑模板配准，区域自然没有题号。 */
    public OcrRegion(String externalId, String type, double x, double y, double width, double height,
                     String text, String latex, double confidence) {
        this(externalId, type, x, y, width, height, text, latex, confidence, null);
    }
}

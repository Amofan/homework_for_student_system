package com.homework.analysis.paper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 校对工作台的单次编辑。
 *
 * <p>一个请求同时承载"区域自身的修正"和"所属候选题的字段修正"，因为教师的一次操作往往两者都要动
 * （把框拖到正确位置之后，题干文本也一起改）。拆成两个请求会让乐观锁形同虚设：两次请求之间
 * 别人插进来一次编辑，第二次请求就会把第一次的结果覆盖掉，而两边都认为自己成功了。
 *
 * <p>{@code version} 是**候选题**的版本号，不是区域的。区域几何用 {@code revision} 独立计数，
 * 它是渲染用的（用来判断缓存要不要失效），不承担并发控制——并发控制只保留一个真相来源。
 */
public record PaperRegionPatchCommand(
    /** 该区域归属的候选题；为空表示把区域从原候选上摘下来。 */
    Long candidateId,

    /**
     * 候选题当前版本。
     *
     * <p>当 {@code candidateId} 非空时必填，由服务层显式检查；把"缺版本号"当成版本 0
     * 会让一次漏传字段的请求在刚识别完的候选上静默通过，而那正是最需要拦住的情况。
     */
    @Min(0) Integer version,

    @Size(max = 32) String regionType,
    @DecimalMin("0.0") @DecimalMax("1.0") Double x,
    @DecimalMin("0.0") @DecimalMax("1.0") Double y,
    @DecimalMin("0.0") @DecimalMax("1.0") Double width,
    @DecimalMin("0.0") @DecimalMax("1.0") Double height,
    @Size(max = 20000) String ocrText,
    @Size(max = 32) String reviewStatus,

    /** 为真时按当前几何裁剪出题图并写回区域的 {@code crop_file_id}。 */
    Boolean createCrop,

    /**
     * 为真时把该区域从原候选题里拆出来，单独组成一道新候选。
     *
     * <p>{@code candidateId} 与 {@code version} 这时指的是**原候选题**：拆分确实改动了它
     * （少了一块区域、版本加一），所以沿用同一套乐观锁。
     *
     * <p>OCR 把两道题并成一组是常见情况（后一题的题号没被识别出来），
     * 只允许"把区域移出成未归属"是不够的——教师还需要一道能填内容的新题。
     */
    Boolean split,

    @Valid PaperQuestionCommand candidate) {
}

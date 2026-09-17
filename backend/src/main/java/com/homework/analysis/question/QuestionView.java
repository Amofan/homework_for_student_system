package com.homework.analysis.question;

import com.homework.analysis.paper.QuestionAssetView;

import java.util.List;

/**
 * 题目视图。
 *
 * <p>新增的 {@code assets} 是题目配图。文本字段（{@code content}、{@code standardAnswer}、
 * {@code acceptedAnswers}）的语义与格式一律不变：题干里的公式仍然以原样文本承载，
 * 由前端的排版组件负责渲染，服务端不参与也不改写。
 *
 * <p>配图类型定义在 {@code paper} 包（整卷导入是它们唯一的写入方）。这让两个包互相引用：
 * {@code paper} 依赖 {@code question} 的题型与评分项，这里又反过来引用它的读模型。
 * 取舍是刻意的——把一个只有四个字段的读模型复制一份，代价是两边的字段迟早会漂移，
 * 而漂移的表现是"题图在导入界面能看见、在题库页面看不见"，很难定位。
 */
public record QuestionView(
    long id,
    String questionCode,
    QuestionType type,
    String content,
    String standardAnswer,
    int totalScore,
    QuestionDifficulty difficulty,
    long primaryKnowledgePointId,
    List<Long> secondaryKnowledgePointIds,
    List<String> acceptedAnswers,
    List<RubricView> rubricItems,
    List<QuestionAssetView> assets) {
}

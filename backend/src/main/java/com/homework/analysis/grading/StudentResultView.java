package com.homework.analysis.grading;

import java.time.Instant;
import java.util.List;

/**
 * 学生看到的最终成绩。
 *
 * <p>这是**安全投影**：字段表本身就是白名单。AI 建议分、模型原始错因、评分明细、
 * 标准答案与评分项、教师改写原因都不在这里，因此不需要在每个使用点做"记得别带上"的人肉检查
 * ——不存在的字段没法泄露。与 {@code StudentAssignmentView} 是同一个做法。
 *
 * <p>两个取舍记在这里，因为它们都不是显然的：
 *
 * <p><b>不返回最终错因。</b>教师"采纳"时最终错因与模型建议一模一样，
 * 返回它等于在最常见的那条路径上把模型判断原样发给学生；而错因是七个固定编码之一，
 * 教师写给学生的那句反馈才是能读懂的部分。
 *
 * <p><b>分数是"已经批完的那部分"。</b>教师逐题确认，所以 {@code gradedScore} 是分母，
 * 而不是整份作业的满分。把还没批的题按 0 分算进去，学生会以为自己考砸了——
 * 这个数字要能直接显示成"8 / 10（已批 1/3 题）"。
 */
public record StudentResultView(
    long assignmentId,
    State state,
    /** 这些成绩属于第几版；整卷导入那条旧链路没有版本，为空。 */
    Integer versionNo,
    /** 已批完题目的得分合计；一道都没批完时为空。 */
    Integer confirmedScore,
    /** 已批完题目的满分合计，与 {@code confirmedScore} 配成一组。 */
    Integer gradedScore,
    int gradedQuestionCount,
    int questionCount,
    /** 老师批完最后一道题的时间；没批完时为空。 */
    Instant completedAt,
    List<QuestionResult> items) {

    public enum State {
        /** 还没交过，或者交了但库里还没有这道题的作答：总之没有可批的东西。 */
        NOT_SUBMITTED,
        /** 交了，但老师一道题都还没批完。 */
        PENDING,
        /** 批完了一部分，先看到这些。 */
        PARTIAL,
        /** 这次交上去的作答全批完了。 */
        GRADED
    }

    /**
     * 一道题的成绩。
     *
     * <p>带题干不带标准答案：题干学生本来就有（作业详情接口已经给过，属于他自己那份卷子），
     * 标准答案是批改依据，不能出现在学生端。
     */
    public record QuestionResult(
        String questionCode,
        String questionContent,
        int confirmedScore,
        int totalScore,
        /** 老师写给这道题的反馈；没写时为空。 */
        String feedback,
        Instant confirmedAt) {
    }
}

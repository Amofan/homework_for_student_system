package com.homework.analysis.paper;

import com.homework.analysis.question.QuestionDifficulty;
import com.homework.analysis.question.QuestionType;
import com.homework.analysis.question.RubricCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 教师在校对界面上编辑的一道题。
 *
 * <p>所有字段都可为空，这里**刻意不做**完整性校验：校对是一个渐进过程，教师会先改题号再改分值。
 * 真正的语义校验（评分项之和等于总分、客观题必须给可接受答案等）在确认时由
 * {@code QuestionService} 统一执行——放在这里会让"改一半就存不下去"，
 * 而放在确认时能保证入库的数据一定合法。
 */
public record PaperQuestionCommand(
    @Size(max = 64) String questionCode,
    QuestionType questionType,
    @Size(max = 4000) String content,
    @Size(max = 4000) String standardAnswer,
    List<@Size(max = 256) String> acceptedAnswers,
    @Min(1) Integer totalScore,
    QuestionDifficulty difficulty,
    Long primaryKnowledgePointId,
    List<@Valid RubricCommand> rubricItems,
    List<Long> assetRegionIds,
    /** 移到这个位置（从 1 开始）；为空表示不改顺序。 */
    @Min(1) Integer orderNo) {
}

package com.homework.analysis.submission;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 教师对一条答案候选的一次修改。
 *
 * <p>所有字段可空，为空表示"这一项不动"——这是刻意的：教师改一处文字不必把整条候选重发一遍，
 * 重发就会把没显示的字段一起写回去，而没显示的字段恰恰是客户端不知道的那些。
 *
 * <p>{@code version} 必填且不允许缺省成 0：把"漏传版本号"当成"当前版本是 0"，
 * 会让请求在刚物化完（版本正好是 0）的候选上静默通过，而那正是最需要拦住的时候。
 * 缺版本号一律 400（{@code ANSWER_CANDIDATE_VERSION_REQUIRED}），
 * 版本对不上才是 409。
 *
 * @param questionId   改派到另一道题：识别把作答分错了题（学生写错框、题框偏了）时用。
 *                     每道题恒有且只有一条候选，所以这是**互换**——那道题原来的候选换到本题来。
 *                     对面已经校对确认过会返回 409，要换得先把它改回待校对。
 * @param answerText   学生作答的文本。教师改的是**识别结果**，不是学生的笔迹——
 *                     笔迹永远以答案图为准，改文本只是让机器读到的更接近图上写的。
 * @param answerLatex  公式的 LaTeX。与 {@code answerText} 并存：一道题可能既有文字又有公式。
 * @param blank        标记为"学生没作答"。这是教师的判断，不是系统的推断
 *                     （识别不出文字既可能是空白，也可能是拍糊了）。
 * @param reviewStatus 置为 {@code CONFIRMED} 才算教师认过这条。只有认过的候选能进正式答案。
 * @param crop         为真时按当前几何重新裁剪这道题的答案图。区域太窄导致自动裁剪失败时用。
 */
public record AnswerCorrectionCommand(
    @NotNull(message = "必须带上当前版本号") @Min(0) Integer version,
    Long questionId,
    @Size(max = 8000) String answerText,
    @Size(max = 2000) String answerLatex,
    Boolean blank,
    @Size(max = 32) String reviewStatus,
    Boolean crop) {
}

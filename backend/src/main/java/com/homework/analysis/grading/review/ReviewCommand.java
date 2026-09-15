package com.homework.analysis.grading.review;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param teacherSeconds 前端计时的教师复核耗时（秒）。可选：无法计时的客户端（脚本、curl）留空即记缺失
 */
public record ReviewCommand(
    @NotNull ReviewDecision decision,
    Integer finalScore,
    @Size(max = 64) String errorType,
    @Size(max = 4000) String feedback,
    @Size(max = 1000) String reason,
    Double teacherSeconds) {
}

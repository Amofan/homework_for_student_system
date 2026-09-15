package com.homework.analysis.exercise;

import jakarta.validation.constraints.Size;

/** {@code title} 允许为空，为空时由班级与来源作业拼出默认标题。 */
public record ExerciseGenerationCommand(
    long classId,
    long sourceAssignmentId,
    @Size(max = 128) String title) {
}

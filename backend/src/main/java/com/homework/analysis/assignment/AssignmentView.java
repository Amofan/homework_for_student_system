package com.homework.analysis.assignment;

import java.util.List;

public record AssignmentView(long id, long classId, String title, String status, List<Long> questionIds) {
}

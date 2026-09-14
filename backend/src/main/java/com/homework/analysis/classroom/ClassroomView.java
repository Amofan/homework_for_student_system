package com.homework.analysis.classroom;

public record ClassroomView(long id, String classCode, String name, Integer grade, String semester,
                            long studentCount) {
}

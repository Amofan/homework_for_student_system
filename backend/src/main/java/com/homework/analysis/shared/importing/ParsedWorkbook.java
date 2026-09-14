package com.homework.analysis.shared.importing;

import java.util.List;

public record ParsedWorkbook<T>(List<T> rows, List<ImportError> errors) {
    public static <T> ParsedWorkbook<T> success(List<T> rows) {
        return new ParsedWorkbook<>(List.copyOf(rows), List.of());
    }

    public static <T> ParsedWorkbook<T> failure(List<ImportError> errors) {
        return new ParsedWorkbook<>(List.of(), List.copyOf(errors));
    }
}

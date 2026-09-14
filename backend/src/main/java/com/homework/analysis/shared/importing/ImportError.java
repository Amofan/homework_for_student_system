package com.homework.analysis.shared.importing;

public record ImportError(String sheet, int row, String field, String code, String message) {
}

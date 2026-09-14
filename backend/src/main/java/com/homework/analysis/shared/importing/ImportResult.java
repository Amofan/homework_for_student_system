package com.homework.analysis.shared.importing;

import java.util.List;

public record ImportResult(int importedRows, List<ImportError> errors) {
    public static ImportResult success(int importedRows) {
        return new ImportResult(importedRows, List.of());
    }

    public static ImportResult failure(List<ImportError> errors) {
        return new ImportResult(0, List.copyOf(errors));
    }

    public boolean successful() {
        return errors.isEmpty();
    }
}

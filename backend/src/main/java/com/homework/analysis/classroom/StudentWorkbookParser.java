package com.homework.analysis.classroom;

import com.homework.analysis.shared.importing.ImportError;
import com.homework.analysis.shared.importing.ParsedWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public final class StudentWorkbookParser {
    static final int MAX_ROWS = 2_000;

    public ParsedWorkbook<StudentRow> parse(InputStream input) throws IOException {
        try (var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                return ParsedWorkbook.failure(List.of(error("students", 1, "header", "HEADER_MISSING", "工作簿没有工作表")));
            }
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<ImportError> errors = new ArrayList<>();
            if (!hasHeader(header, 0, "student_no") || !hasHeader(header, 1, "student_name")) {
                errors.add(error(sheet.getSheetName(), 1, "header", "HEADER_MISSING",
                    "表头必须依次为 student_no、student_name"));
                return ParsedWorkbook.failure(errors);
            }
            int lastRow = sheet.getLastRowNum();
            if (lastRow > MAX_ROWS) {
                errors.add(error(sheet.getSheetName(), MAX_ROWS + 2, "row", "ROW_LIMIT_EXCEEDED", "学生数量不能超过 2000"));
                return ParsedWorkbook.failure(errors);
            }
            DataFormatter formatter = new DataFormatter();
            List<StudentRow> rows = new ArrayList<>();
            for (int index = 1; index <= lastRow; index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                if (formula(row.getCell(0)) || formula(row.getCell(1))) {
                    errors.add(error(sheet.getSheetName(), index + 1, "row", "FORMULA_NOT_ALLOWED", "不允许使用公式单元格"));
                    continue;
                }
                String studentNo = text(row.getCell(0), formatter);
                String name = text(row.getCell(1), formatter);
                if (studentNo.isBlank()) errors.add(error(sheet.getSheetName(), index + 1, "student_no", "VALUE_REQUIRED", "学号不能为空"));
                if (name.isBlank()) errors.add(error(sheet.getSheetName(), index + 1, "student_name", "VALUE_REQUIRED", "姓名不能为空"));
                if (!studentNo.isBlank() && !name.isBlank()) rows.add(new StudentRow(studentNo, name, index + 1));
            }
            return errors.isEmpty() ? ParsedWorkbook.success(rows) : ParsedWorkbook.failure(errors);
        }
    }

    private static boolean hasHeader(Row row, int index, String expected) {
        return row != null && expected.equalsIgnoreCase(new DataFormatter().formatCellValue(row.getCell(index)).trim());
    }

    private static boolean formula(Cell cell) {
        return cell != null && cell.getCellType() == CellType.FORMULA;
    }

    private static String text(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static ImportError error(String sheet, int row, String field, String code, String message) {
        return new ImportError(sheet, row, field, code, message);
    }

    public record StudentRow(String studentNo, String name, int row) {}
}

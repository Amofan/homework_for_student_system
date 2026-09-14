package com.homework.analysis.assignment;

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
public final class AnswerWorkbookParser {
    static final int MAX_ROWS = 20_000;

    public ParsedWorkbook<AnswerRow> parse(InputStream input) throws IOException {
        try (var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                return ParsedWorkbook.failure(List.of(error("answers", 1, "header", "HEADER_MISSING", "工作簿没有工作表")));
            }
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<ImportError> errors = new ArrayList<>();
            String[] headers = {"student_no", "question_code", "answer"};
            for (int i = 0; i < headers.length; i++) {
                if (header == null || !headers[i].equalsIgnoreCase(new DataFormatter().formatCellValue(header.getCell(i)).trim())) {
                    errors.add(error(sheet.getSheetName(), 1, "header", "HEADER_MISSING",
                        "表头必须依次为 student_no、question_code、answer"));
                    return ParsedWorkbook.failure(errors);
                }
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                return ParsedWorkbook.failure(List.of(error(sheet.getSheetName(), MAX_ROWS + 2, "row",
                    "ROW_LIMIT_EXCEEDED", "答案行数不能超过 20000")));
            }
            DataFormatter formatter = new DataFormatter();
            List<AnswerRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                boolean formula = false;
                for (int column = 0; column < 3; column++) {
                    Cell cell = row.getCell(column);
                    formula |= cell != null && cell.getCellType() == CellType.FORMULA;
                }
                if (formula) {
                    errors.add(error(sheet.getSheetName(), index + 1, "row", "FORMULA_NOT_ALLOWED", "不允许使用公式单元格"));
                    continue;
                }
                String studentNo = text(row.getCell(0), formatter);
                String questionCode = text(row.getCell(1), formatter);
                String answer = text(row.getCell(2), formatter);
                if (studentNo.isBlank()) errors.add(error(sheet.getSheetName(), index + 1, "student_no", "VALUE_REQUIRED", "学号不能为空"));
                if (questionCode.isBlank()) errors.add(error(sheet.getSheetName(), index + 1, "question_code", "VALUE_REQUIRED", "题目编码不能为空"));
                if (!studentNo.isBlank() && !questionCode.isBlank()) rows.add(new AnswerRow(studentNo, questionCode, answer, index + 1));
            }
            return errors.isEmpty() ? ParsedWorkbook.success(rows) : ParsedWorkbook.failure(errors);
        }
    }

    private static String text(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static ImportError error(String sheet, int row, String field, String code, String message) {
        return new ImportError(sheet, row, field, code, message);
    }

    public record AnswerRow(String studentNo, String questionCode, String answer, int row) {}
}

package com.homework.analysis.assignment;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerWorkbookParserTest {
    private final AnswerWorkbookParser parser = new AnswerWorkbookParser();

    @Test
    void parsesStructuredAnswerRows() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("answers");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("student_no");
            header.createCell(1).setCellValue("question_code");
            header.createCell(2).setCellValue("answer");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("001");
            row.createCell(1).setCellValue("Q-001");
            row.createCell(2).setCellValue("x=2");
            var output = new ByteArrayOutputStream();
            workbook.write(output);

            var result = parser.parse(new ByteArrayInputStream(output.toByteArray()));

            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).containsExactly(new AnswerWorkbookParser.AnswerRow("001", "Q-001", "x=2", 2));
        }
    }
}

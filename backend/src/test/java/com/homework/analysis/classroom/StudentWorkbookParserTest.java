package com.homework.analysis.classroom;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudentWorkbookParserTest {
    private final StudentWorkbookParser parser = new StudentWorkbookParser();

    @Test
    void parsesRowsAndReportsFormulaCellsWithoutReturningPartialData() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("student_no");
            header.createCell(1).setCellValue("student_name");
            Row valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("001");
            valid.createCell(1).setCellValue("张三");
            Row invalid = sheet.createRow(2);
            invalid.createCell(0).setCellFormula("1+1");
            invalid.createCell(1).setCellValue("李四");

            var result = parser.parse(new ByteArrayInputStream(bytes(workbook)));

            assertThat(result.rows()).isEmpty();
            assertThat(result.errors()).extracting("code").contains("FORMULA_NOT_ALLOWED");
        }
    }

    @Test
    void requiresExactHeaders() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("students");
            sheet.createRow(0).createCell(0).setCellValue("学号");
            var result = parser.parse(new ByteArrayInputStream(bytes(workbook)));
            assertThat(result.errors()).extracting("code").contains("HEADER_MISSING");
        }
    }

    private byte[] bytes(XSSFWorkbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}

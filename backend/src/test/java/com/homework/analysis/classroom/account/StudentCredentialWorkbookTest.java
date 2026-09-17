package com.homework.analysis.classroom.account;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StudentCredentialWorkbookTest {

    private final StudentCredentialWorkbook workbook = new StudentCredentialWorkbook();

    @Test
    void 表头固定为四列且顺序稳定() throws IOException {
        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(workbook.write(List.of())))) {
            Sheet sheet = parsed.getSheetAt(0);
            assertThat(cells(sheet.getRow(0))).containsExactly(
                "student_no", "name", "username", "temporary_password");
            assertThat(sheet.getLastRowNum()).isZero();
        }
    }

    /**
     * 已有账号的行（{@code temporaryPassword == null}）必须整行消失。
     *
     * <p>如果保留成空白密码列，教师会以为“密码丢了”；而如果写入上一次的密码，
     * 就等于把已经失效的旧口令当成本次交付物发出去。
     */
    @Test
    void 只写出本次真正产生明文密码的行() throws IOException {
        byte[] bytes = workbook.write(List.of(
            new StudentCredentialWorkbook.CredentialRow("001", "张三", "stu_aaaa", "Temp1234Abcd5678"),
            new StudentCredentialWorkbook.CredentialRow("002", "李四", "stu_bbbb", null),
            new StudentCredentialWorkbook.CredentialRow("003", "王五", null, "Temp5678Efgh1234"),
            new StudentCredentialWorkbook.CredentialRow("004", "赵六", "stu_dddd", "   ")));

        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = parsed.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(cells(sheet.getRow(1))).containsExactly(
                "001", "张三", "stu_aaaa", "Temp1234Abcd5678");
        }
    }

    @Test
    void 中文姓名不被转义或截断() throws IOException {
        byte[] bytes = workbook.write(List.of(
            new StudentCredentialWorkbook.CredentialRow("070101", "张晨曦", "stu_xyz", "Pw1234567890abcd")));

        try (XSSFWorkbook parsed = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(cells(parsed.getSheetAt(0).getRow(1)).get(1)).isEqualTo("张晨曦");
        }
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            values.add(row.getCell(index) == null ? null : row.getCell(index).getStringCellValue());
        }
        return values;
    }
}

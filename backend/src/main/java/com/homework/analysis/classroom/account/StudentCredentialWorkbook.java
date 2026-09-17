package com.homework.analysis.classroom.account;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 把一次性明文凭据渲染成 XLSX。
 *
 * <p>本类不落库、不查库：明文凭据只存在于当前请求的内存中，工作簿生成后即被丢弃。
 * 因此“只有本次请求产生了新密码的行”才有内容——已开通学生的行会被静默跳过，
 * 不会在文件里留下一列空白造成“密码丢了”的误解。
 */
@Component
public final class StudentCredentialWorkbook {

    static final List<String> HEADERS = List.of("student_no", "name", "username", "temporary_password");

    static final String CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public byte[] write(List<CredentialRow> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("credentials");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
            }
            int rowIndex = 1;
            for (CredentialRow row : rows) {
                if (!row.hasPlaintextPassword()) {
                    continue;
                }
                Row target = sheet.createRow(rowIndex++);
                target.createCell(0).setCellValue(nullToEmpty(row.studentNo()));
                target.createCell(1).setCellValue(nullToEmpty(row.name()));
                target.createCell(2).setCellValue(row.username());
                target.createCell(3).setCellValue(row.temporaryPassword());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 工作簿行。字段刻意少而稳定：不含学生 ID、班级 ID 等内部标识，导出文件只用于线下发放。 */
    public record CredentialRow(String studentNo, String name, String username, String temporaryPassword) {

        public static CredentialRow of(ProvisionedStudentAccount account) {
            return new CredentialRow(account.studentNo(), account.name(),
                account.username(), account.temporaryPassword());
        }

        public boolean hasPlaintextPassword() {
            return username != null && !username.isBlank()
                && temporaryPassword != null && !temporaryPassword.isBlank();
        }
    }
}

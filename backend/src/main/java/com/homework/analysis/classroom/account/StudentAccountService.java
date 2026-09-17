package com.homework.analysis.classroom.account;

import com.homework.analysis.auth.AccountStatus;
import com.homework.analysis.classroom.ClassroomService;
import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学生账号的开通与重置。
 *
 * <p>两条硬约束贯穿本类：
 * <ol>
 *   <li>登录名与临时密码只由服务端生成，不拼接姓名、学号或身份证号，
 *       否则学生之间可以互相推导登录名；</li>
 *   <li>明文密码只在发起请求的那一次响应里出现，数据库只留 BCrypt 哈希。</li>
 * </ol>
 */
@Service
public class StudentAccountService {

    /** 剔除 i、l、o、0、1：这些字符在打印和口述时最容易混淆。 */
    private static final String USERNAME_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int USERNAME_RANDOM_LENGTH = 12;
    private static final String USERNAME_PREFIX = "stu_";

    /** 口令字符集同样剔除易混字符，但仍覆盖大写、小写、数字三类。 */
    private static final String PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    /** 单次开通里登录名的最大重试次数。32^12 的空间下连续 5 次撞名已属异常。 */
    private static final int USERNAME_MAX_ATTEMPTS = 5;

    private final ClassroomService classrooms;
    private final StudentAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    StudentAccountService(ClassroomService classrooms, StudentAccountRepository repository,
                          PasswordEncoder passwordEncoder) {
        this.classrooms = classrooms;
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 为班级中的指定学生开通账号。
     *
     * <p>整批在一个事务里完成：任意一个学生不属于本班就整批失败，不出现“开一半”的名单。
     * 已开通的学生返回 {@code temporaryPassword=null}，因此重复点击“开通账号”不会泄露出新密码。
     */
    @Transactional
    public List<ProvisionedStudentAccount> provision(long teacherId, long classId, List<Long> studentIds) {
        classrooms.get(teacherId, classId);
        List<Long> requested = studentIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            throw new DomainException("STUDENT_IDS_REQUIRED", "请至少选择一名学生", HttpStatus.BAD_REQUEST);
        }
        Map<Long, StudentAccountRepository.StudentAccountRow> owned = new LinkedHashMap<>();
        for (StudentAccountRepository.StudentAccountRow row : repository.findClassStudents(teacherId, classId)) {
            owned.put(row.studentId(), row);
        }
        List<ProvisionedStudentAccount> results = new ArrayList<>(requested.size());
        for (Long studentId : requested) {
            StudentAccountRepository.StudentAccountRow row = owned.get(studentId);
            if (row == null) {
                throw new DomainException("STUDENT_NOT_FOUND", "学生不存在", HttpStatus.NOT_FOUND);
            }
            results.add(provisionOne(row));
        }
        return results;
    }

    /** 重置指定学生的密码。返回的新临时密码只出现这一次，并强制其下次登录改密。 */
    @Transactional
    public ProvisionedStudentAccount resetPassword(long teacherId, long studentId) {
        StudentAccountRepository.StudentAccountRow row = repository.findOwnedStudent(teacherId, studentId)
            .orElseThrow(() -> new DomainException("STUDENT_NOT_FOUND", "学生不存在", HttpStatus.NOT_FOUND));
        if (!row.provisioned()) {
            throw new DomainException("STUDENT_ACCOUNT_NOT_PROVISIONED",
                "该学生尚未开通账号，请先开通", HttpStatus.CONFLICT);
        }
        String password = generateTemporaryPassword();
        repository.replacePassword(row.userId(), passwordEncoder.encode(password),
            AccountStatus.PASSWORD_CHANGE_REQUIRED.name());
        return new ProvisionedStudentAccount(row.studentId(), row.studentNo(), row.name(),
            row.username(), password, false);
    }

    private ProvisionedStudentAccount provisionOne(StudentAccountRepository.StudentAccountRow row) {
        if (row.provisioned()) {
            return new ProvisionedStudentAccount(row.studentId(), row.studentNo(), row.name(),
                row.username(), null, false);
        }
        for (int attempt = 0; attempt < USERNAME_MAX_ATTEMPTS; attempt++) {
            String username = generateUsername();
            String password = generateTemporaryPassword();
            Long userId = repository.insertStudentAccount(username, passwordEncoder.encode(password),
                AccountStatus.PASSWORD_CHANGE_REQUIRED.name());
            if (userId == null) {
                continue;
            }
            if (repository.bindStudentUser(row.studentId(), userId) == 0) {
                // 读到的 user_id 为空但绑定失败，说明有并发开通。抛异常让整批回滚，
                // 连带撤销刚插入的 app_user，不留无人认领的孤儿账号。
                throw new DomainException("STUDENT_ACCOUNT_CONFLICT",
                    "该学生的账号已被其他操作开通，请刷新后重试", HttpStatus.CONFLICT);
            }
            return new ProvisionedStudentAccount(row.studentId(), row.studentNo(), row.name(),
                username, password, true);
        }
        throw new DomainException("STUDENT_USERNAME_EXHAUSTED",
            "连续生成登录名均被占用，请重试", HttpStatus.CONFLICT);
    }

    private String generateUsername() {
        return USERNAME_PREFIX + randomToken(USERNAME_RANDOM_LENGTH, USERNAME_ALPHABET);
    }

    private String generateTemporaryPassword() {
        return randomToken(PASSWORD_LENGTH, PASSWORD_ALPHABET);
    }

    /** 用 {@link SecureRandom} 逐字符取值，不依赖 {@code Math.random} 或时间种子。 */
    private String randomToken(int length, String alphabet) {
        StringBuilder token = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }
}

package com.homework.analysis.auth;

import com.homework.analysis.classroom.account.PasswordChangeCommand;
import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
final class AuthService {

    /** 与令牌有效期一致，避免前端拿着“还剩 30 分钟”的文案却与实际过期时间不符。 */
    private static final long TOKEN_TTL_SECONDS = 30 * 60L;

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    LoginResponse login(LoginRequest request) {
        AuthRepository.LoginAccount account = repository.findLoginAccount(request.username())
            .filter(AuthRepository.LoginAccount::allowsLogin)
            .filter(found -> passwordEncoder.matches(request.password(), found.passwordHash()))
            .orElseThrow(() -> new DomainException(
                "AUTH_INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        return new LoginResponse(issueToken(account), "Bearer", TOKEN_TTL_SECONDS);
    }

    /**
     * 修改当前主体的密码，成功后换发一枚新令牌。
     *
     * <p>换发令牌而不是让旧令牌自然过期：旧令牌里可能带着
     * {@code passwordChangeRequired=true}，不换发的话学生改完密码仍然被挡在改密页。
     * 新令牌按改密后的状态签发，改密标志归零。
     */
    LoginResponse changePassword(AuthPrincipal principal, PasswordChangeCommand command) {
        AuthRepository.AccountCredential credential = repository.findCredential(principal.userId())
            .filter(AuthRepository.AccountCredential::allowsPasswordChange)
            .orElseThrow(() -> new DomainException(
                "ACCOUNT_NOT_FOUND", "账号不存在或已停用", HttpStatus.NOT_FOUND));

        if (!passwordEncoder.matches(command.currentPassword(), credential.passwordHash())) {
            throw new DomainException("AUTH_INVALID_CREDENTIALS", "当前密码不正确", HttpStatus.UNAUTHORIZED);
        }
        if (passwordEncoder.matches(command.newPassword(), credential.passwordHash())) {
            throw new DomainException("PASSWORD_REUSED", "新密码不能与当前密码相同", HttpStatus.BAD_REQUEST);
        }
        requireStrongPassword(command.newPassword());

        repository.replacePassword(principal.userId(),
            passwordEncoder.encode(command.newPassword()), AccountStatus.ACTIVE);
        return new LoginResponse(issueTokenFor(principal), "Bearer", TOKEN_TTL_SECONDS);
    }

    /** 当前主体资料。教师返回 {@code teacherId} 与校名，学生返回 {@code studentId} 与改密标志。 */
    MeResponse me(AuthPrincipal principal) {
        if (principal.isTeacher()) {
            AuthRepository.TeacherProfile teacher = repository.findTeacher(principal.teacherId())
                .orElseThrow(() -> new DomainException(
                    "TEACHER_NOT_FOUND", "教师不存在或已停用", HttpStatus.NOT_FOUND));
            return new MeResponse(principal.userId(), AuthPrincipal.ROLE_TEACHER,
                teacher.teacherId(), null, teacher.displayName(), teacher.schoolName(),
                principal.passwordChangeRequired());
        }
        if (principal.isStudent()) {
            AuthRepository.StudentProfile student = repository.findStudent(principal.studentId())
                .orElseThrow(() -> new DomainException(
                    "STUDENT_NOT_FOUND", "学生不存在或已停用", HttpStatus.NOT_FOUND));
            return new MeResponse(principal.userId(), AuthPrincipal.ROLE_STUDENT,
                null, student.studentId(), student.displayName(), null,
                student.accountStatus().requiresPasswordChange());
        }
        throw new DomainException("AUTH_ROLE_UNSUPPORTED", "账号角色不受支持", HttpStatus.FORBIDDEN);
    }

    /**
     * 按角色签发令牌。
     *
     * <p>账号状态为 {@code PASSWORD_CHANGE_REQUIRED} 时，令牌带上同名标志；服务端据此
     * 把学生限制在改密接口，前端路由守卫也据此跳转。
     */
    private String issueToken(AuthRepository.LoginAccount account) {
        return switch (account.role()) {
            case AuthPrincipal.ROLE_TEACHER -> jwtService.issueTeacher(account.userId(), account.teacherId());
            case AuthPrincipal.ROLE_STUDENT -> jwtService.issueStudent(
                account.userId(), account.studentId(), account.accountStatus().requiresPasswordChange());
            default -> throw new DomainException(
                "AUTH_INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        };
    }

    /** 改密后按登录时同样的角色规则换发令牌，但不再带改密标志。 */
    private String issueTokenFor(AuthPrincipal principal) {
        if (principal.isTeacher()) {
            return jwtService.issueTeacher(principal.userId(), principal.teacherId());
        }
        if (principal.isStudent()) {
            return jwtService.issueStudent(principal.userId(), principal.studentId(), false);
        }
        throw new DomainException("AUTH_ROLE_UNSUPPORTED", "账号角色不受支持", HttpStatus.FORBIDDEN);
    }

    /**
     * 新密码需包含大写、小写、数字、符号中的至少三类。
     *
     * <p>长度由 {@link PasswordChangeCommand} 上的约束负责，这里只判字符类别，
     * 两者合起来才算一条完整口令策略，任何一处放松都会让策略名不副实。
     */
    private static void requireStrongPassword(String password) {
        int categories = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) categories++;
        if (password.chars().anyMatch(Character::isLowerCase)) categories++;
        if (password.chars().anyMatch(Character::isDigit)) categories++;
        if (password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) categories++;
        if (categories < 3) {
            throw new DomainException("PASSWORD_TOO_WEAK",
                "新密码需包含大写字母、小写字母、数字、符号中的至少三类", HttpStatus.BAD_REQUEST);
        }
    }

    record LoginResponse(String accessToken, String tokenType, long expiresIn) {}

    /**
     * 统一的当前主体响应。
     *
     * <p>教师与学生共用一个结构，角色不适用的字段为 {@code null}，被
     * {@code spring.jackson.default-property-inclusion=non_null} 省略。前端据此把
     * JSON 收窄成判别联合，不依赖字段顺序也不需要类型断言。
     */
    record MeResponse(
        long userId,
        String role,
        Long teacherId,
        Long studentId,
        String displayName,
        String schoolName,
        boolean passwordChangeRequired) {}
}

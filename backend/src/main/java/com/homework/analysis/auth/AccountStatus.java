package com.homework.analysis.auth;

/**
 * 账号状态。
 *
 * <p>取值写入 {@code app_user.account_status}，因此名称即数据库中的字面量，改名等于迁移。
 */
public enum AccountStatus {

    /** 正常可用。 */
    ACTIVE,

    /** 系统生成的临时密码尚未更换，登录后只能访问改密接口。 */
    PASSWORD_CHANGE_REQUIRED,

    /** 教师停用或学生被删除后同步停用，保留历史审计数据。 */
    DISABLED;

    /** 解析数据库字面量；未知取值视为不可登录，避免新状态被静默当作 ACTIVE。 */
    public static AccountStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DISABLED;
        }
        for (AccountStatus status : values()) {
            if (status.name().equalsIgnoreCase(raw.trim())) {
                return status;
            }
        }
        return DISABLED;
    }

    public boolean requiresPasswordChange() {
        return this == PASSWORD_CHANGE_REQUIRED;
    }

    public boolean allowsLogin() {
        return this != DISABLED;
    }
}

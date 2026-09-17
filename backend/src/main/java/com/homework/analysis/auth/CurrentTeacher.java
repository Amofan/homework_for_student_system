package com.homework.analysis.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 教师身份取用点。
 *
 * <p>现有教师接口均通过本类取 {@code teacherId}，改造后统一委托给 {@link CurrentActor}，
 * 于是这些接口在服务层自动获得“学生令牌一律 403”的保护，不需要逐个改动。
 */
@Component
public final class CurrentTeacher {
    private final CurrentActor currentActor;

    CurrentTeacher(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    public long id(Authentication authentication) {
        return currentActor.requireTeacher(authentication).teacherId();
    }
}

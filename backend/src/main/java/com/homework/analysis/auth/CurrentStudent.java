package com.homework.analysis.auth;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** 学生身份取用点，学生端接口一律通过本类取 {@code studentId}。 */
@Component
public final class CurrentStudent {
    private final CurrentActor currentActor;

    CurrentStudent(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    public long id(Authentication authentication) {
        return currentActor.requireStudent(authentication).studentId();
    }
}

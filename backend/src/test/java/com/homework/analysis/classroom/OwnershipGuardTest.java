package com.homework.analysis.classroom;

import com.homework.analysis.shared.error.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnershipGuardTest {
    private final OwnershipGuard guard = new OwnershipGuard();

    @Test
    void allowsTheOwningTeacher() {
        assertThatCode(() -> guard.requireOwned(7L, 7L, "CLASS_NOT_FOUND"))
            .doesNotThrowAnyException();
    }

    @Test
    void hidesAClassOwnedByAnotherTeacher() {
        assertThatThrownBy(() -> guard.requireOwned(7L, 8L, "CLASS_NOT_FOUND"))
            .isInstanceOf(DomainException.class)
            .extracting("code")
            .isEqualTo("CLASS_NOT_FOUND");
    }
}

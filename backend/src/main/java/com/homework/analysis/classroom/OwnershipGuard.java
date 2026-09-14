package com.homework.analysis.classroom;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class OwnershipGuard {
    public void requireOwned(long currentTeacherId, long ownerTeacherId, String notFoundCode) {
        if (currentTeacherId != ownerTeacherId) {
            throw new DomainException(notFoundCode, "资源不存在", HttpStatus.NOT_FOUND);
        }
    }
}

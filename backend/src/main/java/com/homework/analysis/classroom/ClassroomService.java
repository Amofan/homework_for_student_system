package com.homework.analysis.classroom;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomService {
    private final ClassroomRepository repository;

    ClassroomService(ClassroomRepository repository) {
        this.repository = repository;
    }

    public List<ClassroomView> list(long teacherId) {
        return repository.findAllOwned(teacherId);
    }

    public ClassroomView get(long teacherId, long classId) {
        return repository.findOwned(teacherId, classId)
            .orElseThrow(() -> new DomainException("CLASS_NOT_FOUND", "班级不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public ClassroomView create(long teacherId, ClassroomRequest request) {
        try {
            return repository.insert(teacherId, request);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("CLASS_CODE_DUPLICATE", "班级编码已存在", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public ClassroomView update(long teacherId, long classId, ClassroomRequest request) {
        try {
            if (repository.updateOwned(teacherId, classId, request) == 0) throw notFound();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("CLASS_CODE_DUPLICATE", "班级编码已存在", HttpStatus.CONFLICT);
        }
        return get(teacherId, classId);
    }

    @Transactional
    public void delete(long teacherId, long classId) {
        get(teacherId, classId);
        if (repository.hasActiveAssignmentsOwned(teacherId, classId)) {
            throw new DomainException("CLASS_HAS_ASSIGNMENTS", "班级已有作业，不能删除", HttpStatus.CONFLICT);
        }
        if (repository.softDeleteOwned(teacherId, classId) == 0) throw notFound();
    }

    private static DomainException notFound() {
        return new DomainException("CLASS_NOT_FOUND", "班级不存在", HttpStatus.NOT_FOUND);
    }
}

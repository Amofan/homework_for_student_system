package com.homework.analysis.classroom;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StudentService {
    private final ClassroomService classrooms;
    private final StudentRepository repository;

    StudentService(ClassroomService classrooms, StudentRepository repository) {
        this.classrooms = classrooms;
        this.repository = repository;
    }

    List<StudentView> list(long teacherId, long classId) {
        classrooms.get(teacherId, classId);
        return repository.findAllOwned(teacherId, classId);
    }

    @Transactional
    StudentView create(long teacherId, long classId, StudentRequest request) {
        classrooms.get(teacherId, classId);
        try {
            return repository.insert(teacherId, classId, request);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("STUDENT_NO_DUPLICATE", "该班级中的学号已存在", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    StudentView update(long teacherId, long studentId, StudentRequest request) {
        try {
            if (repository.updateOwned(teacherId, studentId, request) == 0) {
                throw notFound();
            }
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("STUDENT_NO_DUPLICATE", "该班级中的学号已存在", HttpStatus.CONFLICT);
        }
        return repository.findOwned(teacherId, studentId).orElseThrow(StudentService::notFound);
    }

    @Transactional
    void delete(long teacherId, long studentId) {
        if (repository.softDeleteOwned(teacherId, studentId) == 0) {
            throw notFound();
        }
        // 名册删除必须同步停用登录能力，否则被移出班级的学生还能继续登入并看到作业。
        repository.disableAccountOwned(teacherId, studentId);
    }

    private static DomainException notFound() {
        return new DomainException("STUDENT_NOT_FOUND", "学生不存在", HttpStatus.NOT_FOUND);
    }
}

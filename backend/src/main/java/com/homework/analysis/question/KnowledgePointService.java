package com.homework.analysis.question;

import com.homework.analysis.shared.error.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class KnowledgePointService {
    private final JdbcClient jdbc;

    KnowledgePointService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<KnowledgePointView> list(long teacherId) {
        return jdbc.sql("""
                select id, parent_id, code, name, grade, active from knowledge_point
                where teacher_id = :teacherId order by grade, parent_id, code
                """)
            .param("teacherId", teacherId)
            .query(KnowledgePointService::map)
            .list();
    }

    @Transactional
    public KnowledgePointView create(long teacherId, KnowledgePointRequest request) {
        validateParent(teacherId, request.parentId());
        try {
            jdbc.sql("""
                    insert into knowledge_point(teacher_id, parent_id, code, name, grade, active)
                    values (:teacherId, :parentId, :code, :name, :grade, :active)
                    """)
                .param("teacherId", teacherId)
                .param("parentId", request.parentId())
                .param("code", request.code().trim())
                .param("name", request.name().trim())
                .param("grade", request.grade())
                .param("active", request.active())
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("KNOWLEDGE_POINT_CODE_DUPLICATE", "知识点编码已存在", HttpStatus.CONFLICT);
        }
        return jdbc.sql("""
                select id, parent_id, code, name, grade, active from knowledge_point
                where teacher_id = :teacherId and code = :code
                """)
            .param("teacherId", teacherId)
            .param("code", request.code().trim())
            .query(KnowledgePointService::map)
            .single();
    }

    public void requireActiveOwned(long teacherId, long knowledgePointId) {
        int found = jdbc.sql("""
                select count(*) from knowledge_point
                where id = :id and teacher_id = :teacherId and active = true
                """)
            .param("id", knowledgePointId)
            .param("teacherId", teacherId)
            .query(Integer.class)
            .single();
        if (found == 0) {
            throw new DomainException("KNOWLEDGE_POINT_INVALID", "知识点不存在、未启用或无权访问");
        }
    }

    private void validateParent(long teacherId, Long parentId) {
        if (parentId != null) {
            requireActiveOwned(teacherId, parentId);
        }
    }

    private static KnowledgePointView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long rawParent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : rawParent;
        return new KnowledgePointView(rs.getLong("id"), parentId, rs.getString("code"),
            rs.getString("name"), rs.getInt("grade"), rs.getBoolean("active"));
    }
}

package com.homework.analysis.question;

import com.homework.analysis.shared.error.DomainException;
import com.homework.analysis.shared.jdbc.GeneratedKeys;
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
        long knowledgePointId;
        try {
            knowledgePointId = GeneratedKeys.insert(jdbc, """
                    insert into knowledge_point(teacher_id, parent_id, code, name, grade, active)
                    values (:teacherId, :parentId, :code, :name, :grade, :active)
                    """, statement -> statement
                .param("teacherId", teacherId)
                .param("parentId", request.parentId())
                .param("code", request.code().trim())
                .param("name", request.name().trim())
                .param("grade", request.grade())
                .param("active", request.active()));
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("KNOWLEDGE_POINT_CODE_DUPLICATE", "知识点编码已存在", HttpStatus.CONFLICT);
        }
        return jdbc.sql("""
                select id, parent_id, code, name, grade, active from knowledge_point
                where id = :id and teacher_id = :teacherId
                """)
            .param("id", knowledgePointId)
            .param("teacherId", teacherId)
            .query(KnowledgePointService::map)
            .single();
    }

    /**
     * 校验知识点存在、属于当前教师且已启用。
     *
     * <p>"不属于我"与"不存在"合并成同一个 404：若分开返回，别的教师就能拿编号逐个试探，
     * 从状态码差异反推出哪些知识点存在。这与其它教师边界（班级、学生、题目、作业、
     * 评分结果、练习单）保持一致的"不泄露资源存在性"口径。
     *
     * <p>"存在、有权限、但已停用"不泄露任何别人的信息，而且是教师自己改得回来的输入问题，
     * 所以仍然报 400，给出可操作的原因，不要也压成 404。
     */
    public void requireActiveOwned(long teacherId, long knowledgePointId) {
        Boolean active = jdbc.sql("""
                select active from knowledge_point
                where id = :id and teacher_id = :teacherId
                """)
            .param("id", knowledgePointId)
            .param("teacherId", teacherId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(() -> new DomainException(
                "KNOWLEDGE_POINT_NOT_FOUND", "知识点不存在", HttpStatus.NOT_FOUND));
        if (!active) {
            throw new DomainException("KNOWLEDGE_POINT_INACTIVE", "知识点已停用，不能再用于新题目");
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

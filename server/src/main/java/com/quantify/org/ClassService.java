package com.quantify.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.auth.ScopeResolver;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.log.AuditLogService;
import com.quantify.student.Student;
import com.quantify.student.StudentMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 班级管理（仅 ADMIN）。
 *
 * <p>只读下拉仍走 {@link OrgController} 的 {@code /api/meta/classes}（只返回启用班、按数据范围）；
 * 这里是管理用接口，含停用班。写操作全部记 {@code audit_log}（target_type=CLASS）。
 *
 * <p>班级结构变更后必须 {@link ScopeResolver#evictAll()}，否则已登录账号
 * 最多 60 秒内看不到新班，表现为「新班不存在」。
 */
@Service
@RequiredArgsConstructor
public class ClassService {

    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final StudentMapper studentMapper;
    private final AuditLogService auditLogService;
    private final ScopeResolver scopeResolver;

    public List<Map<String, Object>> list(Long gradeId, Integer status, String keyword) {
        var qw = Wrappers.<SysClass>lambdaQuery();
        if (gradeId != null) {
            qw.eq(SysClass::getGradeId, gradeId);
        }
        if (status != null) {
            qw.eq(SysClass::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like(SysClass::getName, keyword.trim());
        }
        qw.orderByAsc(SysClass::getGradeId).orderByAsc(SysClass::getClassNo);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysClass c : classMapper.selectList(qw)) {
            SysGrade g = gradeMapper.selectById(c.getGradeId());
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("gradeId", c.getGradeId());
            m.put("gradeName", g == null ? "" : g.getGradeName());
            m.put("classNo", c.getClassNo());
            m.put("status", c.getStatus());
            m.put("studentCount", countActiveStudents(c.getId()));
            rows.add(m);
        }
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(ClassDTO dto) {
        if (dto.getGradeId() == null || dto.getClassNo() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择年级并填写班号");
        }
        if (dto.getClassNo() < 1 || dto.getClassNo() > 99) {
            throw new BizException(ErrorCode.BAD_REQUEST, "班号只能是 1~99");
        }
        SysGrade grade = gradeMapper.selectById(dto.getGradeId());
        if (grade == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "年级不存在");
        }
        ensureUnique(dto.getGradeId(), dto.getClassNo(), null);

        SysClass c = new SysClass();
        c.setGradeId(dto.getGradeId());
        c.setClassNo(dto.getClassNo());
        c.setName(dto.getName() == null || dto.getName().isBlank()
                ? grade.getGradeName() + dto.getClassNo() + "班"
                : dto.getName().trim());
        c.setStatus(1);
        classMapper.insert(c);

        scopeResolver.evictAll();
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), c.getId(),
                "CLASS", c.getId(), "CREATE", null, snapshot(c));
        return c.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ClassUpdateDTO dto) {
        SysClass exist = requireClass(id);
        Integer classNo = dto.getClassNo() == null ? exist.getClassNo() : dto.getClassNo();
        if (classNo == null || classNo < 1 || classNo > 99) {
            throw new BizException(ErrorCode.BAD_REQUEST, "班号只能是 1~99");
        }
        // 不允许改 grade_id：班级换年级会让历史归属漂移
        ensureUnique(exist.getGradeId(), classNo, id);

        SysClass upd = new SysClass();
        upd.setId(id);
        upd.setClassNo(classNo);
        if (dto.getName() != null && !dto.getName().isBlank()) {
            upd.setName(dto.getName().trim());
        }
        classMapper.updateById(upd);

        scopeResolver.evictAll();
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), id,
                "CLASS", id, "UPDATE", snapshot(exist), snapshot(requireClass(id)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeStatus(Long id, StatusDTO dto) {
        SysClass exist = requireClass(id);
        Integer target = dto.getStatus();
        if (target == null || (target != 0 && target != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是启用 / 停用");
        }

        long active = countActiveStudents(id);
        if (target == 0 && active > 0 && !Boolean.TRUE.equals(dto.getCascadeStudents())) {
            throw new BizException(ErrorCode.BIZ_ERROR,
                    "该班级还有 " + active + " 名在册学生，请确认是否同时置为离班");
        }

        SysClass upd = new SysClass();
        upd.setId(id);
        upd.setStatus(target);
        classMapper.updateById(upd);

        long affected = 0;
        if (target == 0 && active > 0) {
            affected = studentMapper.update(null, Wrappers.<Student>lambdaUpdate()
                    .eq(Student::getClassId, id)
                    .eq(Student::getStatus, 1)
                    .set(Student::getStatus, 0));
        }

        scopeResolver.evictAll();
        Map<String, Object> after = new HashMap<>();
        after.put("status", target);
        after.put("cascadeStudents", Boolean.TRUE.equals(dto.getCascadeStudents()));
        after.put("affectedStudents", affected);
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), id,
                "CLASS", id, target == 1 ? "ENABLE" : "DISABLE", snapshot(exist), after);

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("status", target);
        result.put("affectedStudents", affected);
        return result;
    }

    private long countActiveStudents(Long classId) {
        Long n = studentMapper.selectCount(Wrappers.<Student>lambdaQuery()
                .eq(Student::getClassId, classId)
                .eq(Student::getStatus, 1));
        return n == null ? 0 : n;
    }

    private void ensureUnique(Long gradeId, Integer classNo, Long excludeId) {
        var qw = Wrappers.<SysClass>lambdaQuery()
                .eq(SysClass::getGradeId, gradeId)
                .eq(SysClass::getClassNo, classNo);
        if (excludeId != null) {
            qw.ne(SysClass::getId, excludeId);
        }
        Long dup = classMapper.selectCount(qw);
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.BIZ_ERROR, "该年级下班号已存在：" + classNo);
        }
    }

    private SysClass requireClass(Long id) {
        SysClass c = classMapper.selectById(id);
        if (c == null) {
            throw BizException.notFound("班级不存在");
        }
        return c;
    }

    private static Map<String, Object> snapshot(SysClass c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("gradeId", c.getGradeId());
        m.put("classNo", c.getClassNo());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        return m;
    }

    @Data
    public static class ClassDTO {
        private Long gradeId;
        private Integer classNo;
        private String name;
    }

    @Data
    public static class ClassUpdateDTO {
        private Integer classNo;
        private String name;
    }

    @Data
    public static class StatusDTO {
        private Integer status;
        private Boolean cascadeStudents;
    }
}

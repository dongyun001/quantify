package com.quantify.student;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.PageResult;
import com.quantify.common.Role;
import com.quantify.log.AuditLogService;
import com.quantify.org.ClassMapper;
import com.quantify.org.SysClass;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentMapper studentMapper;
    private final ClassMapper classMapper;
    private final AuditLogService auditLogService;

    /**
     * 学生列表。
     *
     * <p>{@code classId} 参数来自前端，但<b>只有落在数据范围内才会生效</b>——
     * 三级账号传了别的班也会被忽略，永远看不到范围外的人。
     */
    public PageResult<Student> page(long page, long size, String keyword, Long classId,
                                    Long gradeId, Integer status) {
        List<Long> scope = ScopeContext.getClassIds();
        if (scope.isEmpty()) {
            return PageResult.of(0, page, size, List.of());
        }

        // 非 ADMIN 只能看本范围的在册学生；ADMIN 可传 0（离班）或空（全部）
        Integer effectiveStatus = Role.isAdmin(ScopeContext.getRole())
                ? status : Integer.valueOf(1);
        var qw = Wrappers.<Student>lambdaQuery()
                .in(Student::getClassId, scope);
        if (effectiveStatus != null) {
            qw.eq(Student::getStatus, effectiveStatus);
        }

        // 前端传的 classId 必须在范围内才采纳，否则忽略（而不是报错）
        if (classId != null && scope.contains(classId)) {
            qw.eq(Student::getClassId, classId);
        }
        if (gradeId != null) {
            List<Long> ids = classMapper.selectList(Wrappers.<SysClass>lambdaQuery()
                            .eq(SysClass::getGradeId, gradeId))
                    .stream().map(SysClass::getId).toList();
            ids.retainAll(scope);
            if (ids.isEmpty()) {
                return PageResult.of(0, page, size, List.of());
            }
            qw.in(Student::getClassId, ids);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Student::getStudentNo, keyword).or().like(Student::getName, keyword));
        }
        qw.orderByAsc(Student::getStudentNo);

        var p = studentMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), qw);
        fillClassName(p.getRecords());
        return PageResult.of(p.getTotal(), page, size, p.getRecords());
    }

    /** 录入时选人用的模糊搜索。返回精简字段，不分页 */
    public List<Student> search(String keyword, Integer limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        int lim = limit == null ? 20 : Math.min(limit, 50);
        List<Long> scope = ScopeContext.getClassIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        var list = studentMapper.selectList(Wrappers.<Student>lambdaQuery()
                .in(Student::getClassId, scope)
                .eq(Student::getStatus, 1)
                .and(w -> w.like(Student::getStudentNo, keyword).or().like(Student::getName, keyword))
                .orderByAsc(Student::getStudentNo)
                .last("LIMIT " + lim));
        fillClassName(list);
        return list;
    }

    /** 学生基本信息 + 分数汇总一起返回，少一次请求 */
    public Student detail(Long id) {
        Student s = studentMapper.selectById(id);
        if (s == null || s.getStatus() == null || s.getStatus() != 1) {
            throw BizException.notFound("学生不存在");
        }
        // 数据范围校验：三级看不了别班的学生
        if (!ScopeContext.canSee(s.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限查看该学生");
        }
        fillClassName(List.of(s));
        return s;
    }

    public Long create(StudentDTO dto) {
        SysClass c = classMapper.selectById(dto.getClassId());
        if (c == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "班级不存在");
        }
        if (!Objects.equals(c.getStatus(), 1)) {
            throw new BizException(ErrorCode.BIZ_ERROR, "目标班级已停用");
        }
        Long dup = studentMapper.selectCount(Wrappers.<Student>lambdaQuery()
                .eq(Student::getClassId, dto.getClassId())
                .eq(Student::getStudentNo, dto.getStudentNo()));
        if (dup != null && dup > 0) {
            throw BizException.of("该班级下学号已存在：" + dto.getStudentNo());
        }
        Student s = new Student();
        s.setClassId(dto.getClassId());
        s.setStudentNo(dto.getStudentNo());
        s.setName(dto.getName());
        s.setBaseScore(dto.getBaseScore() == null ? new BigDecimal("50.00") : dto.getBaseScore());
        s.setStatus(1);
        studentMapper.insert(s);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), s.getClassId(),
                "STUDENT", s.getId(), "CREATE", null, snapshot(s));
        return s.getId();
    }

    /**
     * 编辑学生。允许改姓名、基础分、班级（转班）、状态；<b>学号不允许改</b>。
     *
     * <p>转班只改 {@code student.class_id}，历史 {@code score_item.class_id} 不变（R4），
     * 新录入的分数归新班。
     */
    public void update(Long id, StudentDTO dto) {
        Student exist = studentMapper.selectById(id);
        if (exist == null) {
            throw BizException.notFound("学生不存在");
        }
        if (!ScopeContext.canSee(exist.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限修改该学生");
        }

        Long targetClassId = dto.getClassId() == null ? exist.getClassId() : dto.getClassId();
        boolean transfer = !Objects.equals(targetClassId, exist.getClassId());
        if (transfer) {
            SysClass target = classMapper.selectById(targetClassId);
            if (target == null || !Objects.equals(target.getStatus(), 1)) {
                throw new BizException(ErrorCode.BIZ_ERROR, "目标班级不存在或已停用");
            }
            Long dup = studentMapper.selectCount(Wrappers.<Student>lambdaQuery()
                    .eq(Student::getClassId, targetClassId)
                    .eq(Student::getStudentNo, exist.getStudentNo()));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.BIZ_ERROR,
                        "目标班级下学号已存在：" + exist.getStudentNo());
            }
        }

        Student upd = new Student();
        upd.setId(id);
        upd.setName(dto.getName());
        upd.setBaseScore(dto.getBaseScore());
        upd.setClassId(targetClassId);
        upd.setStatus(dto.getStatus());
        studentMapper.updateById(upd);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), targetClassId,
                "STUDENT", id, transfer ? "TRANSFER" : "UPDATE",
                snapshot(exist), snapshot(studentMapper.selectById(id)));
    }

    /** 离班 / 复学：单独入口，便于审计和前端按钮区分 */
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是在册 / 离班");
        }
        Student exist = studentMapper.selectById(id);
        if (exist == null) {
            throw BizException.notFound("学生不存在");
        }
        if (!ScopeContext.canSee(exist.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该学生");
        }

        Student upd = new Student();
        upd.setId(id);
        upd.setStatus(status);
        studentMapper.updateById(upd);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), exist.getClassId(),
                "STUDENT", id, status == 1 ? "ENABLE" : "DISABLE",
                snapshot(exist), snapshot(studentMapper.selectById(id)));
    }

    private static Map<String, Object> snapshot(Student s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("classId", s.getClassId());
        m.put("studentNo", s.getStudentNo());
        m.put("name", s.getName());
        m.put("baseScore", s.getBaseScore());
        m.put("status", s.getStatus());
        return m;
    }

    private void fillClassName(List<Student> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> classIds = list.stream().map(Student::getClassId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        for (SysClass c : classMapper.selectBatchIds(classIds)) {
            names.put(c.getId(), c.getName());
        }
        for (Student s : list) {
            s.setClassName(names.get(s.getClassId()));
        }
    }

    /** 批量取学生，供积分录入时校验用 */
    public List<Student> listByIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? new ArrayList<>() : studentMapper.selectBatchIds(ids);
    }
}

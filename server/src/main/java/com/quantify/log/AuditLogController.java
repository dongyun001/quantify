package com.quantify.log;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantify.common.PageResult;
import com.quantify.common.R;
import com.quantify.org.ClassMapper;
import com.quantify.org.DeptMapper;
import com.quantify.org.SysClass;
import com.quantify.org.SysDept;
import com.quantify.student.Student;
import com.quantify.student.StudentMapper;
import com.quantify.user.SysUser;
import com.quantify.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 操作日志。仅一级可查 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;
    private final DeptMapper deptMapper;
    private final StudentMapper studentMapper;
    private final ClassMapper classMapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public R<PageResult<AuditLog>> page(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) Long operatorId,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String targetType) {
        var qw = Wrappers.<AuditLog>lambdaQuery();
        if (operatorId != null) {
            qw.eq(AuditLog::getOperatorId, operatorId);
        }
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action);
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq(AuditLog::getTargetType, targetType);
        }
        qw.orderByDesc(AuditLog::getId);

        var p = auditLogMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), qw);
        fillNames(p.getRecords());
        return R.ok(PageResult.of(p.getTotal(), page, size, p.getRecords()));
    }

    @GetMapping("/{id}")
    public R<AuditLog> detail(@PathVariable Long id) {
        AuditLog log = auditLogMapper.selectById(id);
        if (log == null) {
            throw com.quantify.common.BizException.notFound("日志不存在");
        }
        fillNames(List.of(log));
        return R.ok(log);
    }

    /**
     * 回填展示字段。
     *
     * <p>快照里只有 id（学生、操作人、部门），前端拿着 id 说不清"给谁加的分"。
     * 这里补上学生姓名 / 学号 / 班级，让日志页能直接读出人话。
     */
    private void fillNames(List<AuditLog> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, String> users = new HashMap<>();
        Map<Long, String> depts = new HashMap<>();
        Map<Long, Student> students = new HashMap<>();
        Map<Long, String> classes = new HashMap<>();

        for (AuditLog l : list) {
            if (l.getOperatorId() != null && !users.containsKey(l.getOperatorId())) {
                SysUser u = userMapper.selectById(l.getOperatorId());
                users.put(l.getOperatorId(), u == null ? "未知用户" : u.getRealName());
            }
            if (l.getDeptId() != null && !depts.containsKey(l.getDeptId())) {
                SysDept d = deptMapper.selectById(l.getDeptId());
                depts.put(l.getDeptId(), d == null ? null : d.getDeptName());
            }
        }

        for (AuditLog l : list) {
            l.setOperatorName(users.get(l.getOperatorId()));
            l.setDeptName(depts.get(l.getDeptId()));

            Long sid = studentIdOf(l);
            if (sid == null) {
                continue;
            }
            Student st = students.computeIfAbsent(sid, studentMapper::selectById);
            if (st == null) {
                continue;
            }
            l.setStudentName(st.getName());
            l.setStudentNo(st.getStudentNo());
            if (st.getClassId() != null) {
                String cn = classes.computeIfAbsent(st.getClassId(), cid -> {
                    SysClass c = classMapper.selectById(cid);
                    return c == null ? null : c.getName();
                });
                l.setClassName(cn);
            }
        }
    }

    /** 从 afterJson / beforeJson 里挖 studentId；挖不到返回 null（不是所有日志都有学生） */
    private Long studentIdOf(AuditLog l) {
        for (String raw : new String[]{l.getAfterJson(), l.getBeforeJson()}) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode n = objectMapper.readTree(raw);
                if (n != null && n.hasNonNull("studentId")) {
                    return n.get("studentId").asLong();
                }
            } catch (Exception ignore) {
                // 快照损坏就当没有，不影响日志展示
            }
        }
        return null;
    }
}

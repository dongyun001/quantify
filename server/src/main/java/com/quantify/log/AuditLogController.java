package com.quantify.log;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.common.PageResult;
import com.quantify.common.R;
import com.quantify.org.DeptMapper;
import com.quantify.org.SysDept;
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

    private void fillNames(List<AuditLog> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, String> users = new HashMap<>();
        Map<Long, String> depts = new HashMap<>();
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
        list.forEach(l -> {
            l.setOperatorName(users.get(l.getOperatorId()));
            l.setDeptName(depts.get(l.getDeptId()));
        });
    }
}

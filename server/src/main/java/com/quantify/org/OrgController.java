package com.quantify.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 元数据：班级、年级、部门下拉框用。所有登录用户都能调 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class OrgController {

    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final DeptMapper deptMapper;
    private final com.quantify.student.StudentMapper studentMapper;

    /** 班级列表。三级账号只会返回自己所在的班级 */
    @GetMapping("/classes")
    public R<List<Map<String, Object>>> classes(@RequestParam(required = false) Long gradeId) {
        List<Long> scope = ScopeContext.getClassIds();
        if (scope.isEmpty()) {
            return R.ok(List.of());
        }
        var qw = Wrappers.<SysClass>lambdaQuery().in(SysClass::getId, scope).eq(SysClass::getStatus, 1);
        if (gradeId != null) {
            qw.eq(SysClass::getGradeId, gradeId);
        }
        qw.orderByAsc(SysClass::getGradeId).orderByAsc(SysClass::getClassNo);

        List<Map<String, Object>> list = classMapper.selectList(qw).stream().map(c -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("gradeId", c.getGradeId());
            SysGrade g = gradeMapper.selectById(c.getGradeId());
            m.put("gradeName", g == null ? "" : g.getGradeName());
            m.put("studentCount", studentMapper.selectCount(Wrappers.<com.quantify.student.Student>lambdaQuery()
                    .eq(com.quantify.student.Student::getClassId, c.getId())
                    .eq(com.quantify.student.Student::getStatus, 1)));
            return m;
        }).toList();
        return R.ok(list);
    }

    @GetMapping("/grades")
    public R<List<SysGrade>> grades() {
        return R.ok(gradeMapper.selectList(Wrappers.<SysGrade>lambdaQuery()
                .eq(SysGrade::getStatus, 1).orderByDesc(SysGrade::getEnrollYear)));
    }

    /** 部门列表。纯标签，不参与权限判定 */
    @GetMapping("/depts")
    public R<List<SysDept>> depts() {
        return R.ok(deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                .eq(SysDept::getStatus, 1).orderByAsc(SysDept::getSortNo)));
    }
}

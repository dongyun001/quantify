package com.quantify.org;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.quantify.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 班级管理（仅 ADMIN）。
 *
 * <p>与只读的 {@link OrgController} 分开：那边是下拉用的元数据（仅启用班、按范围），
 * 这边是管理接口（含停用班）。真正的防线是这里的 {@code @SaCheckRole}。
 */
@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class ClassAdminController {

    private final ClassService classService;

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long gradeId,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(classService.list(gradeId, status, keyword));
    }

    @PostMapping
    public R<Long> create(@RequestBody ClassService.ClassDTO dto) {
        return R.ok(classService.create(dto));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody ClassService.ClassUpdateDTO dto) {
        classService.update(id, dto);
        return R.ok();
    }

    @PostMapping("/{id}/status")
    public R<Map<String, Object>> changeStatus(@PathVariable Long id,
                                               @RequestBody ClassService.StatusDTO dto) {
        return R.ok(classService.changeStatus(id, dto));
    }
}

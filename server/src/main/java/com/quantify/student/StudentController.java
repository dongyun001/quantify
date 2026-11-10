package com.quantify.student;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.quantify.common.PageResult;
import com.quantify.common.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    /**
     * 学生列表。
     * 三级账号调这个接口只会返回本班学生（服务端按登录身份过滤，不看前端传参）。
     */
    @GetMapping
    public R<PageResult<Student>> page(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long classId,
                                       @RequestParam(required = false) Long gradeId,
                                       @RequestParam(required = false) Integer status) {
        return R.ok(studentService.page(page, size, keyword, classId, gradeId, status));
    }

    /** 录入时选人用的模糊搜索。一级二级都能用 */
    @GetMapping("/search")
    @SaCheckRole(value = {"ADMIN", "EDITOR"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<List<Student>> search(@RequestParam String keyword,
                                   @RequestParam(required = false) Integer limit) {
        return R.ok(studentService.search(keyword, limit));
    }

    @GetMapping("/{id}")
    public R<Student> detail(@PathVariable Long id) {
        return R.ok(studentService.detail(id));
    }

    @PostMapping
    @SaCheckRole("ADMIN")
    public R<Long> create(@RequestBody @Valid StudentDTO dto) {
        return R.ok(studentService.create(dto));
    }

    @PutMapping("/{id}")
    @SaCheckRole("ADMIN")
    public R<Void> update(@PathVariable Long id, @RequestBody StudentDTO dto) {
        studentService.update(id, dto);
        return R.ok();
    }

    /** 离班 / 复学。单独接口便于审计和前端按钮区分 */
    @PostMapping("/{id}/status")
    @SaCheckRole("ADMIN")
    public R<Void> changeStatus(@PathVariable Long id,
                                @RequestBody java.util.Map<String, Integer> body) {
        studentService.changeStatus(id, body == null ? null : body.get("status"));
        return R.ok();
    }
}

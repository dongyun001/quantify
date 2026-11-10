package com.quantify.user;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.quantify.common.PageResult;
import com.quantify.common.R;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号管理。仅一级（ADMIN）可调。
 *
 * <p>账号数量不预设，管理员按需创建，每次自由组合「角色 + 数据范围」。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class UserController {

    private final UserService userService;

    @GetMapping
    public R<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String role,
                                      @RequestParam(required = false) Integer scopeType,
                                      @RequestParam(required = false) Integer status) {
        return R.ok(userService.page(page, size, keyword, role, scopeType, status));
    }

    /** 创建账号。返回的 initialPassword 只此一次，前端务必弹窗提示保存 */
    @PostMapping
    public R<CreateUserVO> create(@RequestBody @Valid UserDTO dto) {
        return R.ok(userService.create(dto));
    }

    /** 编辑账号。用户名不可改 */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody UserDTO dto) {
        userService.update(id, dto);
        return R.ok();
    }

    /** 重置密码。会强制对方下线并下次登录改密 */
    @PostMapping("/{id}/reset-password")
    public R<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                @RequestBody(required = false) ResetPwdDTO dto) {
        return R.ok(userService.resetPassword(id, dto == null ? null : dto.getNewPassword()));
    }

    /** 启用 / 禁用。不能禁用当前登录的账号 */
    @PostMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestBody StatusDTO dto) {
        userService.changeStatus(id, dto.getStatus());
        return R.ok();
    }

    @Data
    public static class ResetPwdDTO {
        private String newPassword;
    }

    @Data
    public static class StatusDTO {
        private Integer status;
    }
}

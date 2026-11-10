package com.quantify.score;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.quantify.common.R;
import com.quantify.notify.AppealNotifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 量化申诉。
 *
 * <p>角色控制在注解上：提交 / 我的 / 撤回是三级，待处理 / 受理 / 驳回 / 重发是一级。
 * 详情接口对「三级本人」和「一级」都开放，归属和数据范围由 service 再校验一次。
 */
@RestController
@RequestMapping("/api/appeals")
@RequiredArgsConstructor
public class AppealController {

    private final AppealService appealService;
    private final AppealNotifyService notifyService;

    @PostMapping
    @SaCheckRole("VIEWER")
    public R<Map<String, Object>> create(@RequestBody @Valid AppealService.CreateDTO dto) {
        return R.ok(appealService.create(dto));
    }

    @GetMapping("/mine")
    @SaCheckRole("VIEWER")
    public R<Map<String, Object>> mine(@RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(appealService.mine(status, page, size));
    }

    @GetMapping("/pending")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> pending(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(appealService.pending(page, size));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(appealService.detail(id));
    }

    @PostMapping("/{id}/accept")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> accept(@PathVariable Long id,
                                         @RequestBody @Valid AppealService.AcceptDTO dto) {
        return R.ok(appealService.accept(id, dto));
    }

    @PostMapping("/{id}/reject")
    @SaCheckRole("ADMIN")
    public R<Void> reject(@PathVariable Long id,
                          @RequestBody @Valid AppealService.RejectDTO dto) {
        appealService.reject(id, dto.getReviewRemark());
        return R.ok();
    }

    @PostMapping("/{id}/withdraw")
    @SaCheckRole("VIEWER")
    public R<Void> withdraw(@PathVariable Long id) {
        appealService.withdraw(id);
        return R.ok();
    }

    @PostMapping("/{id}/notify-retry")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> notifyRetry(@PathVariable Long id) {
        return R.ok(notifyService.retry(id, com.quantify.auth.ScopeContext.getUserId()));
    }
}

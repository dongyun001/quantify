package com.quantify.score;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.quantify.common.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 积分录入与申请。一级和二级都能用，三级无权访问 */
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
@SaCheckRole(value = {"ADMIN", "EDITOR"}, mode = SaMode.OR)
public class ScoreItemController {

    private final ScoreItemService scoreItemService;
    private final ScoreAdminService scoreAdminService;

    /** 新增流水。一级可传 effectNow=true 直接生效 */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody @Valid ScoreItemDTO dto) {
        return R.ok(scoreItemService.create(dto));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid ScoreItemDTO dto) {
        scoreItemService.update(id, dto);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    public R<Void> submit(@PathVariable Long id) {
        scoreItemService.submit(id);
        return R.ok();
    }

    @PostMapping("/{id}/revoke")
    public R<Void> revoke(@PathVariable Long id) {
        scoreItemService.revoke(id);
        return R.ok();
    }

    /** 对已通过的记录发起变更 */
    @PostMapping("/{id}/change")
    public R<Map<String, Object>> change(@PathVariable Long id, @RequestBody @Valid ChangeDTO dto) {
        return R.ok(scoreItemService.change(id, dto));
    }

    /** 我的申请 */
    @GetMapping("/mine")
    public R<Map<String, Object>> mine(@RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(scoreItemService.mine(status, page, size));
    }

    // ==================================================================
    //  一级管理动作（R28 的三个受控接口，没有通用「编辑他人记录」入口）
    // ==================================================================

    /** 打回重做：待审 → 已驳回，理由必填 */
    @PostMapping("/{id}/return")
    @SaCheckRole("ADMIN")
    public R<Void> returnForRedo(@PathVariable Long id, @RequestBody @Valid ReasonDTO dto) {
        scoreAdminService.returnForRedo(id, dto.getReason());
        return R.ok();
    }

    /** 管理员撤回：待审 → 已撤销，理由可选 */
    @PostMapping("/{id}/admin-revoke")
    @SaCheckRole("ADMIN")
    public R<Void> adminRevoke(@PathVariable Long id,
                               @RequestBody(required = false) OptionalReasonDTO dto) {
        scoreAdminService.adminRevoke(id, dto == null ? null : dto.getReason());
        return R.ok();
    }

    /** 管理员变更：已通过记录，走变更链，不直接 UPDATE 原记录 */
    @PostMapping("/{id}/admin-change")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> adminChange(
            @PathVariable Long id,
            @RequestBody @Valid ScoreAdminService.AdminChangeDTO dto) {
        return R.ok(scoreAdminService.adminChange(id, dto));
    }

    @Data
    public static class ReasonDTO {

        @NotBlank(message = "请填写理由")
        @Size(max = 500, message = "理由不能超过 500 字")
        private String reason;
    }

    @Data
    public static class OptionalReasonDTO {

        @Size(max = 500, message = "理由不能超过 500 字")
        private String reason;
    }
}

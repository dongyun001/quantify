package com.quantify.score;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.quantify.common.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 审批。仅一级（ADMIN）可调 */
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
public class ReviewController {

    private final ScoreReviewService reviewService;

    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam(required = false) Long deptId,
                                          @RequestParam(required = false) Long classId,
                                          @RequestParam(required = false) Integer category,
                                          @RequestParam(required = false) Integer dimension,
                                          @RequestParam(required = false) Boolean changeOnly,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(reviewService.pending(deptId, classId, category, dimension, changeOnly, page, size));
    }

    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id) {
        reviewService.approve(id);
        return R.ok();
    }

    @PostMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestBody RejectDTO dto) {
        reviewService.reject(id, dto.getRejectReason());
        return R.ok();
    }

    @Data
    public static class RejectDTO {
        private String rejectReason;
    }
}

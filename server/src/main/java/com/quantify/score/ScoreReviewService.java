package com.quantify.score;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ScoreStatus;
import com.quantify.log.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreReviewService {

    private final ScoreItemMapper scoreItemMapper;
    private final AuditLogService auditLogService;

    public Map<String, Object> pending(Long deptId, Long classId, Integer category,
                                        Integer dimension, Boolean changeOnly,
                                        long page, long size) {
        List<Long> scope = ScopeContext.getClassIds();
        if (scope.isEmpty()) {
            return emptyPage(page, size);
        }
        // 前端传的 classId 必须在范围内才采纳
        Long effective = (classId != null && scope.contains(classId)) ? classId : null;

        long offset = Math.max(0, (page - 1) * size);
        List<ScoreItem> list = scoreItemMapper.selectPending(
                deptId, effective, category, dimension, changeOnly, offset, size);
        long total = scoreItemMapper.countPending(deptId, effective, category, dimension, changeOnly);

        Long me = ScopeContext.getUserId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScoreItem i : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", i.getId());
            m.put("studentId", i.getStudentId());
            m.put("studentNo", i.getStudentNo());
            m.put("studentName", i.getStudentName());
            m.put("classId", i.getClassId());
            m.put("className", i.getClassName());
            m.put("category", i.getCategory());
            m.put("categoryText", ScoreItemService.categoryText(i.getCategory()));
            m.put("dimension", i.getDimension());
            m.put("dimensionText", ScoreItemService.dimensionText(i.getDimension()));
            m.put("signedPoints", ScoreItemService.signedPoints(i));
            m.put("reason", i.getReason());
            m.put("occurredOn", i.getOccurredOn());
            m.put("deptId", i.getDeptId());
            m.put("deptName", i.getDeptName());
            m.put("submitterName", i.getSubmitterName());
            m.put("submittedAt", i.getSubmittedAt());
            m.put("changeType", i.getChangeType());
            m.put("changeTypeText", changeTypeText(i.getChangeType()));
            m.put("originId", i.getOriginId());
            m.put("evidenceUrl", i.getEvidenceUrl());
            // 自己提交的不能自己审——按钮置灰，后端也会再拦一次
            m.put("canReview", !Objects.equals(i.getSubmittedBy(), me));
            rows.add(m);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("page", page);
        r.put("size", size);
        r.put("list", rows);
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id) {
        ScoreItem item = mustBePendingAndNotMine(id);

        // ★ 状态条件更新：靠数据库保证原子性。
        //   两个审核员同时点"通过"时，第二个人的 rows 会是 0，不会重复写日志。
        int rows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", id)
                .eq("status", ScoreStatus.PENDING)
                .set("status", ScoreStatus.APPROVED)
                .set("reviewed_by", ScopeContext.getUserId())
                .set("reviewed_at", LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of("该申请已被处理，请刷新后重试");
        }

        // 变更申请通过时，把原记录置为已失效——必须和上面在同一个事务里
        if (item.getChangeType() != null && item.getChangeType() != 0 && item.getOriginId() != null) {
            scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                    .eq("id", item.getOriginId())
                    .eq("status", ScoreStatus.APPROVED)
                    .set("status", ScoreStatus.SUPERSEDED)
                    .set("superseded_by", id));
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "APPROVE",
                Map.of("status", ScoreStatus.PENDING),
                Map.of("status", ScoreStatus.APPROVED,
                        "reviewedBy", ScopeContext.getUserId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of("驳回理由不能为空");
        }
        ScoreItem item = mustBePendingAndNotMine(id);

        int rows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", id)
                .eq("status", ScoreStatus.PENDING)
                .set("status", ScoreStatus.REJECTED)
                .set("reject_reason", reason)
                .set("reviewed_by", ScopeContext.getUserId())
                .set("reviewed_at", LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of("该申请已被处理，请刷新后重试");
        }

        // 驳回变更申请时，原记录不受任何影响，总分不变
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "REJECT",
                Map.of("status", ScoreStatus.PENDING),
                Map.of("status", ScoreStatus.REJECTED, "rejectReason", reason));
    }

    /**
     * 校验：记录存在、在数据范围内、状态是待审、<b>且不是自己提交的</b>。
     *
     * <p>最后一条是硬规则——一级自己也能录入，如果允许自审，
     * 就等于"左手交右手"，审批流形同虚设。
     */
    private ScoreItem mustBePendingAndNotMine(Long id) {
        ScoreItem item = scoreItemMapper.selectById(id);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw BizException.notFound("申请不存在");
        }
        if (!ScopeContext.canSee(item.getClassId())) {
            throw new BizException(com.quantify.common.ErrorCode.FORBIDDEN, "无权限审核该申请");
        }
        if (!Objects.equals(item.getStatus(), ScoreStatus.PENDING)) {
            throw BizException.of("该申请已被处理，请刷新后重试");
        }
        if (Objects.equals(item.getSubmittedBy(), ScopeContext.getUserId())) {
            throw BizException.of("不能审批自己提交的申请");
        }
        return item;
    }

    private String changeTypeText(Integer t) {
        if (t == null) {
            return null;
        }
        return switch (t) {
            case 1 -> "修改";
            case 2 -> "删除";
            default -> null;
        };
    }

    private Map<String, Object> emptyPage(long page, long size) {
        Map<String, Object> r = new HashMap<>();
        r.put("total", 0L);
        r.put("page", page);
        r.put("size", size);
        r.put("list", List.of());
        return r;
    }
}

package com.quantify.score;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.ScoreStatus;
import com.quantify.log.AuditLogService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一级对他人记录的三个受控动作（AGENTS.md R28）。
 *
 * <p>除这三个方法外，不存在任何「编辑他人记录」的入口：
 * <ul>
 *   <li>打回重做：待审 → 已驳回，让提交人自己改</li>
 *   <li>管理员撤回：待审 → 已撤销</li>
 *   <li>管理员变更：已通过 → 变更链（不能 UPDATE 原记录）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScoreAdminService {

    private final ScoreItemMapper scoreItemMapper;
    private final AuditLogService auditLogService;

    @Transactional(rollbackFor = Exception.class)
    public void returnForRedo(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写打回理由");
        }
        if (reason.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "打回理由不能超过 500 字");
        }
        ScoreItem item = requireInScope(id);
        if (!Objects.equals(item.getStatus(), ScoreStatus.PENDING)) {
            throw BizException.of("只有待审的申请才能打回");
        }
        if (Objects.equals(item.getSubmittedBy(), ScopeContext.getUserId())) {
            throw BizException.of("不能打回自己提交的申请");
        }

        int rows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", id)
                .eq("status", ScoreStatus.PENDING)
                .set("status", ScoreStatus.REJECTED)
                .set("reject_reason", reason.trim())
                .set("reviewed_by", ScopeContext.getUserId())
                .set("reviewed_at", LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of("该申请已被处理，请刷新后重试");
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "RETURN",
                Map.of("status", ScoreStatus.PENDING),
                Map.of("status", ScoreStatus.REJECTED, "rejectReason", reason.trim()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void adminRevoke(Long id, String reason) {
        if (reason != null && reason.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "撤回原因不能超过 500 字");
        }
        ScoreItem item = requireInScope(id);
        if (!Objects.equals(item.getStatus(), ScoreStatus.PENDING)) {
            throw BizException.of("只有待审的申请才能撤回");
        }

        int rows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", id)
                .eq("status", ScoreStatus.PENDING)
                .set("status", ScoreStatus.REVOKED));
        if (rows == 0) {
            throw BizException.of("该申请已被处理，请刷新后重试");
        }

        Map<String, Object> after = new HashMap<>();
        after.put("status", ScoreStatus.REVOKED);
        after.put("reason", reason == null ? null : reason.trim());
        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "ADMIN_REVOKE",
                Map.of("status", ScoreStatus.PENDING), after);
    }

    /**
     * 修正一条已通过的记录。
     *
     * <p>和二级的「变更申请」走同一个方向，区别只在于：一级不需要审批，
     * 新记录直接以 {@code status=2 且 reviewed_by=NULL} 生效（R15）。
     * 原记录仍然必须置为已失效并指向新记录——变更链不能断（R12/R14）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminChange(Long id, AdminChangeDTO dto) {
        if (dto.getChangeReason() == null || dto.getChangeReason().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写变更依据");
        }
        if (dto.getChangeReason().length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "变更依据不能超过 500 字");
        }
        ScoreItem origin = requireInScope(id);
        if (!Objects.equals(origin.getStatus(), ScoreStatus.APPROVED)) {
            throw BizException.of("只有「已通过」的记录才能修正");
        }
        validateChange(dto);

        ScoreItem nw = new ScoreItem();
        nw.setClassId(origin.getClassId());
        nw.setDeptId(ScopeContext.getDeptId());
        nw.setStudentId(origin.getStudentId());
        nw.setTerm(origin.getTerm());
        nw.setOriginId(origin.getId());
        nw.setChangeType(dto.getChangeType());
        nw.setEvidenceUrl(origin.getEvidenceUrl());
        nw.setCreatedBy(ScopeContext.getUserId());
        nw.setStatus(ScoreStatus.APPROVED);

        if (Objects.equals(dto.getChangeType(), 2)) {
            nw.setCategory(origin.getCategory());
            nw.setDimension(origin.getDimension());
            nw.setPoints(BigDecimal.ZERO);
            nw.setReason(origin.getReason());
            nw.setOccurredOn(origin.getOccurredOn());
        } else {
            nw.setCategory(dto.getCategory());
            nw.setDimension(dto.getDimension());
            nw.setPoints(dto.getPoints());
            nw.setReason(dto.getReason() == null || dto.getReason().isBlank()
                    ? origin.getReason() : dto.getReason().trim());
            nw.setOccurredOn(dto.getOccurredOn() == null ? origin.getOccurredOn() : dto.getOccurredOn());
        }
        scoreItemMapper.insert(nw);

        int rows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", origin.getId())
                .eq("status", ScoreStatus.APPROVED)
                .set("status", ScoreStatus.SUPERSEDED)
                .set("superseded_by", nw.getId()));
        if (rows == 0) {
            throw BizException.of("原记录已变化，请刷新后重试");
        }

        Long me = ScopeContext.getUserId();
        Long deptId = ScopeContext.getDeptId();
        auditLogService.record(me, deptId, origin.getClassId(),
                "SCORE_ITEM", origin.getId(), "ADMIN_CHANGE",
                Map.of("status", ScoreStatus.APPROVED, "changeReason", dto.getChangeReason().trim()),
                Map.of("status", ScoreStatus.SUPERSEDED, "newId", nw.getId()));
        auditLogService.record(me, deptId, origin.getClassId(),
                "SCORE_ITEM", nw.getId(), "CHANGE",
                ScoreItemService.snapshot(origin), ScoreItemService.snapshot(nw));

        Map<String, Object> r = new HashMap<>();
        r.put("newId", nw.getId());
        r.put("originId", origin.getId());
        r.put("status", nw.getStatus());
        r.put("statusText", ScoreStatus.text(nw.getStatus()));
        return r;
    }

    private ScoreItem requireInScope(Long id) {
        ScoreItem item = scoreItemMapper.selectById(id);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw BizException.notFound("记录不存在");
        }
        if (!ScopeContext.canSee(item.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该记录");
        }
        return item;
    }

    private void validateChange(AdminChangeDTO dto) {
        Integer type = dto.getChangeType();
        if (type == null || (type != 1 && type != 2)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "变更类型只能是 1 修改 / 2 删除");
        }
        if (type == 1) {
            if (dto.getCategory() == null || dto.getCategory() < 1 || dto.getCategory() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择修正后的类型（加分 / 扣分）");
            }
            if (dto.getDimension() == null || dto.getDimension() < 1 || dto.getDimension() > 5) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择类别（德/智/体/美/劳）");
            }
            if (dto.getPoints() == null || dto.getPoints().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "修正后的分值必须为正数");
            }
            if (dto.getPoints().scale() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分值最多 2 位小数");
            }
            if (dto.getReason() != null && dto.getReason().length() > 500) {
                throw new BizException(ErrorCode.BAD_REQUEST, "事由不能超过 500 字");
            }
        }
    }

    @Data
    public static class AdminChangeDTO {

        @NotNull(message = "请选择变更类型")
        private Integer changeType;

        /** 五育类别：1德 2智 3体 4美 5劳；修改时必填 */
        @Min(value = 1, message = "请选择类别（德/智/体/美/劳）")
        @Max(value = 5, message = "请选择类别（德/智/体/美/劳）")
        private Integer dimension;

        /** 1 加分 / 2 扣分（职位分已取消） */
        @Min(value = 1, message = "类型只能是加分 / 扣分")
        @Max(value = 2, message = "类型只能是加分 / 扣分")
        private Integer category;
        private BigDecimal points;
        private String reason;
        private LocalDate occurredOn;

        @Size(max = 500, message = "变更依据不能超过 500 字")
        private String changeReason;
    }
}

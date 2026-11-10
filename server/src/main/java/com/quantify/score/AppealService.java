package com.quantify.score;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.AppealStatus;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.Role;
import com.quantify.common.ScoreStatus;
import com.quantify.log.AuditLogService;
import com.quantify.notify.AppealNotifyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 量化申诉。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>只有三级（VIEWER）能申诉，且只能对「已通过」的记录</li>
 *   <li>受理必须走变更链：新建变更记录 + 原记录置为已失效，同一事务（R12/R14）</li>
 *   <li>受理 / 驳回 / 撤回都用状态条件更新，防止两个人同时处理同一条（R17）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ScoreAppealMapper appealMapper;
    private final ScoreItemMapper scoreItemMapper;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ==================================================================
    //  提交 / 查询
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(CreateDTO dto) {
        ScoreItem item = scoreItemMapper.selectById(dto.getScoreItemId());
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw BizException.notFound("被申诉的记录不存在");
        }
        if (!ScopeContext.canSee(item.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限对该记录申诉");
        }
        if (!Objects.equals(item.getStatus(), ScoreStatus.APPROVED)) {
            throw BizException.of("只能对已通过的记录申诉");
        }

        Long me = ScopeContext.getUserId();
        // 先查一次给出友好提示；并发时由 uk_active_appeal 兜底
        Long pending = appealMapper.selectCount(Wrappers.<ScoreAppeal>lambdaQuery()
                .eq(ScoreAppeal::getScoreItemId, item.getId())
                .eq(ScoreAppeal::getCreatedBy, me)
                .eq(ScoreAppeal::getStatus, AppealStatus.PENDING));
        if (pending != null && pending > 0) {
            throw BizException.of("该记录已有一条待处理申诉，请勿重复提交");
        }

        ScoreAppeal a = new ScoreAppeal();
        a.setScoreItemId(item.getId());
        a.setStudentId(item.getStudentId());
        a.setClassId(item.getClassId());
        a.setTerm(item.getTerm());
        a.setAppealType(dto.getAppealType());
        a.setAppealReason(dto.getAppealReason().trim());
        a.setEvidenceUrl(blankToNull(dto.getEvidenceUrl()));
        a.setStatus(AppealStatus.PENDING);
        a.setNotifyStatus(0);
        a.setCreatedBy(me);
        try {
            appealMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw BizException.of("该记录已有一条待处理申诉，请勿重复提交");
        }

        auditLogService.record(me, ScopeContext.getDeptId(), a.getClassId(),
                "SCORE_APPEAL", a.getId(), "APPEAL_CREATE", null, snapshot(a));

        // 事务提交后再发钉钉；失败不影响申诉本身
        eventPublisher.publishEvent(new AppealNotifyService.CreatedEvent(a.getId(), me));

        Map<String, Object> r = new HashMap<>();
        r.put("id", a.getId());
        r.put("status", a.getStatus());
        r.put("statusText", AppealStatus.text(a.getStatus()));
        return r;
    }

    public Map<String, Object> mine(Integer status, long page, long size) {
        long p = Math.max(1, page);
        long s = normalizeSize(size);
        long offset = (p - 1) * s;

        List<ScoreAppeal> list = appealMapper.selectMine(
                ScopeContext.getUserId(), status, offset, s);
        long total = appealMapper.countMine(ScopeContext.getUserId(), status);
        return page(total, p, s, list);
    }

    public Map<String, Object> pending(long page, long size) {
        List<Long> scope = ScopeContext.getClassIds();
        long p = Math.max(1, page);
        long s = normalizeSize(size);
        if (scope.isEmpty()) {
            return page(0, p, s, List.of());
        }
        long offset = (p - 1) * s;
        List<ScoreAppeal> list = appealMapper.selectPending(scope, offset, s);
        long total = appealMapper.countPending(scope);
        return page(total, p, s, list);
    }

    public Map<String, Object> detail(Long id) {
        return toRow(requireVisible(id));
    }

    // ==================================================================
    //  受理 / 驳回 / 撤回
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accept(Long id, AcceptDTO dto) {
        ScoreAppeal a = requireVisible(id);
        if (!Objects.equals(a.getStatus(), AppealStatus.PENDING)) {
            throw BizException.of("该申诉已被处理，请刷新后重试");
        }

        ScoreItem origin = scoreItemMapper.selectById(a.getScoreItemId());
        if (origin == null || (origin.getDeleted() != null && origin.getDeleted() == 1)) {
            throw BizException.of("原记录不存在，无法受理");
        }
        if (!Objects.equals(origin.getStatus(), ScoreStatus.APPROVED)) {
            throw BizException.of("原记录已变化，无法受理");
        }

        validateChange(dto);
        Long me = ScopeContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        // ---- 1. 新建变更记录（status=2 且 reviewed_by=NULL，按 R15 视为直接生效） ----
        ScoreItem nw = new ScoreItem();
        nw.setClassId(origin.getClassId());
        nw.setDeptId(ScopeContext.getDeptId());
        nw.setStudentId(origin.getStudentId());
        nw.setTerm(origin.getTerm());
        nw.setOriginId(origin.getId());
        nw.setChangeType(dto.getChangeType());
        nw.setEvidenceUrl(origin.getEvidenceUrl());
        nw.setCreatedBy(me);
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
            nw.setReason(blankToNull(dto.getReason()) == null ? origin.getReason() : dto.getReason().trim());
            nw.setOccurredOn(dto.getOccurredOn() == null ? origin.getOccurredOn() : dto.getOccurredOn());
        }
        scoreItemMapper.insert(nw);

        // ---- 2. 原记录置为已失效（带 status=2 条件，防止别处已经变更过） ----
        int originRows = scoreItemMapper.update(null, new UpdateWrapper<ScoreItem>()
                .eq("id", origin.getId())
                .eq("status", ScoreStatus.APPROVED)
                .set("status", ScoreStatus.SUPERSEDED)
                .set("superseded_by", nw.getId()));
        if (originRows == 0) {
            throw BizException.of("原记录已变化，无法受理");
        }

        // ---- 3. 申诉置为已受理（带 status=0 条件） ----
        int appealRows = appealMapper.update(null, new UpdateWrapper<ScoreAppeal>()
                .eq("id", id)
                .eq("status", AppealStatus.PENDING)
                .set("status", AppealStatus.ACCEPTED)
                .set("result_change_id", nw.getId())
                .set("reviewed_by", me)
                .set("reviewed_at", now)
                .set("review_remark", blankToNull(dto.getReviewRemark())));
        if (appealRows == 0) {
            throw BizException.of("该申诉已被处理，请刷新后重试");
        }

        auditLogService.record(me, ScopeContext.getDeptId(), a.getClassId(),
                "SCORE_APPEAL", id, "APPEAL_ACCEPT",
                Map.of("status", AppealStatus.PENDING),
                Map.of("status", AppealStatus.ACCEPTED, "resultChangeId", nw.getId()));
        auditLogService.record(me, ScopeContext.getDeptId(), origin.getClassId(),
                "SCORE_ITEM", nw.getId(), "CHANGE",
                ScoreItemService.snapshot(origin), ScoreItemService.snapshot(nw));

        eventPublisher.publishEvent(new AppealNotifyService.ReviewedEvent(id, me));

        Map<String, Object> r = new HashMap<>();
        r.put("appealId", id);
        r.put("status", AppealStatus.ACCEPTED);
        r.put("statusText", AppealStatus.text(AppealStatus.ACCEPTED));
        r.put("newId", nw.getId());
        r.put("originId", origin.getId());
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reviewRemark) {
        if (reviewRemark == null || reviewRemark.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写驳回理由");
        }
        if (reviewRemark.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "驳回理由不能超过 500 字");
        }
        ScoreAppeal a = requireVisible(id);

        int rows = appealMapper.update(null, new UpdateWrapper<ScoreAppeal>()
                .eq("id", id)
                .eq("status", AppealStatus.PENDING)
                .set("status", AppealStatus.REJECTED)
                .set("review_remark", reviewRemark.trim())
                .set("reviewed_by", ScopeContext.getUserId())
                .set("reviewed_at", LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of("该申诉已被处理，请刷新后重试");
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), a.getClassId(),
                "SCORE_APPEAL", id, "APPEAL_REJECT",
                Map.of("status", AppealStatus.PENDING),
                Map.of("status", AppealStatus.REJECTED, "reviewRemark", reviewRemark.trim()));

        eventPublisher.publishEvent(new AppealNotifyService.ReviewedEvent(id, ScopeContext.getUserId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id) {
        ScoreAppeal a = requireVisible(id);
        if (!Objects.equals(a.getCreatedBy(), ScopeContext.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能撤回自己提交的申诉");
        }

        int rows = appealMapper.update(null, new UpdateWrapper<ScoreAppeal>()
                .eq("id", id)
                .eq("status", AppealStatus.PENDING)
                .eq("created_by", ScopeContext.getUserId())
                .set("status", AppealStatus.WITHDRAWN));
        if (rows == 0) {
            throw BizException.of("该申诉已被处理，请刷新后重试");
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), a.getClassId(),
                "SCORE_APPEAL", id, "APPEAL_WITHDRAW",
                Map.of("status", AppealStatus.PENDING),
                Map.of("status", AppealStatus.WITHDRAWN));
    }

    // ==================================================================
    //  工具方法
    // ==================================================================

    /** 详情 + 范围 + 归属校验。ADMIN 受范围约束，VIEWER 只能看自己的。 */
    ScoreAppeal requireVisible(Long id) {
        ScoreAppeal a = appealMapper.selectDetail(id);
        if (a == null) {
            throw BizException.notFound("申诉不存在");
        }
        if (!ScopeContext.canSee(a.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该申诉");
        }
        if (!Role.isAdmin(ScopeContext.getRole())
                && !Objects.equals(a.getCreatedBy(), ScopeContext.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能查看自己的申诉");
        }
        return a;
    }

    private void validateChange(AcceptDTO dto) {
        Integer type = dto.getChangeType();
        if (type == null || (type != 1 && type != 2)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "更正类型只能是 1 修改 / 2 删除");
        }
        if (type == 1) {
            if (dto.getCategory() == null || dto.getCategory() < 1 || dto.getCategory() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择更正后的类型（加分 / 扣分）");
            }
            if (dto.getDimension() == null || dto.getDimension() < 1 || dto.getDimension() > 5) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择类别（德/智/体/美/劳）");
            }
            if (dto.getPoints() == null || dto.getPoints().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "更正后的分值必须为正数");
            }
            if (dto.getPoints().scale() > 2) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分值最多 2 位小数");
            }
            if (dto.getReason() != null && dto.getReason().length() > 500) {
                throw new BizException(ErrorCode.BAD_REQUEST, "事由不能超过 500 字");
            }
        }
    }

    private Map<String, Object> page(long total, long p, long s, List<ScoreAppeal> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScoreAppeal a : list) {
            rows.add(toRow(a));
        }
        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("page", p);
        r.put("size", s);
        r.put("list", rows);
        return r;
    }

    private Map<String, Object> toRow(ScoreAppeal a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("scoreItemId", a.getScoreItemId());
        m.put("studentId", a.getStudentId());
        m.put("studentNo", a.getStudentNo());
        m.put("studentName", a.getStudentName());
        m.put("classId", a.getClassId());
        m.put("className", a.getClassName());
        m.put("term", a.getTerm());
        m.put("appealType", a.getAppealType());
        m.put("appealTypeText", appealTypeText(a.getAppealType()));
        m.put("appealReason", a.getAppealReason());
        m.put("evidenceUrl", a.getEvidenceUrl());
        m.put("status", a.getStatus());
        m.put("statusText", AppealStatus.text(a.getStatus()));
        m.put("resultChangeId", a.getResultChangeId());
        m.put("notifyStatus", a.getNotifyStatus());
        m.put("notifyStatusText", notifyStatusText(a.getNotifyStatus()));
        m.put("notifyError", a.getNotifyError());
        m.put("reviewedBy", a.getReviewedBy());
        m.put("reviewedByName", a.getReviewedByName());
        m.put("reviewedAt", a.getReviewedAt());
        m.put("reviewRemark", a.getReviewRemark());
        m.put("createdBy", a.getCreatedBy());
        m.put("createdByName", a.getCreatedByName());
        m.put("createdAt", a.getCreatedAt());
        m.put("scoreCategory", a.getScoreCategory());
        m.put("scoreCategoryText", ScoreItemService.categoryText(a.getScoreCategory()));
        m.put("scoreDimension", a.getScoreDimension());
        m.put("scoreDimensionText", ScoreItemService.dimensionText(a.getScoreDimension()));
        m.put("scorePoints", a.getScorePoints());
        m.put("scoreSignedPoints", signedPoints(a.getScoreCategory(), a.getScorePoints()));
        m.put("scoreReason", a.getScoreReason());
        m.put("scoreOccurredOn", a.getScoreOccurredOn());
        m.put("scoreStatus", a.getScoreStatus());
        m.put("scoreStatusText", ScoreStatus.text(a.getScoreStatus()));
        return m;
    }

    private static BigDecimal signedPoints(Integer category, BigDecimal points) {
        if (points == null) {
            return BigDecimal.ZERO;
        }
        return category != null && category == 2 ? points.negate() : points;
    }

    static String appealTypeText(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case 1 -> "分数有误";
            case 2 -> "事由有误";
            case 3 -> "重复录入";
            default -> "其他";
        };
    }

    static String notifyStatusText(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case 1 -> "已发送";
            case 2 -> "发送失败";
            default -> "未发送";
        };
    }

    private static Map<String, Object> snapshot(ScoreAppeal a) {
        Map<String, Object> m = new HashMap<>();
        m.put("scoreItemId", a.getScoreItemId());
        m.put("studentId", a.getStudentId());
        m.put("classId", a.getClassId());
        m.put("term", a.getTerm());
        m.put("appealType", a.getAppealType());
        m.put("appealReason", a.getAppealReason());
        m.put("evidenceUrl", a.getEvidenceUrl());
        m.put("status", a.getStatus());
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long normalizeSize(long size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    // ==================================================================
    //  请求体
    // ==================================================================

    @Data
    public static class CreateDTO {

        @NotNull(message = "请选择被申诉的记录")
        private Long scoreItemId;

        @NotNull(message = "请选择申诉类型")
        @Min(value = 1, message = "申诉类型不正确")
        @Max(value = 4, message = "申诉类型不正确")
        private Integer appealType;

        @NotBlank(message = "请填写申诉理由")
        @Size(max = 500, message = "申诉理由不能超过 500 字")
        private String appealReason;

        @Size(max = 500, message = "佐证链接不能超过 500 字")
        private String evidenceUrl;
    }

    @Data
    public static class AcceptDTO {

        @Size(max = 500, message = "处理意见不能超过 500 字")
        private String reviewRemark;

        @NotNull(message = "请选择更正类型")
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
        private java.time.LocalDate occurredOn;
    }

    @Data
    public static class RejectDTO {

        @NotBlank(message = "请填写驳回理由")
        @Size(max = 500, message = "驳回理由不能超过 500 字")
        private String reviewRemark;
    }
}

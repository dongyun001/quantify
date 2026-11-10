package com.quantify.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.common.Role;
import com.quantify.common.ScoreStatus;
import com.quantify.log.AuditLogService;
import com.quantify.setting.SysConfig;
import com.quantify.setting.SysConfigMapper;
import com.quantify.student.Student;
import com.quantify.student.StudentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 积分录入与审批的核心业务。
 *
 * <p>三条硬规则在这里落地：
 * <ol>
 *   <li>只能改自己提交的记录——一级也一样（{@link #getOwnItem}）</li>
 *   <li>已通过的记录谁都不能就地改，只能发起变更申请（{@link #change}）</li>
 *   <li>提交人不能审批自己提交的申请（{@link ScoreReviewService}）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreItemService {

    private final ScoreItemMapper scoreItemMapper;
    private final StudentMapper studentMapper;
    private final SysConfigMapper configMapper;
    private final AuditLogService auditLogService;
    private final ScoreQueryService scoreQueryService;

    // ==================================================================
    //  录入
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(ScoreItemDTO dto) {
        validateCategory(dto.getCategory());
        validateDimension(dto.getDimension());
        Student stu = requireWritableStudent(dto.getStudentId());
        String term = resolveTerm(dto.getTerm());

        // 一级选了"直接生效"就跳过审批；二级传什么都会被忽略
        boolean effectNow = resolveEffectNow(dto.getEffectNow());

        ScoreItem item = insertNew(dto, stu, term,
                effectNow ? ScoreStatus.APPROVED : ScoreStatus.DRAFT, false, null);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", item.getId(), "CREATE", null, snapshot(item));

        Map<String, Object> result = new HashMap<>();
        result.put("id", item.getId());
        result.put("status", item.getStatus());
        result.put("statusText", ScoreStatus.text(item.getStatus()));
        result.put("effectNow", effectNow);
        result.put("duplicateWarning", checkDuplicate(stu.getId(), term, dto));
        return result;
    }

    /**
     * 录入与批量导入共用的学生校验：存在、在册、在当前账号的数据范围内。
     *
     * <p>跨范围时抛 403 而不是返回空——静默跳过会让用户以为导进去了。
     */
    Student requireWritableStudent(Long studentId) {
        Student stu = studentMapper.selectById(studentId);
        if (stu == null || stu.getStatus() == null || stu.getStatus() != 1) {
            throw BizException.of("学生不存在或已离班");
        }
        if (!ScopeContext.canSee(stu.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限给该学生录分");
        }
        return stu;
    }

    /** 学期留空取当前学期。批量导入复用同一规则。 */
    String resolveTerm(String requested) {
        return (requested == null || requested.isBlank())
                ? scoreQueryService.currentTerm() : requested;
    }

    /**
     * 「直接生效」只在服务端判定：当前登录人是 ADMIN 且主动勾选。
     * 二级传 true 也会被忽略（AGENTS.md R16）。
     */
    boolean resolveEffectNow(Boolean requested) {
        return Role.isAdmin(ScopeContext.getRole()) && Boolean.TRUE.equals(requested);
    }

    /**
     * 落库一条流水，<b>不写操作日志</b>。
     *
     * <p>单条录入由 {@link #create} 写一条 CREATE 日志；批量导入由调用方写一条
     * BATCH_IMPORT 日志——2000 行不能写 2000 条日志把日志表冲爆。
     *
     * @param markSubmitted 是否记录提交人和提交时间（批量导入 submitNow 时用）
     * @param batchNo       批量导入批次号；单条录入传 null
     */
    ScoreItem insertNew(ScoreItemDTO dto, Student stu, String term, int status,
                        boolean markSubmitted, String batchNo) {
        ScoreItem item = new ScoreItem();
        item.setClassId(stu.getClassId());
        item.setDeptId(ScopeContext.getDeptId());
        item.setStudentId(stu.getId());
        item.setTerm(term);
        item.setCategory(dto.getCategory());
        item.setDimension(dto.getDimension());
        item.setPoints(dto.getPoints());
        item.setReason(dto.getReason());
        item.setOccurredOn(dto.getOccurredOn());
        item.setEvidenceUrl(dto.getEvidenceUrl());
        item.setChangeType(0);
        item.setCreatedBy(ScopeContext.getUserId());
        item.setBatchNo(batchNo);
        item.setStatus(status);
        if (markSubmitted) {
            item.setSubmittedBy(ScopeContext.getUserId());
            item.setSubmittedAt(LocalDateTime.now());
        }
        scoreItemMapper.insert(item);
        return item;
    }

    /**
     * 重复事由提示。
     * <b>只提示不阻断</b>——防止同一件事被录两次，但不替用户做判断，
     * 因为"同一个学生一个月内两次被子未叠"是完全可能的。
     */
    private String checkDuplicate(Long studentId, String term, ScoreItemDTO dto) {
        if (dto.getReason() == null || dto.getReason().isBlank()) {
            return null;
        }
        List<ScoreItem> dup = scoreItemMapper.selectList(Wrappers.<ScoreItem>lambdaQuery()
                .eq(ScoreItem::getStudentId, studentId)
                .eq(ScoreItem::getTerm, term)
                .eq(ScoreItem::getCategory, dto.getCategory())
                .eq(ScoreItem::getDimension, dto.getDimension())
                .eq(ScoreItem::getReason, dto.getReason())
                .in(ScoreItem::getStatus, ScoreStatus.PENDING, ScoreStatus.APPROVED)
                .last("LIMIT 1"));
        if (dup.isEmpty()) {
            return null;
        }
        return "该学生在同一学期已有一条相同事由的记录，请确认是否重复录入";
    }

    /**
     * 批量重复检查：与 {@link #checkDuplicate} 同一口径
     * （同学生 + 同学期 + 同类型 + 同事由，且状态为待审 / 已通过）。
     *
     * <p>一次查库返回计数，避免批量导入 2000 行时产生 N+1 查询。
     *
     * @return key 由 {@link #duplicateKey} 生成
     */
    Map<String, Integer> duplicateCounts(Collection<Long> studentIds, String term) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        List<ScoreItem> items = scoreItemMapper.selectList(Wrappers.<ScoreItem>lambdaQuery()
                .select(ScoreItem::getStudentId, ScoreItem::getCategory,
                        ScoreItem::getDimension, ScoreItem::getReason)
                .in(ScoreItem::getStudentId, studentIds)
                .eq(ScoreItem::getTerm, term)
                .in(ScoreItem::getStatus, ScoreStatus.PENDING, ScoreStatus.APPROVED));

        Map<String, Integer> counts = new HashMap<>();
        for (ScoreItem item : items) {
            counts.merge(duplicateKey(item.getStudentId(), item.getCategory(),
                            item.getDimension(), item.getReason()),
                    1, Integer::sum);
        }
        return counts;
    }

    /** 重复判断的 key：学生 + 方向 + 类别 + 事由（学期已由查询条件限定） */
    static String duplicateKey(Long studentId, Integer category, Integer dimension, String reason) {
        return studentId + "|" + category + "|" + dimension + "|"
                + (reason == null ? "" : reason.trim());
    }

    // ==================================================================
    //  编辑 / 提交 / 撤回
    // ==================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ScoreItemDTO dto) {
        validateCategory(dto.getCategory());
        validateDimension(dto.getDimension());
        ScoreItem item = getOwnItem(id);
        if (item.getStatus() != ScoreStatus.DRAFT && item.getStatus() != ScoreStatus.REJECTED) {
            throw BizException.of("当前状态不允许修改（已提交待审 / 已通过 / 已失效）");
        }
        ScoreItem before = snapshot(item);

        ScoreItem upd = new ScoreItem();
        upd.setId(id);
        upd.setCategory(dto.getCategory());
        upd.setDimension(dto.getDimension());
        upd.setPoints(dto.getPoints());
        upd.setReason(dto.getReason());
        upd.setOccurredOn(dto.getOccurredOn());
        upd.setEvidenceUrl(dto.getEvidenceUrl());
        scoreItemMapper.updateById(upd);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "UPDATE", before, snapshot(scoreItemMapper.selectById(id)));
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        ScoreItem item = getOwnItem(id);
        if (item.getStatus() != ScoreStatus.DRAFT && item.getStatus() != ScoreStatus.REJECTED) {
            throw BizException.of("只有草稿或已驳回的申请才能提交");
        }
        if (item.getReason() == null || item.getReason().isBlank()) {
            throw BizException.of("请填写事由后再提交");
        }

        ScoreItem upd = new ScoreItem();
        upd.setId(id);
        upd.setStatus(ScoreStatus.PENDING);
        upd.setSubmittedBy(ScopeContext.getUserId());
        upd.setSubmittedAt(LocalDateTime.now());
        upd.setRejectReason(null);
        scoreItemMapper.updateById(upd);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "SUBMIT", null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id) {
        ScoreItem item = getOwnItem(id);
        if (item.getStatus() != ScoreStatus.PENDING) {
            throw BizException.of("只有待审的申请才能撤回");
        }
        ScoreItem upd = new ScoreItem();
        upd.setId(id);
        upd.setStatus(ScoreStatus.REVOKED);
        scoreItemMapper.updateById(upd);

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), item.getClassId(),
                "SCORE_ITEM", id, "REVOKE", null, null);
    }

    // ==================================================================
    //  变更申请
    // ==================================================================

    /**
     * 对一条<b>已通过</b>的记录发起修改或删除。
     *
     * <p>原记录完全不动，新建一条待审记录指向它。一级发起时选"直接生效"，
     * 就在一个事务里把"原记录失效 + 新记录生效"一起做完，跳过待审中间态。
     * 无论哪种方式，<b>变更链都完整保留</b>——三个月后有人问
     * "这条为什么从 2 分变成 5 分"，顺着 originId 就能查出来。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> change(Long originId, ChangeDTO dto) {
        ScoreItem origin = getOwnItem(originId);
        if (origin.getStatus() != ScoreStatus.APPROVED) {
            throw BizException.of("只有「已通过」的记录才能发起变更");
        }
        // 防止同一条记录上有多个变更在审
        Long pending = scoreItemMapper.selectCount(Wrappers.<ScoreItem>lambdaQuery()
                .eq(ScoreItem::getOriginId, originId)
                .eq(ScoreItem::getStatus, ScoreStatus.PENDING));
        if (pending != null && pending > 0) {
            throw BizException.of("该记录已有一条变更申请在审核中，请先等它处理完");
        }

        boolean effectNow = Role.isAdmin(ScopeContext.getRole()) && Boolean.TRUE.equals(dto.getEffectNow());

        ScoreItem nw = new ScoreItem();
        nw.setClassId(origin.getClassId());
        nw.setDeptId(ScopeContext.getDeptId());
        nw.setStudentId(origin.getStudentId());
        nw.setTerm(origin.getTerm());
        nw.setOriginId(originId);
        nw.setChangeType(dto.getChangeType());
        nw.setCreatedBy(ScopeContext.getUserId());
        nw.setEvidenceUrl(origin.getEvidenceUrl());

        if (Objects.equals(dto.getChangeType(), 2)) {
            // 删除申请：分数置 0，事由保留原文，便于审核员看清要删的是哪一条
            nw.setCategory(origin.getCategory());
            nw.setDimension(origin.getDimension());
            nw.setPoints(BigDecimal.ZERO);
            nw.setReason(origin.getReason());
            nw.setOccurredOn(origin.getOccurredOn());
        } else {
            validateCategory(dto.getCategory());
            validateDimension(dto.getDimension());
            nw.setCategory(dto.getCategory());
            nw.setDimension(dto.getDimension());
            nw.setPoints(dto.getPoints());
            // 事由不填就沿用原记录的——"只改分值不改事由"是常见情况。
            // 注意这里用的是新事由，不是 changeReason（那是"为什么要改"，只进日志）
            nw.setReason(dto.getReason() == null || dto.getReason().isBlank()
                    ? origin.getReason() : dto.getReason());
            nw.setOccurredOn(dto.getOccurredOn() == null ? origin.getOccurredOn() : dto.getOccurredOn());
        }

        nw.setStatus(effectNow ? ScoreStatus.APPROVED : ScoreStatus.PENDING);
        if (!effectNow) {
            nw.setSubmittedBy(ScopeContext.getUserId());
            nw.setSubmittedAt(LocalDateTime.now());
        }
        scoreItemMapper.insert(nw);

        if (effectNow) {
            // 一个事务里把原记录置为已失效
            ScoreItem upd = new ScoreItem();
            upd.setId(originId);
            upd.setStatus(ScoreStatus.SUPERSEDED);
            upd.setSupersededBy(nw.getId());
            scoreItemMapper.updateById(upd);
        }

        auditLogService.record(ScopeContext.getUserId(), ScopeContext.getDeptId(), origin.getClassId(),
                "SCORE_ITEM", nw.getId(), "CHANGE", snapshot(origin), snapshot(nw));

        Map<String, Object> result = new HashMap<>();
        result.put("newId", nw.getId());
        result.put("originId", originId);
        result.put("status", nw.getStatus());
        result.put("statusText", ScoreStatus.text(nw.getStatus()));
        result.put("effectNow", effectNow);
        return result;
    }

    // ==================================================================
    //  我的申请
    // ==================================================================

    public Map<String, Object> mine(Integer status, long page, long size) {
        Long uid = ScopeContext.getUserId();
        long offset = Math.max(0, (page - 1) * size);
        List<ScoreItem> list = scoreItemMapper.selectMine(uid, status, offset, size);
        long total = scoreItemMapper.countMine(uid, status);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScoreItem i : list) {
            rows.add(toMineRow(i));
        }
        Map<String, Object> r = new HashMap<>();
        r.put("total", total);
        r.put("page", page);
        r.put("size", size);
        r.put("list", rows);
        return r;
    }

    /** 能不能改、能不能撤回由后端算好，前端直接用，不要在页面上重复判断状态 */
    private Map<String, Object> toMineRow(ScoreItem i) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", i.getId());
        m.put("studentId", i.getStudentId());
        m.put("studentNo", i.getStudentNo());
        m.put("studentName", i.getStudentName());
        m.put("className", i.getClassName());
        m.put("category", i.getCategory());
        m.put("categoryText", categoryText(i.getCategory()));
        m.put("dimension", i.getDimension());
        m.put("dimensionText", dimensionText(i.getDimension()));
        m.put("signedPoints", signedPoints(i));
        m.put("reason", i.getReason());
        m.put("occurredOn", i.getOccurredOn());
        m.put("status", i.getStatus());
        m.put("statusText", ScoreStatus.text(i.getStatus()));
        m.put("rejectReason", i.getRejectReason());
        m.put("changeType", i.getChangeType());
        m.put("originId", i.getOriginId());
        m.put("submittedAt", i.getSubmittedAt());
        m.put("canEdit", i.getStatus() == ScoreStatus.DRAFT || i.getStatus() == ScoreStatus.REJECTED);
        m.put("canRevoke", i.getStatus() == ScoreStatus.PENDING);
        return m;
    }

    // ==================================================================
    //  给 ReviewService 用的工具方法
    // ==================================================================

    /** 取一条流水并校验"是自己提交的"。一级同样受这条约束 */
    ScoreItem getOwnItem(Long id) {
        ScoreItem item = scoreItemMapper.selectById(id);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw BizException.notFound("记录不存在");
        }
        if (!ScopeContext.canSee(item.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该记录");
        }
        if (!Objects.equals(item.getCreatedBy(), ScopeContext.getUserId())) {
            throw BizException.of("只能操作自己提交的记录");
        }
        return item;
    }

    static BigDecimal signedPoints(ScoreItem i) {
        if (i.getPoints() == null) {
            return BigDecimal.ZERO;
        }
        return i.getCategory() != null && i.getCategory() == 2
                ? i.getPoints().negate() : i.getPoints();
    }

    /** 职位分已取消，只允许加分 / 扣分 */
    static void validateCategory(Integer category) {
        if (category == null || (category != 1 && category != 2)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "类型只能是加分 / 扣分");
        }
    }

    static String categoryText(Integer c) {
        if (c == null) {
            return "";
        }
        return switch (c) {
            case 1 -> "加分";
            case 2 -> "扣分";
            default -> "未知";
        };
    }

    /** 五育类别校验：新增、编辑、变更、导入都要求 1~5 */
    static void validateDimension(Integer dimension) {
        if (dimension == null || dimension < 1 || dimension > 5) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择类别（德/智/体/美/劳）");
        }
    }

    /** 五育类别展示文字；历史数据为 null 时显示「未分类」 */
    static String dimensionText(Integer d) {
        if (d == null) {
            return "未分类";
        }
        return switch (d) {
            case 1 -> "德";
            case 2 -> "智";
            case 3 -> "体";
            case 4 -> "美";
            case 5 -> "劳";
            default -> "未分类";
        };
    }

    /** 改动前快照，不包含密码之类的敏感字段 */
    static ScoreItem snapshot(ScoreItem i) {
        if (i == null) {
            return null;
        }
        ScoreItem s = new ScoreItem();
        s.setId(i.getId());
        s.setStudentId(i.getStudentId());
        s.setCategory(i.getCategory());
        s.setDimension(i.getDimension());
        s.setPoints(i.getPoints());
        s.setReason(i.getReason());
        s.setOccurredOn(i.getOccurredOn());
        s.setStatus(i.getStatus());
        s.setOriginId(i.getOriginId());
        s.setChangeType(i.getChangeType());
        return s;
    }
}

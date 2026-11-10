package com.quantify.score;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分流水 / 申请单 —— 整个系统的核心。
 *
 * <p>一条记录 = 一个事件 = 一张申请单。二级录入 → 提交 → 一级审批 → 通过后才计入总分。
 *
 * <p><b>points 恒为正数</b>，方向由 category 决定。原表用正负号表示方向，
 * 一旦扣分被填成 -6，公式 {@code 基础分 - 扣分} 会变成 {@code -(-6) = +6}，扣分变加分且不报错。
 * 现在符号只在聚合 SQL 里出现一次。
 */
@Data
@TableName("score_item")
public class ScoreItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 冗余班级字段，配合数据范围的 IN 过滤，避免每次 join student */
    private Long classId;

    /** 可选标签，记录"这条分是哪个部门录的"；随记录固化 */
    private Long deptId;

    private Long studentId;

    /** 学期，如 2025-2026-1 */
    private String term;

    /** 1 加分 / 2 扣分（职位分已取消） */
    private Integer category;

    /** 五育类别：1德 2智 3体 4美 5劳；null = 历史数据未分类 */
    private Integer dimension;

    /** 分值，恒为正数 */
    private BigDecimal points;

    /** 事由 */
    private String reason;

    private LocalDate occurredOn;

    private String evidenceUrl;

    /**
     * 0 草稿 / 1 待审 / 2 已通过 / 3 已驳回 / 4 已撤销 / 5 已失效。
     *
     * <p>注：{@code status=2 且 reviewedBy=null} 表示"一级直接录入生效"，没人审过；
     * {@code status=2 且 reviewedBy 有值} 表示正常走完审批。用这个区分，不用加字段。
     */
    private Integer status;

    /** 批量录入批次号，便于整批撤回 */
    private String batchNo;

    private Long submittedBy;
    private LocalDateTime submittedAt;

    /** 审核人（一级） */
    private Long reviewedBy;
    private LocalDateTime reviewedAt;

    private String rejectReason;

    // ---- 变更链：已通过的记录不能就地改，只能新建一条变更申请指向它 ----

    /** 变更申请：指向被修改/被删除的原记录 id */
    private Long originId;

    /** 0 新增 / 1 修改 / 2 删除 */
    private Integer changeType;

    /** 本记录被哪条新记录替代（status=5 时才有值） */
    private Long supersededBy;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    // ---- join 出来的展示字段 ----

    @TableField(exist = false)
    private String studentNo;

    @TableField(exist = false)
    private String studentName;

    @TableField(exist = false)
    private String className;

    @TableField(exist = false)
    private String dimensionText;

    @TableField(exist = false)
    private String deptName;

    @TableField(exist = false)
    private String submitterName;

    @TableField(exist = false)
    private String reviewerName;

    @TableField(exist = false)
    private LocalDate originOccurredOn;

    @TableField(exist = false)
    private BigDecimal originPoints;

    @TableField(exist = false)
    private String originReason;
}

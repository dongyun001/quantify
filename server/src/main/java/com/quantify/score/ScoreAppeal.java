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
 * 量化申诉。
 *
 * <p>只有三级（VIEWER）能发起，且只能对「已通过」的流水申诉。
 * 一级受理时不能直接改原记录，必须走变更链——和 {@link ScoreItem} 的变更机制一致。
 *
 * <p>{@code active_flag} 是数据库生成列，不在实体里映射；它只服务于
 * 「同一学生对同一条记录、同一申诉人只能有一条待处理申诉」这个唯一索引。
 */
@Data
@TableName("score_appeal")
public class ScoreAppeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long scoreItemId;
    private Long studentId;

    /** 冗余班级，配合数据范围的 IN 过滤，避免每次 join student */
    private Long classId;

    private String term;

    /** 1 分数有误 / 2 事由有误 / 3 重复录入 / 4 其他 */
    private Integer appealType;

    private String appealReason;
    private String evidenceUrl;

    /** 0 待处理 / 1 已受理 / 2 已驳回 / 3 已撤回 */
    private Integer status;

    /** 受理后生成的变更记录 id */
    private Long resultChangeId;

    /** 0 未发 / 1 成功 / 2 失败 */
    private Integer notifyStatus;
    private String notifyError;

    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewRemark;

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
    private Integer scoreCategory;

    @TableField(exist = false)
    private Integer scoreDimension;

    @TableField(exist = false)
    private String scoreDimensionText;

    @TableField(exist = false)
    private BigDecimal scorePoints;

    @TableField(exist = false)
    private String scoreReason;

    @TableField(exist = false)
    private LocalDate scoreOccurredOn;

    @TableField(exist = false)
    private Integer scoreStatus;

    @TableField(exist = false)
    private String createdByName;

    @TableField(exist = false)
    private String reviewedByName;
}

package com.quantify.score;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 变更申请入参。
 *
 * <p>已通过的记录不能就地改，只能发一条这个，指向原记录。
 */
@Data
public class ChangeDTO {

    /** 1 修改 / 2 删除 */
    @NotNull(message = "请选择变更类型")
    private Integer changeType;

    /** changeType=1 时必填：新的五育类别（1德 2智 3体 4美 5劳） */
    @Min(value = 1, message = "请选择类别（德/智/体/美/劳）")
    @Max(value = 5, message = "请选择类别（德/智/体/美/劳）")
    private Integer dimension;

    /** changeType=1 时必填：新的类型（1加分 / 2扣分） */
    @Min(value = 1, message = "类型只能是加分 / 扣分")
    @Max(value = 2, message = "类型只能是加分 / 扣分")
    private Integer category;

    /** changeType=1 时必填：新的分值（正数） */
    private BigDecimal points;

    /**
     * changeType=1 时填：新的<b>事由</b>（这条记录本身讲的是什么事）。
     * 不填则沿用原记录的事由——只改分值、不改事由是常见情况。
     */
    private String reason;

    private LocalDate occurredOn;

    /**
     * <b>为什么要改</b>，写进操作日志供审核员判断。
     * 注意它和 {@link #reason} 是两回事：
     *   reason       = "元旦晚会志愿者"          （记录本身的内容）
     *   changeReason = "签到表显示服务时长3小时"  （改它的依据）
     */
    @NotNull(message = "请说明修改原因")
    private String changeReason;

    /** 仅一级有效：true = 变更立即生效，跳过审批 */
    private Boolean effectNow;
}

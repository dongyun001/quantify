package com.quantify.score;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ScoreItemDTO {

    @NotNull(message = "请选择学生")
    private Long studentId;

    /** 五育类别：1德 2智 3体 4美 5劳，必填 */
    @NotNull(message = "请选择类别（德/智/体/美/劳）")
    @Min(value = 1, message = "请选择类别（德/智/体/美/劳）")
    @Max(value = 5, message = "请选择类别（德/智/体/美/劳）")
    private Integer dimension;

    /** 1 加分 / 2 扣分（职位分已取消） */
    @NotNull(message = "请选择类型")
    @Min(value = 1, message = "类型只能是加分 / 扣分")
    @Max(value = 2, message = "类型只能是加分 / 扣分")
    private Integer category;

    /**
     * 分值。必须为正数——方向由 category 决定。
     * 原表用正负号表示方向，扣分填成 -6 会让公式变成 -(-6)=+6，扣分变加分且不报错。
     */
    @NotNull(message = "请填写分值")
    @Positive(message = "分值必须为正数")
    private BigDecimal points;

    private String reason;
    private LocalDate occurredOn;
    private String term;
    private String evidenceUrl;

    /**
     * 是否直接生效。<b>仅一级有效</b>，二级传了也会被忽略。
     * true = 跳过审批，status 直接为已通过。
     */
    private Boolean effectNow;
}

package com.quantify.score;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 批量导入确认请求。
 *
 * <p>与单条录入的区别只有「一次多条」和「共用 batch_no」；
 * 每一条的字段语义完全一致（{@link ScoreItemDTO}）。
 */
@Data
public class BatchImportDTO {

    @NotEmpty(message = "请至少提供一条记录")
    @Size(max = 2000, message = "单次最多导入 2000 条，请分批导入")
    @Valid
    private List<Item> items;

    /** 落库后逐条提交，status 0 → 1 */
    private Boolean submitNow;

    /** 仅一级有效；二级传了会被忽略（AGENTS.md R16） */
    private Boolean effectNow;

    @Data
    public static class Item {

        @NotNull(message = "请选择学生")
        private Long studentId;

        /** 五育类别：1德 2智 3体 4美 5劳 */
        @NotNull(message = "请选择类别（德/智/体/美/劳）")
        @Min(value = 1, message = "请选择类别（德/智/体/美/劳）")
        @Max(value = 5, message = "请选择类别（德/智/体/美/劳）")
        private Integer dimension;

        /** 1 加分 / 2 扣分（职位分已取消） */
        @NotNull(message = "请选择类型")
        @Min(value = 1, message = "类型只能是加分 / 扣分")
        @Max(value = 2, message = "类型只能是加分 / 扣分")
        private Integer category;

        /** 恒为正数，方向由 category 决定（AGENTS.md R1） */
        @NotNull(message = "请填写分值")
        @Positive(message = "分值必须为正数")
        private BigDecimal points;

        @NotBlank(message = "事由不能为空")
        @Size(max = 500, message = "事由不能超过 500 字")
        private String reason;

        private LocalDate occurredOn;
    }
}

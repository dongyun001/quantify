package com.quantify.student;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StudentDTO {

    @NotBlank(message = "学号不能为空")
    private String studentNo;

    @NotBlank(message = "姓名不能为空")
    private String name;

    @NotNull(message = "请选择班级")
    private Long classId;

    private BigDecimal baseScore;

    /** 1 在册 / 0 离班 */
    private Integer status;
}

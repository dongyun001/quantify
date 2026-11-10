package com.quantify.student;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生。
 *
 * <p>注意这张表<b>没有 deleted 字段</b>，用 {@code status} 表示在册/离班。
 * 离班绝不能物理删除——历史流水上挂着 student_id，人删了成绩就再也导不出来了。
 */
@Data
@TableName("student")
public class Student {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long classId;

    private String studentNo;

    private String name;

    /** 基础分，默认 50 */
    private BigDecimal baseScore;

    /** 1 在册 / 0 离班 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- join 出来的展示字段 ----

    @TableField(exist = false)
    private String className;

    @TableField(exist = false)
    private String gradeName;

    /** 加分合计（正数） */
    @TableField(exist = false)
    private BigDecimal addScore;

    /** 扣分合计（负数，与原表扣分列一致） */
    @TableField(exist = false)
    private BigDecimal deductScore;

    /** 总分 = 基础分 + 加分 + 扣分 */
    @TableField(exist = false)
    private BigDecimal totalScore;
}

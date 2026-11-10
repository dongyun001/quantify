package com.quantify.org;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 班级，如 25级10班 */
@Data
@TableName("sys_class")
public class SysClass {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long gradeId;

    /** 班号，数字存。用来排序——不然"10班"会排在"9班"前面 */
    private Integer classNo;

    /** 班级全称，给人看的 */
    private String name;

    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 以下不是表字段，查询时 join 带出来 ----

    @TableField(exist = false)
    private String gradeName;

    @TableField(exist = false)
    private Integer studentCount;
}

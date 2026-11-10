package com.quantify.org;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 年级，如 25级 */
@Data
@TableName("sys_grade")
public class SysGrade {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String gradeName;

    /** 入学年份，用于排序 */
    private Integer enrollYear;

    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

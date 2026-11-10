package com.quantify.org;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门。
 *
 * <p><b>不参与权限判定。</b>给账号和流水打一个来源标签用，不需要就整张表空着，系统照跑。
 */
@Data
@TableName("sys_dept")
public class SysDept {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deptName;
    private String fullName;
    private Integer sortNo;

    /** 1 启用 / 0 停用。撤销的部门停用不删，历史流水还要显示它的名字 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

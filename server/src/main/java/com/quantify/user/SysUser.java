package com.quantify.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号。
 *
 * <p>权限只由两个字段决定：
 * <ul>
 *   <li>{@code role}  决定"能做什么"：ADMIN / EDITOR / VIEWER</li>
 *   <li>{@code scopeType + scopeId} 决定"能看多大"：全院 / 本年级 / 本班</li>
 * </ul>
 *
 * <p>{@code deptId} 是可选标签，不参与鉴权判定。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文，永远不存明文 */
    private String password;

    private String realName;

    /** ADMIN / EDITOR / VIEWER */
    private String role;

    /** 可选标签，不参与权限判定，可空 */
    private Long deptId;

    /** 1 全院 / 2 本年级 / 3 本班 */
    private Integer scopeType;

    /** scopeType=1 填 0；=2 填 gradeId；=3 填 classId */
    private Long scopeId;

    /** 本人是学生时关联 student.id */
    private Long studentId;

    /** 备注，如"班长"、"体育部干事"，仅用于人工识别 */
    private String remark;

    private Integer status;

    /** 0 = 初始密码未改，除改密外一律拒绝 */
    private Integer pwdChanged;

    private LocalDateTime lastLoginAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- join 出来的展示字段 ----

    @TableField(exist = false)
    private String deptName;

    @TableField(exist = false)
    private String scopeName;
}

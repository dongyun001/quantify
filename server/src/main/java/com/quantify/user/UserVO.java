package com.quantify.user;

import lombok.Data;

import java.time.LocalDateTime;

/** 账号列表项。<b>不包含密码字段</b>——这是刻意的 */
@Data
public class UserVO {
    private Long id;
    private String username;
    private String realName;
    private String role;
    private String roleText;
    private Long deptId;
    private String deptName;
    private String remark;
    private Integer scopeType;
    private Long scopeId;
    private String scopeName;
    /** 范围的文字描述，如"25级10班"，直接显示 */
    private String scopeDetail;
    private Integer status;
    private Boolean pwdChanged;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}

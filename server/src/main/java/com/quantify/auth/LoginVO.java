package com.quantify.auth;

import lombok.Data;

@Data
public class LoginVO {

    private String token;
    private Long userId;
    private String username;
    private String realName;
    private String role;
    private Long deptId;
    private String deptName;
    /** false 表示还在用初始密码，前端必须跳改密页 */
    private Boolean pwdChanged;
}

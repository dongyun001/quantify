package com.quantify.auth;

import lombok.Data;

@Data
public class CurrentUserVO {

    private Long userId;
    private String username;
    private String realName;
    private String role;
    private Long deptId;
    private String deptName;
    private String remark;

    /** 1 全院 / 2 本年级 / 3 本班 */
    private Integer scopeType;
    private String scopeName;
    private Long scopeId;

    private Long studentId;
    private Long classId;
    private String className;
    private Long gradeId;
    private String gradeName;

    private Boolean pwdChanged;

    /** 当前可见的班级数量，前端可用来说明"你的数据范围" */
    private Integer classCount;
}

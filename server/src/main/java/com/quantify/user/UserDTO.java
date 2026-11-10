package com.quantify.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserDTO {

    /** 创建时必填；编辑时忽略（用户名不可改） */
    @Size(min = 3, max = 50, message = "用户名长度需在 3~50 之间")
    private String username;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotNull(message = "请选择角色")
    private String role;

    @NotNull(message = "请选择数据范围")
    private Integer scopeType;

    /** scopeType=2 填年级id，=3 填班级id，=1 忽略 */
    private Long scopeId;

    /** 不填则系统随机生成 8 位 */
    private String password;

    private Long deptId;
    private Long studentId;
    private String remark;
}

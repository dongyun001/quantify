package com.quantify.user;

/**
 * 创建账号的返回。
 *
 * <p>{@code initialPassword} 是明文密码<b>唯一一次</b>出现的地方——
 * 数据库里存的是 BCrypt 密文，刷新页面就再也拿不到了，忘了只能重置。
 */
public record CreateUserVO(Long id, String username, String realName,
                           String role, String scopeName,
                           String initialPassword, Boolean passwordGenerated) {
}

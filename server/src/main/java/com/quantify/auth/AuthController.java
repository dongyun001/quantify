package com.quantify.auth;

import com.quantify.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<LoginVO> login(@RequestBody @Valid LoginDTO dto, HttpServletRequest req) {
        return R.ok(authService.login(dto, clientIp(req)));
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest req) {
        authService.logout(ScopeContext.getUserId(), clientIp(req));
        return R.ok();
    }

    @GetMapping("/me")
    public R<CurrentUserVO> me() {
        return R.ok(authService.currentUser());
    }

    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody @Valid PasswordDTO dto, HttpServletRequest req) {
        authService.changePassword(ScopeContext.getUserId(), dto.getOldPassword(),
                dto.getNewPassword(), clientIp(req));
        return R.ok();
    }

    @Data
    public static class PasswordDTO {
        private String oldPassword;
        private String newPassword;
    }

    /** 取真实客户端 IP。经过 Nginx 时 X-Forwarded-For 才有值 */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real;
        }
        return req.getRemoteAddr();
    }
}

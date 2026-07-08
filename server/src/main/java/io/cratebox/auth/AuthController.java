package io.cratebox.auth;

import io.cratebox.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record MeResponse(Long userId, String username, String displayName, String role) {}

    public record PasswordChangeRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8) String newPassword) {}

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository,
                          JdbcClient jdbc, PasswordEncoder encoder) {
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Validated @RequestBody LoginRequest req,
                                   HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication auth = authManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            contextRepository.saveContext(ctx, request, response);
            AppPrincipal p = (AppPrincipal) auth.getPrincipal();
            return ResponseEntity.ok(new MeResponse(p.userId(), p.username(), p.displayName(), p.role()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "아이디 또는 비밀번호가 올바르지 않습니다"));
        }
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppPrincipal p) {
        return new MeResponse(p.userId(), p.username(), p.displayName(), p.role());
    }

    /** 본인 비밀번호 변경 (ADMIN·LABEL 공용, 현재 비밀번호 확인 후) */
    @PostMapping("/password")
    public void changePassword(@AuthenticationPrincipal AppPrincipal p,
                               @Validated @RequestBody PasswordChangeRequest req) {
        String hash = jdbc.sql("select password_hash from app_user where id = :id")
                .param("id", p.userId()).query(String.class).single();
        if (!encoder.matches(req.currentPassword(), hash)) {
            throw new DomainException("현재 비밀번호가 올바르지 않습니다");
        }
        jdbc.sql("update app_user set password_hash = :hash where id = :id")
                .param("hash", encoder.encode(req.newPassword())).param("id", p.userId())
                .update();
    }
}

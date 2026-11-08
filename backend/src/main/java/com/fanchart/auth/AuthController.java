package com.fanchart.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
class AuthController {

    record Provider(String id, String name, String loginUrl) {
    }

    /** @param passwordReset 메일 발송이 설정돼 있어 비밀번호 찾기를 쓸 수 있는지 */
    record LoginOptions(List<Provider> providers, boolean devLogin, boolean passwordReset) {
    }

    record ErrorResponse(String message) {
    }

    record DevLoginRequest(@NotBlank @Size(max = 20) String nickname) {
    }

    record SignupRequest(@NotNull @Size(max = 40) String loginId, @NotNull @Size(max = 100) String password,
                         @Size(max = 254) String email) {
    }

    record PasswordLoginRequest(@NotNull @Size(max = 40) String loginId, @NotNull @Size(max = 100) String password) {
    }

    record ResetRequest(@NotNull @Size(max = 40) String loginId, @NotNull @Size(max = 254) String email) {
    }

    record ResetConfirm(@NotNull @Size(max = 100) String token, @NotNull @Size(max = 100) String password) {
    }

    record LoginIdCheck(boolean available, String message) {
    }

    private final AccountLogin login;
    private final PasswordLogin passwords;
    private final AuthProperties props;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    AuthController(AccountLogin login, PasswordLogin passwords, AuthProperties props,
                   ObjectProvider<ClientRegistrationRepository> registrations) {
        this.login = login;
        this.passwords = passwords;
        this.props = props;
        this.registrations = registrations;
    }

    @GetMapping("/api/auth/options")
    LoginOptions options() {
        List<Provider> providers = new ArrayList<>();
        if (registrations.getIfAvailable() instanceof InMemoryClientRegistrationRepository repo) {
            for (ClientRegistration r : repo) {
                providers.add(new Provider(r.getRegistrationId(), r.getClientName(),
                        "/oauth2/authorization/" + r.getRegistrationId()));
            }
        }
        return new LoginOptions(providers, props.devLogin(), passwords.resetAvailable());
    }

    /** 가입 화면에서 아이디를 쓸 수 있는지 미리 본다 */
    @GetMapping("/api/auth/login-ids/check")
    LoginIdCheck checkLoginId(@RequestParam String loginId) {
        return passwords.unavailableReason(loginId)
                .map(reason -> new LoginIdCheck(false, reason))
                .orElse(new LoginIdCheck(true, "쓸 수 있는 아이디예요"));
    }

    /** 아이디·비밀번호로 가입하고 바로 로그인한다 */
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signUp(@Valid @RequestBody SignupRequest req, HttpServletRequest request, HttpServletResponse response) {
        login.signIn(passwords.signUp(req.loginId(), req.password(), req.email()), request, response);
    }

    @PostMapping("/api/auth/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void passwordLogin(@Valid @RequestBody PasswordLoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        login.signIn(passwords.authenticate(req.loginId(), req.password()), request, response);
    }

    /** 아이디와 이메일이 맞으면 재설정 링크를 보낸다. 맞는지는 알려 주지 않는다 */
    @PostMapping("/api/auth/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void requestReset(@Valid @RequestBody ResetRequest req) {
        passwords.requestReset(req.loginId(), req.email());
    }

    @PostMapping("/api/auth/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmReset(@Valid @RequestBody ResetConfirm req) {
        passwords.confirmReset(req.token(), req.password());
    }

    /** 개발·시연용. 같은 닉네임이면 같은 계정으로 들어간다 */
    @PostMapping("/api/auth/dev-login")
    ResponseEntity<?> devLogin(@Valid @RequestBody DevLoginRequest req, HttpServletRequest request,
                               HttpServletResponse response) {
        if (!props.devLogin()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("없는 경로예요"));
        login.signIn(login.findOrCreate("dev", req.nickname().strip()), request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}

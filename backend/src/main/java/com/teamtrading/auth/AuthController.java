package com.teamtrading.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
class AuthController {

    record Provider(String id, String name, String loginUrl) {
    }

    record LoginOptions(List<Provider> providers, boolean devLogin) {
    }

    record ErrorResponse(String message) {
    }

    record DevLoginRequest(@NotBlank @Size(max = 20) String nickname) {
    }

    private final AccountLogin login;
    private final AuthProperties props;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    AuthController(AccountLogin login, AuthProperties props, ObjectProvider<ClientRegistrationRepository> registrations) {
        this.login = login;
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
        return new LoginOptions(providers, props.devLogin());
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

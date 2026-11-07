package com.fanchart.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 탈퇴한 계정의 세션(다른 기기에서 로그인해 둔 것 포함)을 다음 요청에서 끊는다. 그 요청은 로그인하지 않은 요청으로 처리된다 */
class WithdrawnSessionFilter extends OncePerRequestFilter {

    private final Withdrawal withdrawal;

    WithdrawnSessionFilter(Withdrawal withdrawal) {
        this.withdrawal = withdrawal;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof AccountAuthentication a
                && withdrawal.isWithdrawn(a.getPrincipal())) {
            HttpSession session = req.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(req, res);
    }
}

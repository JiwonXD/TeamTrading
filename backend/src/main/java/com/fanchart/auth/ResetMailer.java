package com.fanchart.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** 비밀번호 재설정 메일. SMTP 설정(MAIL_HOST 등)이 없으면 보내지 않는다(ADR 0037) */
@Component
@EnableConfigurationProperties(ResetMailProperties.class)
class ResetMailer {

    private final ResetMailProperties props;
    private final JavaMailSenderImpl sender;

    ResetMailer(ResetMailProperties props) {
        this.props = props;
        if (props.host() == null || props.host().isBlank()) {
            sender = null;
            return;
        }
        sender = new JavaMailSenderImpl();
        sender.setHost(props.host());
        sender.setPort(props.port());
        sender.setUsername(props.username());
        sender.setPassword(props.password());
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(props.username() != null && !props.username().isBlank()));
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
    }

    boolean available() {
        return sender != null;
    }

    void sendReset(String to, String loginId, String token) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(props.from() == null || props.from().isBlank() ? props.username() : props.from());
        m.setTo(to);
        m.setSubject("[팬차트] 비밀번호 재설정");
        m.setText("""
                팬차트 아이디 %s의 비밀번호 재설정을 요청했어요.
                아래 링크에서 30분 안에 새 비밀번호를 정해 주세요.

                %s/#reset-%s

                요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않아요.
                """.formatted(loginId, props.siteUrl().replaceAll("/+$", ""), token));
        sender.send(m);
    }
}

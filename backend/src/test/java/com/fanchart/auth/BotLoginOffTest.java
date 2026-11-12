package com.fanchart.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 비밀 값(BOT_TOKEN)을 넣지 않은 서버는 봇 로그인이 없다 */
@SpringBootTest
@AutoConfigureMockMvc
class BotLoginOffTest {

    @Autowired MockMvc mvc;

    @Test
    void botLoginIsClosedWithoutToken() throws Exception {
        mvc.perform(post("/api/auth/bot-login").with(csrf()).header("X-Bot-Token", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bot-1\"}"))
                .andExpect(status().isNotFound());
    }
}

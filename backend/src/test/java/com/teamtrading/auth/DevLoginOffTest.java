package com.teamtrading.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "teamtrading.auth.dev-login=false")
@AutoConfigureMockMvc
class DevLoginOffTest {

    @Autowired MockMvc mvc;

    @Test
    void devLoginIsClosedOutsideDemo() throws Exception {
        mvc.perform(post("/api/auth/dev-login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}

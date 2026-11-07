package com.fanchart.api;

import com.fanchart.trading.TradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.fanchart.auth.TestLogin.admin;
import static com.fanchart.auth.TestLogin.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PostApiTest {

    @Autowired MockMvc mvc;
    @Autowired TradingService trading;

    @Test
    void anyoneReadsSignedInUsersWriteAndOnlyTheAuthorSeesTheRewardState() throws Exception {
        String author = trading.openAccount("talker").id();
        String reader = trading.openAccount("reader").id();
        String body = "{\"body\":\"키움 오늘 타선 좋다\"}";

        mvc.perform(post("/api/teams/kiwoom/posts").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String created = mvc.perform(post("/api/teams/kiwoom/posts").with(login(author))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.reward").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(author);
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        String listed = mvc.perform(get("/api/teams/kiwoom/posts"))
                .andExpect(jsonPath("$[0].nickname").value("talker"))
                .andExpect(jsonPath("$[0].mine").value(false))
                .andExpect(jsonPath("$[0].reward").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(author);

        mvc.perform(post("/api/posts/" + id + "/reports").with(login(reader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/posts/" + id + "/hide").with(login(reader))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/posts/" + id + "/hide").with(admin(reader)))
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/teams/kiwoom/posts")).andExpect(jsonPath("$.length()").value(0));
    }
}

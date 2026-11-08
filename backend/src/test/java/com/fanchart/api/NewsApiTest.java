package com.fanchart.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 네이버 검색 API 대신 로컬 서버를 띄워 구단 뉴스를 받는다 */
@SpringBootTest
@AutoConfigureMockMvc
class NewsApiTest {

    private static final List<String> requests = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static final HttpServer server;

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", ex -> {
            requests.add(ex.getRequestHeaders().getFirst("X-Naver-Client-Id") + " "
                    + URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] body = ("""
                    {"items":[
                     {"title":"<b>T1</b> &quot;페이커&quot; 롤드컵 8강","originallink":"https://www.sportsnews.example/a/1",
                      "link":"https://n.news.naver.com/1","description":"<b>T1</b>이 8강에 &amp; 올랐다",
                      "pubDate":"Sun, 08 Nov 2026 18:20:00 +0900"},
                     {"title":"원문 주소가 없는 기사","originallink":"","link":"https://n.news.naver.com/2",
                      "description":"","pubDate":"Sun, 08 Nov 2026 17:00:00 +0900"},
                     {"title":"시각이 깨진 기사","originallink":"https://bad.example/3","link":"",
                      "description":"","pubDate":"어제"}
                    ]}""").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @DynamicPropertySource
    static void news(DynamicPropertyRegistry r) {
        r.add("fanchart.news.naver-client-id", () -> "test-id");
        r.add("fanchart.news.naver-client-secret", () -> "test-secret");
        r.add("fanchart.news.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/search/news.json");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void searchesTheTeamWithItsSportCleansHtmlAndCachesPerTeam() throws Exception {
        mvc.perform(get("/api/teams/t1/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.articles.length()").value(2))
                .andExpect(jsonPath("$.articles[0].title").value("T1 \"페이커\" 롤드컵 8강"))
                .andExpect(jsonPath("$.articles[0].summary").value("T1이 8강에 & 올랐다"))
                .andExpect(jsonPath("$.articles[0].url").value("https://www.sportsnews.example/a/1"))
                .andExpect(jsonPath("$.articles[0].source").value("sportsnews.example"))
                .andExpect(jsonPath("$.articles[0].publishedAt").value("2026-11-08T09:20:00Z"))
                // 원문 주소가 없으면 네이버 뉴스 주소
                .andExpect(jsonPath("$.articles[1].url").value("https://n.news.naver.com/2"));
        assertThat(requests).anyMatch(q -> q.startsWith("test-id query=T1 롤&display=20&sort=date"));

        int before = requests.size();
        mvc.perform(get("/api/teams/t1/news")).andExpect(jsonPath("$.articles.length()").value(2));
        assertThat(requests).hasSize(before);

        mvc.perform(get("/api/teams/lg/news")).andExpect(status().isOk());
        assertThat(requests).anyMatch(q -> q.contains("query=LG 트윈스 야구"));
    }

    @Test
    void failsWithoutEarlierResultsAndRejectsUnknownTeams() throws Exception {
        status = 500;
        try {
            mvc.perform(get("/api/teams/nc/news")).andExpect(status().is5xxServerError());
        } finally {
            status = 200;
        }
        mvc.perform(get("/api/teams/nope/news")).andExpect(status().isNotFound());
    }
}

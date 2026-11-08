package com.fanchart.api;

import com.fanchart.match.TeamOverview;
import com.fanchart.news.TeamNews;
import com.fanchart.notice.Notices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 구단 화면의 구단정보(배당 조건·다가오는 경기), 뉴스, 공시. 읽기는 누구나, 공시 쓰기·고치기·지우기는 운영자(/api/admin/**) */
@RestController
class TeamInfoController {

    /** teamId를 비우면 모든 구단에 보이는 전체 공시 */
    record WriteRequest(@Size(max = 20) String teamId, @NotBlank @Size(max = 100) String title,
                        @NotBlank @Size(max = 3000) String body) {
    }

    record EditRequest(@NotBlank @Size(max = 100) String title, @NotBlank @Size(max = 3000) String body) {
    }

    private final TeamOverview overview;
    private final Notices notices;
    private final TeamNews news;

    TeamInfoController(TeamOverview overview, Notices notices, TeamNews news) {
        this.overview = overview;
        this.notices = notices;
        this.news = news;
    }

    @GetMapping("/api/teams/{teamId}/news")
    TeamNews.News news(@PathVariable String teamId) {
        try {
            return news.of(teamId);
        } catch (TeamNews.Unavailable e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "뉴스를 불러오지 못했어요");
        }
    }

    @GetMapping("/api/teams/{teamId}/overview")
    TeamOverview.Overview overview(@PathVariable String teamId) {
        return overview.of(teamId);
    }

    @GetMapping("/api/teams/{teamId}/notices")
    List<Notices.Notice> notices(@PathVariable String teamId, @RequestParam(defaultValue = "30") int limit) {
        return notices.forTeam(teamId, Math.min(Math.max(limit, 1), 100));
    }

    @PostMapping("/api/admin/notices")
    @ResponseStatus(HttpStatus.CREATED)
    Notices.Notice write(@AuthenticationPrincipal String adminId, @Valid @RequestBody WriteRequest req) {
        String teamId = req.teamId() == null || req.teamId().isBlank() ? null : req.teamId().strip();
        return notices.write(adminId, teamId, req.title(), req.body());
    }

    @PutMapping("/api/admin/notices/{id}")
    Notices.Notice edit(@AuthenticationPrincipal String adminId, @PathVariable long id,
                        @Valid @RequestBody EditRequest req) {
        return notices.edit(adminId, id, req.title(), req.body());
    }

    @DeleteMapping("/api/admin/notices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal String adminId, @PathVariable long id) {
        notices.delete(adminId, id);
    }
}

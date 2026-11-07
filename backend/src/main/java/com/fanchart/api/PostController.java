package com.fanchart.api;

import com.fanchart.post.Post;
import com.fanchart.post.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 종목토론방. 읽기는 누구나, 쓰기·삭제·신고는 로그인, 숨김·복구는 운영자. 계정 id는 내보내지 않는다 */
@RestController
class PostController {

    /** @param reward 내 글일 때만 보상 상태(PENDING, PAID, NONE), 아니면 null */
    record PostResponse(long id, String teamId, String nickname, String body, boolean holder, Instant createdAt,
                        boolean mine, String reward) {
        static PostResponse of(Post p, String viewer) {
            boolean mine = p.accountId().equals(viewer);
            return new PostResponse(p.id(), p.teamId(), p.nickname(), p.body(), p.holder(), p.createdAt(), mine,
                    mine ? p.reward().name() : null);
        }
    }

    record WriteRequest(@NotNull @Size(max = 1000) String body) {
    }

    record ReportRequest(@NotNull Post.ReportReason reason) {
    }

    private final PostService posts;

    PostController(PostService posts) {
        this.posts = posts;
    }

    @GetMapping("/api/teams/{teamId}/posts")
    List<PostResponse> list(@AuthenticationPrincipal String accountId, @PathVariable String teamId,
                            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "30") int limit) {
        return posts.list(teamId, before, Math.min(Math.max(limit, 1), 100)).stream()
                .map(p -> PostResponse.of(p, accountId)).toList();
    }

    @PostMapping("/api/teams/{teamId}/posts")
    @ResponseStatus(HttpStatus.CREATED)
    PostResponse write(@AuthenticationPrincipal String accountId, @PathVariable String teamId,
                       @Valid @RequestBody WriteRequest req) {
        return PostResponse.of(posts.write(accountId, teamId, req.body()), accountId);
    }

    @DeleteMapping("/api/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal String accountId, @PathVariable long postId) {
        posts.delete(accountId, postId);
    }

    @PostMapping("/api/posts/{postId}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@AuthenticationPrincipal String accountId, @PathVariable long postId,
                @Valid @RequestBody ReportRequest req) {
        posts.report(accountId, postId, req.reason());
    }

    @GetMapping("/api/admin/posts")
    List<PostResponse> moderation(@RequestParam(defaultValue = "HIDDEN") Post.Status status,
                                  @RequestParam(defaultValue = "50") int limit) {
        return posts.byStatus(status, Math.min(Math.max(limit, 1), 200)).stream().map(p -> PostResponse.of(p, null))
                .toList();
    }

    @PostMapping("/api/admin/posts/{postId}/hide")
    PostResponse hide(@PathVariable long postId) {
        return PostResponse.of(posts.hide(postId), null);
    }

    @PostMapping("/api/admin/posts/{postId}/restore")
    PostResponse restore(@PathVariable long postId) {
        return PostResponse.of(posts.restore(postId), null);
    }
}

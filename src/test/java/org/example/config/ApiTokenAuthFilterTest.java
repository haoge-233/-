package org.example.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiTokenAuthFilter 的行为契约：鉴权关闭时全放行，开启时 /api/** 必须带正确令牌
 */
class ApiTokenAuthFilterTest {

    private ApiTokenAuthFilter filter;
    private MockHttpServletResponse response;
    private AtomicBoolean chainInvoked;

    @BeforeEach
    void setUp() {
        filter = new ApiTokenAuthFilter();
        response = new MockHttpServletResponse();
        chainInvoked = new AtomicBoolean(false);
    }

    private void withToken(String token) {
        ReflectionTestUtils.setField(filter, "configuredToken", token);
    }

    private FilterChain recordingChain() {
        return (req, res) -> chainInvoked.set(true);
    }

    /**
     * 走公开的 doFilter 入口，确保 shouldNotFilter 的短路逻辑也被覆盖
     */
    private void doFilter(String uri, String headerToken, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (headerToken != null) {
            request.addHeader("X-API-Token", headerToken);
        }
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        filter.doFilter(request, response, recordingChain());
    }

    @Test
    void 未配置令牌时直接放行() throws Exception {
        withToken("");
        doFilter("/api/chat", null, null);
        assertThat(chainInvoked).isTrue();
    }

    @Test
    void 开启鉴权后缺失令牌返回401() throws Exception {
        withToken("s3cret");
        doFilter("/api/chat", null, null);
        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 令牌错误返回401() throws Exception {
        withToken("s3cret");
        doFilter("/api/chat", "wrong", null);
        assertThat(chainInvoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void 正确令牌通过请求头放行() throws Exception {
        withToken("s3cret");
        doFilter("/api/chat", "s3cret", null);
        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 支持Bearer形式的Authorization头() throws Exception {
        withToken("s3cret");
        doFilter("/api/chat", null, "Bearer s3cret");
        assertThat(chainInvoked).isTrue();
    }

    @Test
    void 开启鉴权时非api路径仍然放行() throws Exception {
        withToken("s3cret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setRequestURI("/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void 配置端点在开启鉴权时也必须免鉴权() throws Exception {
        // 前端要先问"是否需要令牌"才能发起带令牌的请求；
        // 若这个端点也被拦，界面就永远拿不到配置、无法引导用户输入令牌
        withToken("s3cret");
        doFilter("/api/config", null, null);
        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 白名单只放行配置端点不波及其他api() throws Exception {
        withToken("s3cret");
        doFilter("/api/chat", null, null);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    void 拒绝响应体是JSON且不含令牌值() throws Exception {
        withToken("s3cret");
        doFilter("/api/upload", "guessed", null);
        String body = response.getContentAsString();
        assertThat(body).contains("401").doesNotContain("s3cret");
        assertThat(response.getContentType()).contains("application/json");
    }
}

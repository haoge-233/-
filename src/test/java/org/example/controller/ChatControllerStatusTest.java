package org.example.controller;

import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 非流式接口的 HTTP 状态码语义
 *
 * <p>此前所有失败都返回 HTTP 200 + success:false，监控、网关和客户端重试逻辑
 * 都无法区分"正常响应"与"上游炸了"。这里锁住正确的映射。</p>
 */
class ChatControllerStatusTest {

    private MockMvc mockMvc;
    private SessionStore sessionStore;

    @BeforeEach
    void setUp() {
        sessionStore = new SessionStore(50);

        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "aiOpsService", mock(AiOpsService.class));
        ReflectionTestUtils.setField(controller, "chatService", mock(ChatService.class));
        ReflectionTestUtils.setField(controller, "sessionStore", sessionStore);
        ReflectionTestUtils.setField(controller, "prometheusMock", true);
        ReflectionTestUtils.setField(controller, "clsMock", true);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 空问题返回400而不是200() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"s1\",\"Question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("问题内容不能为空"))
                // 兼容既有前端：data.errorMessage 仍然可用
                .andExpect(jsonPath("$.data.errorMessage").value("问题内容不能为空"));
    }

    @Test
    void 清空不存在的会话返回404() throws Exception {
        mockMvc.perform(post("/api/chat/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void 清空会话缺少Id返回400() throws Exception {
        mockMvc.perform(post("/api/chat/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 清空已存在的会话返回200() throws Exception {
        sessionStore.getOrCreate("live").addMessage("q", "a");

        mockMvc.perform(post("/api/chat/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"live\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 查询不存在的会话信息返回404() throws Exception {
        mockMvc.perform(get("/api/chat/session/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 上游异常按分类映射状态码() throws Exception {
        ChatService failing = mock(ChatService.class);
        when(failing.createStandardChatModel()).thenThrow(new RuntimeException("InvalidApiKey"));

        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "aiOpsService", mock(AiOpsService.class));
        ReflectionTestUtils.setField(controller, "chatService", failing);
        ReflectionTestUtils.setField(controller, "sessionStore", sessionStore);
        ReflectionTestUtils.setField(controller, "prometheusMock", true);
        ReflectionTestUtils.setField(controller, "clsMock", true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 502 而不是 401：401 保留给"调用方没带访问令牌"
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Id\":\"s2\",\"Question\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502));
    }

    @Test
    void 会话被LRU淘汰后查询返回404() throws Exception {
        SessionStore tiny = new SessionStore(1);
        tiny.getOrCreate("old").addMessage("q", "a");
        tiny.getOrCreate("new").addMessage("q", "a");

        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "aiOpsService", mock(AiOpsService.class));
        ReflectionTestUtils.setField(controller, "chatService", mock(ChatService.class));
        ReflectionTestUtils.setField(controller, "sessionStore", tiny);
        ReflectionTestUtils.setField(controller, "prometheusMock", true);
        ReflectionTestUtils.setField(controller, "clsMock", true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        org.assertj.core.api.Assertions.assertThat(tiny.find("old")).isNull();
        mvc.perform(get("/api/chat/session/old")).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/session/new")).andExpect(status().isOk());
    }
}

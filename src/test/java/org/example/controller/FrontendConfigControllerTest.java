package org.example.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端配置端点：只回答"要不要带令牌"，且不得泄露任何密钥
 */
class FrontendConfigControllerTest {

    private MockMvc mockMvcWithToken(String token) {
        FrontendConfigController controller = new FrontendConfigController();
        ReflectionTestUtils.setField(controller, "authToken", token);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 未配置令牌时authRequired为false() throws Exception {
        mockMvcWithToken("")
                .perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authRequired").value(false))
                .andExpect(jsonPath("$.tokenStorageKey").value("superbizagent.apiToken"));
    }

    @Test
    void 配置了令牌时authRequired为true() throws Exception {
        mockMvcWithToken("s3cret-token")
                .perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authRequired").value(true));
    }

    @Test
    void 响应体不含令牌明文() throws Exception {
        String body = mockMvcWithToken("s3cret-token")
                .perform(get("/api/config"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("s3cret-token");
    }
}

package org.example.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端启动配置
 *
 * <p>该端点刻意不设鉴权：前端需要先知道要不要带令牌才能发起带令牌的请求。
 * 它只暴露"是否需要鉴权"这一个布尔值，不含任何密钥或内部信息。</p>
 */
@RestController
@RequestMapping("/api")
public class FrontendConfigController {

    @Value("${server.auth.token:}")
    private String authToken;

    @GetMapping("/config")
    public Map<String, Object> config() {
        boolean authRequired = authToken != null && !authToken.isBlank();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authRequired", authRequired);
        body.put("tokenStorageKey", "superbizagent.apiToken");
        return body;
    }
}

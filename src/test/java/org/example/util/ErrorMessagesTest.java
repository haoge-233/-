package org.example.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误消息映射：把上游异常翻译成可读提示，且不把内部细节透传给用户
 */
class ErrorMessagesTest {

    @Test
    void 免费额度耗尽映射为充值提示() {
        String msg = ErrorMessages.toUserMessage(
                new RuntimeException("403 - AllocationQuota.FreeTierOnly: Free quota exhausted"));
        assertThat(msg).contains("免费额度").contains("百炼");
    }

    @Test
    void 密钥无效提示同时指出对话与向量两处配置() {
        String msg = ErrorMessages.toUserMessage(new RuntimeException("InvalidApiKey"));
        assertThat(msg).contains("chat-api.api-key").contains("dashscope.api.key");
    }

    @Test
    void 模型不存在提示指向运维模型配置项() {
        String msg = ErrorMessages.toUserMessage(new RuntimeException("Model not found"));
        assertThat(msg).contains("chat-api.model");
    }

    @Test
    void 限流提示不暴露内部异常文本() {
        String raw = "Throttling: Requests throttled, internal-token=abc123";
        String msg = ErrorMessages.toUserMessage(new RuntimeException(raw));
        assertThat(msg).contains("限流").doesNotContain("abc123");
    }

    @Test
    void Milvus不可达提示检查端口() {
        String msg = ErrorMessages.toUserMessage(new RuntimeException("connect to Milvus failed"));
        assertThat(msg).contains("19530");
    }

    @Test
    void 未知异常返回通用提示且不为null() {
        assertThat(ErrorMessages.toUserMessage(new RuntimeException("完全没见过的错误")))
                .isEqualTo("服务暂时不可用，请稍后重试。如果问题持续存在，请联系管理员。");
        assertThat(ErrorMessages.toUserMessage(null)).isNotNull();
    }

    @Test
    void 会沿异常链向上查找根因() {
        Exception root = new IllegalStateException("Connection refused");
        Exception wrapped = new RuntimeException("外层包装", root);
        assertThat(ErrorMessages.toUserMessage(wrapped)).contains("网络");
    }

    // ==================== HTTP 状态码映射 ====================

    @Test
    void 额度用尽映射为429可稍后重试() {
        assertThat(ErrorMessages.toHttpStatus(
                new RuntimeException("403 AllocationQuota.FreeTierOnly: Free quota exhausted"))).isEqualTo(429);
    }

    @Test
    void 上游密钥失效绝不能映射为401() {
        // 401 在本服务中专指"调用方没带访问令牌"。
        // 若上游密钥问题也返回 401，前端 apiFetch 会误判为需要重新输入令牌，
        // 把服务故障伪装成登录问题，排查方向会被彻底带偏。
        int status = ErrorMessages.toHttpStatus(new RuntimeException("InvalidApiKey"));

        assertThat(status).isNotEqualTo(401);
        assertThat(status).isNotEqualTo(403);
        assertThat(status).isEqualTo(502);
    }

    @Test
    void 模型无权限映射为502() {
        assertThat(ErrorMessages.toHttpStatus(
                new RuntimeException("403 Model.AccessDenied model access denied"))).isEqualTo(502);
    }

    @Test
    void 限流映射为429() {
        assertThat(ErrorMessages.toHttpStatus(new RuntimeException("Throttling: Rate limit"))).isEqualTo(429);
    }

    @Test
    void 超时映射为504() {
        assertThat(ErrorMessages.toHttpStatus(
                new RuntimeException("java.net.SocketTimeoutException: read timed out"))).isEqualTo(504);
    }

    @Test
    void 向量库故障映射为503() {
        assertThat(ErrorMessages.toHttpStatus(new RuntimeException("connect to Milvus failed"))).isEqualTo(503);
    }

    @Test
    void 未知错误与null都回落500且消息与状态同源() {
        assertThat(ErrorMessages.toHttpStatus(null)).isEqualTo(500);
        assertThat(ErrorMessages.toHttpStatus(new RuntimeException("完全没见过的错误"))).isEqualTo(500);

        RuntimeException e = new RuntimeException("InvalidApiKey");
        assertThat(ErrorMessages.toUserMessage(e)).contains("API Key");
    }
}

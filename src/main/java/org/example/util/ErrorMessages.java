package org.example.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户友好错误消息工具类
 *
 * <p>将底层异常（上游模型 API 错误、网络异常等）映射为面向用户的可读提示，
 * 以及配套的 HTTP 状态码，避免把原始异常堆栈或 API 返回的 JSON 直接透传给前端。</p>
 *
 * <p>详细错误信息仍通过日志记录，便于排查问题。</p>
 */
public final class ErrorMessages {

    private static final Logger logger = LoggerFactory.getLogger(ErrorMessages.class);

    /** 无法识别的通用错误提示 */
    private static final String GENERIC_ERROR =
            "服务暂时不可用，请稍后重试。如果问题持续存在，请联系管理员。";

    private ErrorMessages() {
        // 工具类，禁止实例化
    }

    /**
     * 将异常转换为面向用户的友好提示
     *
     * @param e 原始异常（可为 null）
     * @return 用户可读的错误消息，永不为 null
     */
    public static String toUserMessage(Throwable e) {
        return classify(e).userMessage();
    }

    /**
     * 将异常映射为 HTTP 状态码，与 {@link #toUserMessage} 出自同一次分类，避免两处判断漂移。
     *
     * <p>刻意不使用 401/403：这两个状态在本服务中专指"调用方没带访问令牌"。
     * 若把上游密钥失效也映射成 401，前端会把服务故障误判成需要重新输入令牌。</p>
     *
     * <ul>
     *   <li>429 —— 限流或额度用尽，调用方可稍后重试</li>
     *   <li>504 —— 上游网络超时</li>
     *   <li>503 —— 依赖的向量库不可用</li>
     *   <li>502 —— 上游模型服务返回错误（密钥、模型名、配额等）</li>
     *   <li>500 —— 其他未知错误</li>
     * </ul>
     */
    public static int toHttpStatus(Throwable e) {
        return classify(e).status();
    }

    /**
     * 分类结果：HTTP 状态码 + 用户可读消息
     */
    public record Classified(int status, String userMessage) {
    }

    private static Classified classify(Throwable e) {
        if (e == null) {
            return new Classified(500, GENERIC_ERROR);
        }

        // 拼接完整的异常链信息，用于识别错误类型
        String raw = buildRawMessage(e);
        if (raw == null || raw.isBlank()) {
            return new Classified(500, GENERIC_ERROR);
        }

        // 记录详细错误到日志（保留排查信息）
        logger.warn("转换用户错误提示 - 原始异常: {}", raw);

        // 1. 免费额度耗尽（最常见）
        if (containsAny(raw,
                "AllocationQuota.FreeTierOnly",
                "Free quota exhausted",
                "免费额度",
                "quota exhausted")) {
            return new Classified(429,
                    "阿里云百炼 API 免费额度已用尽。请到控制台充值，或在「模型广场」中关闭「仅使用免费额度」模式后重试。");
        }

        // 2. API Key 无效 / 缺失 / 无模型权限
        if (containsAny(raw,
                "InvalidApiKey",
                "Invalid API Key",
                "ApiKey.Invalid",
                "Model.AccessDenied",
                "Unauthorized",
                "401")) {
            return new Classified(502,
                    "API Key 无效或无模型权限。对话模型请检查 chat-api.api-key，"
                            + "向量化模型请检查 dashscope.api.key（须属于已开通该模型的业务空间）。");
        }

        // 3. 请求被限流
        if (containsAny(raw,
                "Throttling",
                "Rate limit",
                "Requests throttled",
                "429",
                "Too Many Requests")) {
            return new Classified(429, "请求过于频繁，已被限流。请稍等几秒后再试。");
        }

        // 4. 模型不存在 / 不可用
        if (containsAny(raw,
                "ModelNotFound",
                "Model.NotExist",
                "model not found",
                "ModelNotExist",
                "模型不存在")) {
            return new Classified(502,
                    "指定的 AI 模型不可用，请检查 chat-api.model 配置是否正确（可用模型可访问 <base-url>/v1/models 查询）。");
        }

        // 5. 网络超时 / 连接失败
        if (containsAny(raw,
                "SocketTimeoutException",
                "ConnectException",
                "Connection refused",
                "timed out",
                "timeout",
                "504",
                "deadline exceeded")) {
            return new Classified(504, "上游模型服务网络连接超时，请检查网络后重试。");
        }

        // 6. Milvus 向量库异常
        if (containsAny(raw,
                "Milvus",
                "milvus",
                "DEADLINE_EXCEEDED")) {
            return new Classified(503, "向量数据库连接异常，请确认 Milvus 服务（端口 19530）是否正常运行。");
        }

        // 其他未知异常：返回通用提示（不暴露内部细节）
        return new Classified(500, GENERIC_ERROR);
    }

    /**
     * 拼接完整异常链消息
     */
    private static String buildRawMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 5) {
            if (sb.length() > 0) {
                sb.append(" | caused by: ");
            }
            sb.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage() != null ? current.getMessage() : "");
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 判断原始消息是否包含任意关键字（忽略大小写）
     */
    private static boolean containsAny(String raw, String... keywords) {
        String lower = raw.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}

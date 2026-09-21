package org.example.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)  // MCP 禁用时为 null
    private ToolCallbackProvider tools;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Value("${chat-api.base-url}")
    private String chatApiBaseUrl;

    @Value("${chat-api.api-key}")
    private String chatApiKey;

    @Value("${chat-api.model}")
    private String chatApiModel;

    @Value("${chat-api.ops-model:${chat-api.model}}")
    private String chatApiOpsModel;

    private volatile OpenAiApi chatApi;

    @PostConstruct
    public void validateModelConfig() {
        logger.info("对话模型: {} | 运维编排模型: {}", chatApiModel, chatApiOpsModel);
        if (chatApiOpsModel.equals(chatApiModel)) {
            logger.warn("chat-api.ops-model 未显式配置，运维编排将复用对话模型 {}。"
                    + "/api/ai_ops 走阻塞式调用且生成长报告，多数模型会在网关约 60s 的非流式超时处返回 504，"
                    + "建议显式指定一个高速模型（如 deepseek-v4-flash）。", chatApiModel);
        }
    }

    /**
     * 获取 OpenAI 兼容网关的 API 客户端（懒加载，构建后复用）
     * 复用 restClientBuilder 以继承超时配置，否则长输出请求会走默认短超时
     */
    private OpenAiApi chatApi() {
        OpenAiApi current = chatApi;
        if (current == null) {
            synchronized (this) {
                current = chatApi;
                if (current == null) {
                    current = OpenAiApi.builder()
                            .baseUrl(chatApiBaseUrl)
                            .apiKey(chatApiKey)
                            .restClientBuilder(restClientBuilder)
                            .build();
                    chatApi = current;
                }
            }
        }
        return current;
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public ChatModel createChatModel(double temperature, int maxToken, double topP) {
        return createChatModel(chatApiModel, temperature, maxToken, topP);
    }

    /**
     * 创建指定模型的 ChatModel
     */
    public ChatModel createChatModel(String model, double temperature, int maxToken, double topP) {
        logger.info("创建 ChatModel - provider: {}, model: {}", chatApiBaseUrl, model);
        return OpenAiChatModel.builder()
                .openAiApi(chatApi())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxToken)
                        .topP(topP)
                        .build())
                .build();
    }

    /**
     * 创建 AIOps 多 Agent 编排专用 ChatModel
     */
    public ChatModel createOpsChatModel() {
        return createChatModel(chatApiOpsModel, 0.3, 6000, 0.9);
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public ChatModel createStandardChatModel() {
        return createChatModel(0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n\n");
        
        // 添加历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        if (tools == null) {
            return new ToolCallback[0];
        }
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = getToolCallbacks();
        if (toolCallbacks.length == 0) {
            logger.info("MCP 已禁用，没有 MCP 工具");
            return;
        }
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(ChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer != null ? answer.length() : 0);

        // 检查是否返回了 API 错误（部分模型会把错误作为文本返回而非抛异常）
        if (answer != null && answer.startsWith("Exception:")) {
            logger.warn("检测到模型 API 错误响应: {}", answer);
            throw new RuntimeException("模型 API 错误: " + answer);
        }

        return answer;
    }
}

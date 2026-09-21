package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.session.SessionStore;
import org.example.util.ErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired(required = false)  // MCP 禁用时为 null
    private ToolCallbackProvider tools;

    @Value("${prometheus.mock-enabled:true}")
    private boolean prometheusMock;

    @Value("${cls.mock-enabled:true}")
    private boolean clsMock;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    @Autowired
    private SessionStore sessionStore;

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return chatFailure(HttpStatus.BAD_REQUEST.value(), "问题内容不能为空");
            }

            // 获取或创建会话
            SessionStore.SessionInfo session = getOrCreateSession(request.getId());
            
            // 获取历史消息
            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            // 创建 ChatModel
            ChatModel chatModel = chatService.createStandardChatModel();

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(history);
            
            // 创建 ReactAgent
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());
            
            // 更新会话历史
            session.addMessage(request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                request.getId(), session.getMessagePairCount());
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            // 状态码与提示文案出自同一次分类，便于监控与客户端重试策略识别
            int status = ErrorMessages.toHttpStatus(e);
            return chatFailure(status, ErrorMessages.toUserMessage(e));
        }
    }

    /**
     * 构造带正确 HTTP 状态码的失败响应。
     * data 仍填 ChatResponse.error，保持既有前端读取 data.errorMessage 的契约不变。
     */
    private static ResponseEntity<ApiResponse<ChatResponse>> chatFailure(int status, String message) {
        ApiResponse<ChatResponse> body = new ApiResponse<>();
        body.setCode(status);
        body.setMessage(message);
        body.setData(ChatResponse.error(message));
        return ResponseEntity.status(status).body(body);
    }

    private static <T> ResponseEntity<ApiResponse<T>> failure(int status, String message) {
        ApiResponse<T> body = new ApiResponse<>();
        body.setCode(status);
        body.setMessage(message);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return failure(HttpStatus.BAD_REQUEST.value(), "会话ID不能为空");
            }

            SessionStore.SessionInfo session = sessionStore.find(request.getId());
            if (session != null) {
                session.clearHistory();
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                // 会话可能已被 LRU 淘汰，用 404 而不是 200，便于调用方区分"没这个会话"
                return failure(HttpStatus.NOT_FOUND.value(), "会话不存在");
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return failure(ErrorMessages.toHttpStatus(e), ErrorMessages.toUserMessage(e));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                SessionStore.SessionInfo session = getOrCreateSession(request.getId());
                
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 创建 ChatModel
                ChatModel chatModel = chatService.createStandardChatModel();

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history);
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(ErrorMessages.toUserMessage(error)), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                request.getId(), fullAnswer.length());

                            // 检查是否返回了 API 错误（部分模型会把错误作为文本流返回）
                            if (!fullAnswer.isEmpty() && fullAnswer.trim().startsWith("Exception:")) {
                                logger.warn("流式对话检测到模型 API 错误响应");
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.error(ErrorMessages.toUserMessage(
                                                new RuntimeException(fullAnswer))), MediaType.APPLICATION_JSON));
                            } else {
                                // 正常情况：更新会话历史
                                session.addMessage(request.getQuestion(), fullAnswer);
                                logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                                    request.getId(), session.getMessagePairCount());

                                // 发送完成标记
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            }
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(ErrorMessages.toUserMessage(e)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        // 超时后 Tomcat 会直接结束响应，客户端只会看到流凭空断掉。
        // 这里补一条明确的错误事件，避免"卡十分钟然后静默失败"。
        emitter.onTimeout(() -> {
            logger.error("AI Ops 流程达到 emitter 超时上限，主动结束并通知客户端");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error(
                        "运维分析在 10 分钟内未返回结果，已主动结束。可查看 server.log 中 ai_ops_supervisor 的路由轮数，或调低 chat-api.ops-model 的输出长度。")));
                emitter.complete();
            } catch (Exception e) {
                logger.warn("超时后发送错误事件失败: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                ChatModel chatModel = chatService.createOpsChatModel();

                ToolCallback[] toolCallbacks = (tools != null) ? tools.getToolCallbacks() : new ToolCallback[0];

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                // 模拟数据源必须显式声明：报告文本看起来和生产数据无异，
                // 一旦被当作真实故障结论会直接误导线上处置。
                if (prometheusMock || clsMock) {
                    StringBuilder src = new StringBuilder();
                    if (prometheusMock) src.append("Prometheus 告警/指标");
                    if (clsMock) src.append(src.length() > 0 ? " 与腾讯云日志" : "腾讯云日志");
                    emitter.send(SseEmitter.event().name("message").data(SseMessage.content(
                            "\n⚠️ 数据源为模拟模式：" + src + "均来自内置夹具，并非真实生产监控数据。\n"
                                    + "本报告仅用于验证 Agent 编排链路，不可作为线上故障处置依据。\n"
                                    + "接入真实数据源请设置 PROMETHEUS_MOCK_ENABLED=false / CLS_MOCK_ENABLED=false。\n\n")));
                }
                
                // 以流式驱动多 Agent 编排：底层模型调用改为 SSE，从而绕开网关约 60s 的
                // 非流式空闲超时（实测同样的长生成在阻塞式下 504、流式下可跑数百秒）。
                // 注意只把"节点变化"作为进度下发——单次编排会产生数千个 token 级事件，
                // 原样转发会淹没前端；报告内容一律从最终状态里取，不从 token 流拼接。
                AtomicReference<OverAllState> finalState = new AtomicReference<>();
                AtomicReference<String> lastLabel = new AtomicReference<>("");

                aiOpsService.streamAiOpsAnalysis(chatModel, toolCallbacks)
                        .doOnNext(output -> {
                            if (output.state() != null) {
                                finalState.set(output.state());
                            }
                            String node = output.node();
                            if (node == null) {
                                return;
                            }
                            // 按标签文本而非节点名去重：__START__/__END__/_AGENT_MODEL_ 会在
                            // 多轮子图之间反复出现，按节点名去重会把同一句提示重复推给前端
                            String label = aiOpsProgressLabel(node);
                            if (!label.equals(lastLabel.getAndSet(label))) {
                                try {
                                    emitter.send(SseEmitter.event().name("message")
                                            .data(SseMessage.content(label), MediaType.APPLICATION_JSON));
                                } catch (IOException e) {
                                    throw new IllegalStateException("发送 AI Ops 进度事件失败", e);
                                }
                            }
                        })
                        .blockLast();

                OverAllState state = finalState.get();
                if (state == null) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败：" + ErrorMessages.toUserMessage(e)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionStore.SessionInfo session = sessionStore.find(sessionId);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.getCreateTime());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return failure(HttpStatus.NOT_FOUND.value(), "会话不存在");
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return failure(ErrorMessages.toHttpStatus(e), ErrorMessages.toUserMessage(e));
        }
    }

    // ==================== 辅助方法 ====================

    private SessionStore.SessionInfo getOrCreateSession(String sessionId) {
        return sessionStore.getOrCreate(sessionId);
    }

    /**
     * 把图执行内部的节点名翻译成用户可读的进度提示。
     *
     * <p>两点约束：一是不能透出 __START__ / _AGENT_MODEL_ 这类内部名；二是标签必须诚实——
     * {@code _AGENT_MODEL_} 对 Planner 和 Executor 都会触发，无法据此判断是哪个 Agent，
     * 所以只能写成中性文案，否则会误导用户以为只有 Planner 在工作。</p>
     */
    static String aiOpsProgressLabel(String node) {
        return switch (node) {
            case "__START__" -> "▶ 启动多 Agent 编排...\n";
            case "_AGENT_MODEL_" -> "· 模型推理中...\n";
            case "_AGENT_TOOL_" -> "· 工具调用返回...\n";
            case "planner_agent" -> "· Planner 已接手...\n";
            case "executor_agent" -> "· Executor 已接手...\n";
            case "ai_ops_supervisor" -> "· Supervisor 正在决定下一步调度...\n";
            case "__END__" -> "· 本轮结束...\n";
            default -> "· " + node + "...\n";
        };
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}

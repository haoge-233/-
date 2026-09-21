package org.example.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AIOps 进度标签的可读性与诚实性
 *
 * <p>回归背景：初版实现把 {@code _AGENT_MODEL_} 标成"Planner 正在分析"，而该节点对
 * Planner 与 Executor 都会触发，属于对用户的事实性误导；同时按节点名去重导致
 * __START__/__END__ 在多轮子图间被重复推送。</p>
 */
class AiOpsProgressLabelTest {

    @Test
    void 不向用户泄漏内部节点名() {
        for (String node : new String[]{"__START__", "__END__", "_AGENT_MODEL_", "_AGENT_TOOL_", "ai_ops_supervisor"}) {
            String label = ChatController.aiOpsProgressLabel(node);
            assertThat(label)
                    .as("节点 %s 的标签不应原样透出内部名", node)
                    .doesNotContain("__")
                    .doesNotContain("_AGENT_")
                    .doesNotContain("ai_ops_supervisor");
        }
    }

    @Test
    void 模型推理节点标签保持中性不指名Agent() {
        // _AGENT_MODEL_ 对 Planner 和 Executor 都会触发，无法据此判断是哪个 Agent
        String label = ChatController.aiOpsProgressLabel("_AGENT_MODEL_");

        assertThat(label).doesNotContain("Planner").doesNotContain("Executor");
        assertThat(label).contains("模型");
    }

    @Test
    void 每个已知节点都有非空标签() {
        String[] nodes = {"__START__", "__END__", "_AGENT_MODEL_", "_AGENT_TOOL_",
                "planner_agent", "executor_agent", "ai_ops_supervisor"};
        for (String node : nodes) {
            assertThat(ChatController.aiOpsProgressLabel(node)).isNotBlank();
        }
    }

    @Test
    void planner与executor标签可区分() {
        assertThat(ChatController.aiOpsProgressLabel("planner_agent"))
                .isNotEqualTo(ChatController.aiOpsProgressLabel("executor_agent"));
    }

    @Test
    void 未知节点降级为通用提示而不抛异常() {
        assertThat(ChatController.aiOpsProgressLabel("some_future_node")).contains("some_future_node");
    }

    @Test
    void 同一节点重复调用返回相同标签以保证可去重() {
        // 前端按"标签文本"去重，因此标签必须是节点的纯函数
        assertThat(ChatController.aiOpsProgressLabel("_AGENT_MODEL_"))
                .isEqualTo(ChatController.aiOpsProgressLabel("_AGENT_MODEL_"));
    }
}

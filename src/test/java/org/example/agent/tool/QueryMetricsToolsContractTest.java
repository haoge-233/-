package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryMetricsTools 对真实 Prometheus /api/v1/alerts 响应的契约测试
 *
 * <p>样本 src/test/resources/prometheus/alerts-real.json 是从真实 Prometheus v2.55.1
 * 抓取的原样响应，不是手写 fixture —— 手写 fixture 只会固化作者对协议的误解。</p>
 */
class QueryMetricsToolsContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private byte[] responseBody;
    private int responseStatus = 200;

    @BeforeEach
    void startServer() throws Exception {
        responseBody = readAll(getClass().getClassLoader()
                .getResourceAsStream("prometheus/alerts-real.json"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/alerts", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private QueryMetricsTools realModeTool() {
        QueryMetricsTools tool = new QueryMetricsTools();
        ReflectionTestUtils.setField(tool, "prometheusBaseUrl", baseUrl);
        ReflectionTestUtils.setField(tool, "timeout", 5);
        ReflectionTestUtils.setField(tool, "mockEnabled", false);
        tool.init();
        return tool;
    }

    @Test
    void 真实响应可被完整解析并保留每条现场() throws Exception {
        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());

        assertThat(out.get("success").asBoolean()).isTrue();
        JsonNode alerts = out.get("alerts");

        // 真实样本含 6 条告警，其中 HighDiskUsage 在 3 个不同挂载点触发
        assertThat(alerts).hasSize(6);

        List<String> diskMounts = new ArrayList<>();
        for (JsonNode a : alerts) {
            assertThat(a.get("alert_name").asText()).isNotBlank();
            assertThat(a.get("state").asText()).isEqualTo("firing");
            // 时长不得为负，且 activeAt 的纳秒精度必须可解析
            assertThat(a.get("duration").asText()).doesNotStartWith("-");
            if (a.get("alert_name").asText().equals("HighDiskUsage")) {
                diskMounts.add(a.get("mountpoint").asText());
            }
        }
        assertThat(diskMounts).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void 同一告警的多个实例不会被压成一条() throws Exception {
        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());

        long distinctDiskIncidents = java.util.stream.StreamSupport
                .stream(out.get("alerts").spliterator(), false)
                .filter(a -> a.get("alert_name").asText().equals("HighDiskUsage"))
                .count();

        assertThat(distinctDiskIncidents)
                .as("回归：按 alertname 去重曾把 3 个挂载点压成 1 条")
                .isEqualTo(3);
    }

    @Test
    void Prometheus未来新增字段不会导致解析崩溃() throws Exception {
        // 用真实样本派生：给每条告警和顶层加上当前 DTO 未声明的字段
        JsonNode mutated = mapper.readTree(responseBody);
        ((JsonNode) mutated.get("data").get("alerts")).forEach(a -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) a).put("url", "http://example/alert");
            ((com.fasterxml.jackson.databind.node.ObjectNode) a).put("generatorURL", "http://example/rule");
        });
        ((com.fasterxml.jackson.databind.node.ObjectNode) mutated).put("warnings", mapper.createArrayNode());
        responseBody = mapper.writeValueAsString(mutated).getBytes(StandardCharsets.UTF_8);

        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());

        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("alerts")).hasSize(6);
    }

    @Test
    void HTTP成功但业务非success时给出明确原因() throws Exception {
        // 注意：这条分支要求 HTTP 状态是 2xx；HTTP 非 2xx 会先被 isSuccessful 拦下，走另一条降级路径
        responseBody = "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"invalid selector\"}"
                .getBytes(StandardCharsets.UTF_8);

        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());

        assertThat(out.get("success").asBoolean()).isFalse();
        assertThat(out.get("message").asText()).contains("非成功状态");
        assertThat(out.get("error").asText()).contains("invalid selector");
    }

    @Test
    void HTTP错误状态码时降级为错误响应而不是抛异常() throws Exception {
        responseBody = "{\"status\":\"error\",\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
        responseStatus = 400;

        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());

        assertThat(out.get("success").asBoolean()).isFalse();
        assertThat(out.get("message").asText()).contains("查询失败");
    }

    @Test
    void 连不上Prometheus时返回错误结构而非异常() throws Exception {
        QueryMetricsTools tool = new QueryMetricsTools();
        ReflectionTestUtils.setField(tool, "prometheusBaseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(tool, "timeout", 2);
        ReflectionTestUtils.setField(tool, "mockEnabled", false);
        tool.init();

        JsonNode out = mapper.readTree(tool.queryPrometheusAlerts());

        assertThat(out.get("success").asBoolean()).isFalse();
        assertThat(out.get("message").asText()).contains("查询失败");
    }

    @Test
    void 时钟偏移导致activeAt在未来时长按零处理() throws Exception {
        responseBody = ("{\"status\":\"success\",\"data\":{\"alerts\":[{" +
                "\"labels\":{\"alertname\":\"FutureAlert\",\"instance\":\"i1\"}," +
                "\"annotations\":{\"description\":\"d\"}," +
                "\"state\":\"firing\"," +
                "\"activeAt\":\"2099-01-01T00:00:00.123456789Z\"," +
                "\"value\":\"1\"}]}}").getBytes(StandardCharsets.UTF_8);

        JsonNode out = mapper.readTree(realModeTool().queryPrometheusAlerts());
        JsonNode alert = out.get("alerts").get(0);

        assertThat(alert.get("duration").asText()).doesNotStartWith("-");
        assertThat(alert.get("duration").asText()).isEqualTo("0s");
    }
}

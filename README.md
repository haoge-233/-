# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话管理**: 上下文维护、历史管理、自动清理
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | 1.1.0 | AI Agent 框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | ReactAgent / 多 Agent 编排 |
| TokenRhythm | - | 对话模型网关（OpenAI 兼容，基元律动） |
| DashScope SDK | 2.17.0 | 文本向量化（text-embedding-v4） |
| Milvus | 2.6.10 SDK / 2.5.10 服务端 | 向量数据库 |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   ├── RagService.java            # RAG 服务
│   │   └── Vector*.java               # 向量服务
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 文件管理

- `POST /api/upload` - 上传文件并自动向量化
- `GET /milvus/health` - Milvus 健康检查


## ⚙️ 核心配置

### application.yml

所有密钥与部署参数都从环境变量读取，配置文件里**不含明文密钥**：

```yaml
server:
  port: 9900
  address: ${SERVER_ADDRESS:127.0.0.1}   # 默认只监听回环地址
  auth:
    token: ${SERVER_AUTH_TOKEN:}         # 留空=不鉴权
  cors:
    allowed-origins: ${SERVER_CORS_ALLOWED_ORIGINS:...}

# 对话模型：基元律动 TokenRhythm（OpenAI 兼容网关）
chat-api:
  base-url: ${TOKENRHYTHM_BASE_URL:https://tokenrhythm.studio}
  api-key: ${TOKENRHYTHM_API_KEY:}
  model: ${TOKENRHYTHM_MODEL:glm-5.3}
  ops-model: ${TOKENRHYTHM_OPS_MODEL:deepseek-v4-flash}

# 向量模型：阿里云 DashScope（仅用于 embedding）
dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:}
  embedding:
    model: ${DASHSCOPE_EMBEDDING_MODEL:text-embedding-v4}

rag:
  top-k: 3          # 供文档检索工具使用

prometheus:
  mock-enabled: ${PROMETHEUS_MOCK_ENABLED:true}   # false 才接真实监控
cls:
  mock-enabled: ${CLS_MOCK_ENABLED:true}
```

### 环境变量

| 变量 | 必填 | 说明 |
|------|------|------|
| `TOKENRHYTHM_API_KEY` | 是 | 对话模型密钥 |
| `DASHSCOPE_API_KEY` | 是 | 向量模型密钥；**必须属于已开通 embedding 模型的业务空间**，否则返回 `Model.AccessDenied` |
| `SERVER_ADDRESS` | 否 | 默认 `127.0.0.1`；改 `0.0.0.0` 前务必同时设置鉴权令牌 |
| `SERVER_AUTH_TOKEN` | 否 | 设置后 `/api/**` 需带 `X-API-Token` 或 `Authorization: Bearer` |
| `PROMETHEUS_MOCK_ENABLED` / `CLS_MOCK_ENABLED` | 否 | 默认 `true`，即 AIOps 报告使用内置模拟数据 |

本地开发建议把上述变量写进 `local-env.sh`（已被 `.gitignore` 排除），不要写回 `application.yml`。


## 🚀 快速开始

### 1. 环境准备

```bash
# 复制模板填入自己的密钥（该文件已被 gitignore 排除）
cp local-env.sh.example local-env.sh
vim local-env.sh
source ./local-env.sh
```

### 2. 启动应用

方法一： 手动启动
```bash
1.先启动向量数据库
docker compose up -d -f vector-database.yml

2.启动服务
mvn clean install
mvn spring-boot:run
```

方法二：一键启动
```bash
make init  # 会自动启动向量数据库并上传运维文档到向量库
```


### 3. 接入真实 Prometheus（可选，推荐）

默认 `prometheus.mock-enabled=true`，此时 AIOps 报告里的指标与告警**全部来自内置模拟夹具**，
不是真实监控数据。仓库自带一套本地可观测栈用于验证真实链路：

```bash
# 需要 make；未安装时可直接用 docker 命令
docker compose -f observability.yml up -d      # Prometheus :9090 + node_exporter :9100
export PROMETHEUS_MOCK_ENABLED=false
# 然后重启服务
```

- 规则文件：`observability/alerts.yml`，告警名与 `aiops-docs` 中的类型对齐
  （HighCPUUsage / HighMemoryUsage / HighDiskUsage / SlowResponse / ServiceUnavailable）
- 阈值刻意设得极低，以便在开发机上真的进入 firing 状态；**生产必须按实际容量重设**
- 查看当前告警：`curl -s localhost:9090/api/v1/alerts`
- 关闭：`docker compose -f observability.yml down`

腾讯云日志（CLS）一侧仍需你自己的凭据，且需把 `spring.ai.mcp.client.enabled` 置为 true，
区域 ID 必须使用连字符格式（如 `ap-guangzhou`）。

### 4. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/milvus/health
```


## 📊 HTTP 状态码语义

非流式接口按真实结果返回状态码，响应体同时保留 `code` 与可读 `message`：

| 状态码 | 含义 | 调用方应对 |
|---|---|---|
| 200 | 成功 | — |
| 400 | 参数校验失败（问题为空、会话 ID 缺失） | 修正请求 |
| 401 | **仅表示调用方没带访问令牌** | 补 `X-API-Token` |
| 404 | 会话不存在（含被 LRU 淘汰） | 重新发起会话 |
| 429 | 上游限流或额度用尽 | 稍后重试 |
| 502 | 上游模型服务错误（密钥无效、无模型权限、模型名不存在） | 检查配置 |
| 503 | Milvus 向量库不可用 | 检查 Milvus（19530） |
| 504 | 上游网络超时 | 重试或降低 maxTokens |

> 上游故障刻意**不映射为 401/403**，否则前端会把服务异常误判成"需要重新输入令牌"。
> 该约束由单元测试锁住。

`/api/upload` 特别地：文件已落盘但向量化失败时返回 **500** 且 `data.indexed=false`、
`data.indexError` 给出原因——不再出现"返回 200 但知识库查不到"的静默丢数据。

## 🔁 持续集成

`.github/workflows/ci.yml` 在 push / PR 时执行：

1. **无凭据离线测试** —— 显式清空 `DASHSCOPE_API_KEY` / `TOKENRHYTHM_API_KEY` 后跑 `mvn test`。
   测试被设计为不依赖网络、Milvus 和任何密钥；一旦有人引入需要外部服务才能跑的测试，CI 会直接失败而不是静默跳过。
2. **打包**
3. **明文密钥守卫** —— 扫描源码与 jar 内的 `application.yml`，发现 `sk_tr_…` / `sk-ws-…` 形态的密钥即阻断构建（`local-env.sh` 与测试样本已排除）。

> 注意：本目录当前不是 git 仓库，需先 `git init` 并推送到 GitHub 后工作流才会生效。

##  测试

```bash
mvn test    # 68 个测试，全部离线运行
```

覆盖：会话 LRU 上界与并发、鉴权过滤器与免鉴权白名单、上传路径穿越与失败语义、
文档分片边界、错误消息与状态码分类、Prometheus 真实响应契约（样本取自真实抓取结果）。

---

**版本**: v1.1.0  
**作者**: chief  
**许可证**: MIT

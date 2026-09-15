# 技术方案说明

> 本文档描述 ad-review-analyst（AI 投流复盘分析师）的技术架构与关键设计决策，供后续开发维护参考。

## 1. 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 4.1.0 | Jakarta EE、内嵌 Tomcat 11 |
| AI 框架 | Spring AI 2.0.0 | ChatClient API、MCP Client、结构化输出 |
| 大模型 | 通义千问 qwen-max | 通过 DashScope OpenAI 兼容接口 |
| 数据接入 | 巨量千川 MCP Server | StreamableHttp 传输 |
| 序列化 | Jackson 3（tools.jackson） | Spring Boot 4 默认，内置 java.time 支持 |
| 前端 | Vue 3 + Vite + Element Plus + ECharts | ECharts 按需引入 |
| 部署 | Docker Compose + Nginx | 前后端双容器 |
| 构建 | Maven 3.9.11（含 Wrapper） | Java 21 |

## 2. 关键设计决策

### 2.1 千川数据获取与解析（QianchuanService）

- 通过 Spring AI `ChatClient` + MCP 工具回调让模型调用千川 MCP 获取报表。
- 使用 `BeanOutputConverter`（JSON Schema 结构化输出）将模型返回解析为 `List<AdCampaign>`，替代手动解析，保证字段映射正确。
- **降级策略**：MCP 工具不可用（未配置/为空/获取失败）时返回模拟数据；AI 调用异常时默认降级为模拟数据（`ad-review.fallback-mock-on-error` 可关闭），保证开发与演示可用性。

### 2.2 报告存储（ReportStorageService）

- 报告以 JSON 文件落盘（默认 `./data/reports`），`ObjectMapper` 由 Spring Boot 4 自动配置注入（Jackson 3，内置 java.time 支持，无需额外 jsr310 依赖）。
- 列表按报告生成时间 `generatedAt` 倒序分页（不依赖文件系统 mtime，避免写入时间精度导致的排序不稳定）。
- 报告 ID 仅允许字母数字，防路径穿越。

### 2.3 阈值配置

- 全部业务阈值（ROI/CPA/CTR/最低消耗门槛）通过 `application.yml` 的 `ad-review.*` 前缀配置，并由 `GET /api/config` 暴露给前端，前端动态渲染，无硬编码。

### 2.4 接口鉴权

- 自定义 `SecurityInterceptor`，配置 `ad-review.api-key` 后启用 Bearer Token 鉴权；未配置则跳过（开发模式）。
- 注意：自定义配置不使用 `spring.security.*` 前缀，避免与 Spring Security 自动配置语义冲突。

## 3. 本次完善记录（2026-09-15）

1. 实现千川数据解析（原 `parseCampaignData` 返回空列表）。
2. 修复 Dockerfile：补充 Maven Wrapper（mvnw/mvnw.cmd/.mvn）。
3. 修复 Spring Boot 4 / Jackson 3 兼容：报告存储序列化 LocalDate 崩溃。
4. 自定义报告改为区间汇总；日期范围参数校验；报告 ID 防路径穿越。
5. 阈值配置化 + 新增 `/api/config` 接口。
6. 报告历史分页（返回 total）+ 删除接口 `DELETE /api/report/{id}`。
7. 自然语言查询意图识别（intent）。
8. 数据拉取失败降级模拟数据（可配置）。
9. 前端：ECharts 图表（消耗 TOP10 / ROI 对比）、分页/删除/空状态、预算分配建议展示、动态阈值。
10. 测试：AnalysisService（4 例）+ ReportStorageService（8 例），共 12 例全绿。

## 4. 开发约定

- 每次代码改动后必须创建 git commit（见 `agents.md`）。
- 每次代码改动后必须编写或更新相关测试，交付前确保全部通过。
- 新增业务阈值统一放 `ad-review.*` 配置前缀。

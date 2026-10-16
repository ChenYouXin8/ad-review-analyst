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

### 2.1 三级数据源路由与取数缓存（QianchuanService）

取数按以下优先级路由，并始终携带来源标识（`DataSourceType`）向上透传：

1. **LOCAL_IMPORT**：`CampaignRepository` 在 `[startDate, endDate]` 内有数据即优先命中，不消耗 LLM Token。
2. **MCP_LIVE**：本地无数据且千川 MCP 工具可用时，通过 `ChatClient` + MCP 工具取数，用 `BeanOutputConverter`（JSON Schema）解析为 `List<AdCampaign>`，避免手工解析字段错位。
3. **MOCK_FALLBACK**：前两者均不可用时返回**幂等**模拟数据（固定种子 `startDate.toEpochDay() + campaignId`，保证同一日期多次查询结果稳定）。`ad-review.fallback-mock-on-error=false` 可关闭降级。

**短期内存缓存**：以 `startDate_endDate` 为 Key、`ConcurrentHashMap` + 过期时间戳实现 TTL 缓存（默认 300 秒），避免仪表盘高频刷新打穿下游取数链路；`evictCache()` 供 CSV 导入后主动失效。

### 2.2 CSV 报表导入（CsvImportService）

- 使用 hutool `CsvUtil` 解析上传文件，UTF-8（含 BOM）/ GBK 自动识别。
- 表头归一化（小写 + 去除非字母数字）后做中英文别名映射（如「消耗/花费/消费/cost」）。
- 缺失的衍生指标（CTR/CPA/CVR/CPC/CPM/ROI）按基础字段自动计算并处理除零，结果四舍五入保留 4 位。
- 通过 `CampaignRepository.upsertAll` 按 `(campaign_id, stat_date)` 唯一键批量 Upsert，写入后调用 `qianchuanService.evictCache()`。

### 2.3 报告与计划数据持久化（ReportStorageService / CampaignRepository）

- **H2 文件数据库**（`MODE=MySQL`，`./data/db/adreview`），`spring.sql.init` 加载 `schema.sql` 建表；测试用内存模式。
- 报告历史使用 `JdbcTemplate` **SQL 分页**（`LIMIT/OFFSET` + `COUNT`），彻底替代早期 JSON 文件全量遍历扫描，避免随历史增长的内存与 IO 放大。
- 报告 ID 仅允许字母数字，防路径穿越。

### 2.4 单次强 Schema 结构化 AI 调用（ReportService）

- 报告生成先**仅取数一次**，随后在内存聚合 `AccountSummary`、检测异常、计算 TOP/衰退计划与预算建议，**消除重复取数**。
- 所有 LLM 调用统一走 `BeanOutputConverter` 强 JSON Schema 结构化输出：报告洞察（概览/深度分析/行动建议）由**单次**调用一次性产出，替代早期多次串行调用，显著降低延迟与 Token。
- AI 调用失败时记录含耗时的 error 日志并降级为**确定性规则结论**，同时把 `degraded=true` 透传到报告，绝不静默吞异常。

### 2.5 全套 6 种异常检测（AnalysisService）

`detectAbnormalities` 覆盖：`LOW_ROI`、`HIGH_CPA`、`LOW_CTR`、`BUDGET_OVERSPEND`、`CREATIVE_DECAY`、`SUDDEN_DROP`。
- `BUDGET_OVERSPEND`：消耗 > 异常门槛×2 且 > 全场平均消耗×`overspendRatioThreshold` 且 ROI 低于阈值。
- `CREATIVE_DECAY`：展示量 ≥ `ctrMinImpressions` 且 CTR < 阈值×1.2 且 CPC > 全场平均 CPC×`creativeDecayCpcMultiplier`。
- `SUDDEN_DROP`：同一 `campaignId` 多日记录时，最新一日 ROI 低于历史均值 60% 且消耗达标；单日聚合数据时，退化为「消耗 ≥ `decliningCampaignMinCost` 且零转化」的大额空耗检测。

### 2.6 阈值配置（ReviewConfig）

- `ReviewConfig` 为 `@ConfigurationProperties(prefix="ad-review")` 的 POJO，集中 11 个可配置阈值，Java 侧默认值与历史硬编码一致，保证向后兼容，**彻底消除魔法数字**。
- 配置由 `GET /api/config` 暴露给前端动态渲染。

### 2.7 请求可观测性（TraceLogFilter）

- `OncePerRequestFilter`（最高优先级）为每个请求生成 8 位短 `traceId`：写入 `MDC.put("traceId", ...)`、响应头 `X-Trace-Id`。
- 日志 pattern 含 `[%X{traceId:-}]`，使业务日志与 AI 调用日志可通过同一 `traceId` 串联；请求结束打印 `[URI, Method, Status, 耗时ms]` 访问日志并清理 MDC。

### 2.8 接口鉴权与跨域（SecurityInterceptor / CorsConfig）

- `SecurityInterceptor`：配置 `ad-review.api-key` 后启用 Bearer Token 鉴权，未配置则跳过（开发模式）；令牌比较使用 `MessageDigest.isEqual` **常量时间比较**，防计时攻击。自定义配置不使用 `spring.security.*` 前缀，避免与 Spring Security 自动配置语义冲突。
- `CorsConfig`：允许来源从 `ad-review.cors.allowed-origins` 读取（默认 `*` 兼容开发，生产可通过环境变量 `CORS_ALLOWED_ORIGINS` 收紧）。

## 3. 完善记录

### Sprint 1-3（核心链路 / 持久化 / 问答 Agent）

1. 实现千川数据解析与结构化输出；修复 Spring Boot 4 / Jackson 3 兼容（`LocalDate` 序列化）。
2. 阈值配置化 + 新增 `/api/config`；报告历史分页（返回 total）与删除接口。
3. 引入 `DataSourceType` 标识数据源与降级状态，合并 LLM 调用为单次结构化调用，消除重复取数。
4. 引入千川标准 CSV 导入与 H2 数据库持久化，`ReportStorageService` 以 `JdbcTemplate` 重构。
5. 基于 Spring AI `@Tool` 重构自然语言问答 Agent（请求级有状态工具箱 `AdDataAnalyticsTools`）。

### Sprint 4（收官：算法 / 配置 / 缓存 / 可观测性 / 安全）

1. `ReviewConfig` 由 record 升级为 `@ConfigurationProperties` POJO，新增 7 个阈值字段，消除 `AnalysisService` / `ReportService` 中的魔法数字（200/500/3/10000/1.1）。
2. 补齐 `BUDGET_OVERSPEND`、`CREATIVE_DECAY`、`SUDDEN_DROP` 三种异常检测，完整落地 6 种异常类型。
3. `QianchuanService` 引入 TTL 内存缓存与 `evictCache()`；`CsvImportService` 导入后主动失效。
4. 新增 `TraceLogFilter`（traceId + MDC + `X-Trace-Id` + 访问耗时日志）；`CorsConfig` 可配置化；`SecurityInterceptor` 改用常量时间比较。
5. 新增/扩展单元测试：异常检测 6 例、取数缓存 4 例、CSV 导入清缓存 1 例，`./mvnw.cmd test` 共 45 例全绿。

## 4. 开发约定

- 每次代码改动后必须创建 git commit（见 `agents.md`）。
- 每次代码改动后必须编写或更新相关测试，交付前确保全部通过（Maven 命令统一使用 `./mvnw.cmd`）。
- 新增业务阈值统一放 `ad-review.*` 配置前缀，并同步更新 `ReviewConfig` 与 `application.yml`。
- 所有 LLM 调用必须使用强 JSON Schema 结构化输出，禁止自由文本无校验调用。

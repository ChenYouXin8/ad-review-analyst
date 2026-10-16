# AI 投流复盘分析师

基于 Spring Boot + Spring AI + 巨量千川 MCP 的智能投放复盘系统。自动拉取千川投放数据，通过 AI 大模型分析生成结构化复盘报告，支持异常诊断、策略建议和自然语言查询。

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-green.svg)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 功能特性

- **自动复盘报告**：支持日报、周报、自定义时间段报告，一键生成
- **AI 深度分析**：通义千问大模型**单次强 Schema 结构化调用**一次性产出概览 / 深度分析 / 行动建议
- **全套 6 种异常智能诊断**：ROI 过低、CPA 过高、CTR 偏低、预算超支、素材衰退、效果突降，阈值全部可配置
- **策略建议生成**：基于数据给出预算分配、出价优化、素材优化建议
- **预算分配建议**：按 ROI 加权自动给出各计划的预算调整比例与原因
- **自然语言查询**：用大白话问数据，如"今天哪个计划跑废了"；基于 Spring AI `@Tool` 让大模型**按需调用本地数据工具**获取真实数据后作答
- **每日定时任务**：每天早上 9 点自动生成前一天日报
- **报告历史管理**：报告持久化到 **H2 数据库**，`JdbcTemplate` SQL 分页，支持回溯查看与删除
- **三级数据源路由 + 可信水印**：本地 CSV 导入 > 千川 MCP 实时拉取 > 幂等 Mock 兜底，来源与降级状态透传到前端醒目提示
- **请求可观测性**：每个请求生成短 `traceId` 写入 MDC 与响应头 `X-Trace-Id`，串联全部业务日志与 AI 调用日志
- **取数短时缓存**：按日期段内存缓存取数结果（TTL 5 分钟），避免仪表盘高频刷新打穿下游链路

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.1.0 + Spring AI 2.0.0 |
| 大模型 | 通义千问 qwen-max（`BeanOutputConverter` 强 Schema 结构化输出 / `@Tool` Tool Calling） |
| 数据接入 | 三级路由：千川标准 CSV 导入 + 巨量千川官方 MCP Server（StreamableHttp） + 幂等 Mock 兜底 |
| 持久化 | H2 文件数据库（`MODE=MySQL`）+ `JdbcTemplate` SQL 分页 |
| 定时任务 | Spring @Scheduled |
| 前端 | Vue 3 + Vite + Element Plus + ECharts（按需引入） |
| 部署 | Docker Compose |

## 快速开始

### 方式一：本地开发

```bash
# 1. 克隆项目
git clone https://github.com/ChenYouXin8/ad-review-analyst.git
cd ad-review-analyst

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 AI_DASHSCOPE_API_KEY

# 3. 配置 MCP（可选，用于真实千川数据）
cp src/main/resources/mcp-servers.example.json src/main/resources/mcp-servers.json
# 编辑 mcp-servers.json，填入 QIANCHUAN_ACCESS_TOKEN

# 4. 启动后端（项目自带 Maven Wrapper，无需本地安装 Maven）
./mvnw spring-boot:run
# Windows: mvnw.cmd spring-boot:run

# 5. 启动前端
cd frontend
npm install
npm run dev
```

访问：
- 前端：http://localhost:5174
- 后端 API：http://localhost:8124/api
- Swagger：http://localhost:8124/api/swagger-ui.html

### 方式二：Docker 一键部署

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 AI_DASHSCOPE_API_KEY

# 2. 一键启动
docker compose up -d --build

# 3. 访问
# 前端：http://localhost:5174
# 后端：http://localhost:8124/api

# 停止
docker compose down
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/data/summary` | 获取账户汇总数据（支持时间段） |
| GET | `/api/data/campaigns` | 获取计划级报表 |
| POST | `/api/data/import-csv` | 导入千川标准 CSV 报表（中英文表头，UTF-8/GBK 自动识别，导入后自动失效取数缓存） |
| GET | `/api/data/csv-template` | 下载标准 CSV 导入模板 |
| GET | `/api/report/daily` | 生成日报 |
| GET | `/api/report/weekly` | 生成周报 |
| GET | `/api/report/custom` | 生成自定义时间段报告 |
| GET | `/api/report/list` | 获取历史报告列表（分页，返回 total） |
| GET | `/api/report/{id}` | 获取报告详情 |
| DELETE | `/api/report/{id}` | 删除历史报告 |
| GET | `/api/query/ask` | 自然语言查询（基于 Spring AI Tool Calling，返回数据来源与工具调用轨迹） |
| GET | `/api/config` | 获取业务阈值配置（供前端展示） |

> 所有响应头均携带 `X-Trace-Id`，可用于在服务端日志中检索该请求的完整调用链路。

## 项目结构

```
ad-review-analyst/
├── src/main/java/io/github/chenyouxin8/adreview/
│   ├── AdReviewApplication.java    # 启动类
│   ├── common/                      # 通用响应、异常处理
│   ├── config/                      # 配置类（CORS、安全、MVC、请求追踪过滤器）
│   ├── controller/                  # REST API 接口
│   │   ├── DataController.java      # 数据查询 + CSV 导入
│   │   ├── ReportController.java    # 报告生成与管理
│   │   ├── QueryController.java     # 自然语言查询
│   │   └── ConfigController.java    # 阈值配置
│   ├── service/                     # 业务逻辑
│   │   ├── QianchuanService.java    # 三级数据源路由取数（含 5 分钟内存缓存）
│   │   ├── CsvImportService.java    # 千川 CSV 报表导入（中英文表头映射）
│   │   ├── AnalysisService.java     # 数据分析、全套 6 种异常检测
│   │   ├── ReportService.java       # 报告生成、单次结构化 AI 分析、Tool Calling 问答
│   │   └── ReportStorageService.java # 报告存储（H2 + JdbcTemplate 分页/删除）
│   ├── repository/                  # CampaignRepository（H2 批量 Upsert）
│   ├── tool/                        # AdDataAnalyticsTools（请求级有状态 AI 工具箱）
│   ├── model/                       # 数据模型（含分页结果、ReviewConfig 阈值配置）
│   ├── constant/                    # 枚举常量（含 DataSourceType、AbnormalType）
│   └── job/                         # 定时任务
├── src/test/java/                   # 单元测试（Analysis/CsvImport/Qianchuan/Report/Tools）
├── src/main/resources/
│   ├── application.yml              # 应用配置（含阈值、缓存、CORS、日志 pattern）
│   ├── schema.sql                   # H2 表结构
│   └── mcp-servers.example.json     # MCP 配置模板
├── frontend/                        # Vue 3 前端
│   └── src/
│       ├── views/                   # 页面（概览/报告/查询/历史/详情）
│       ├── router/                  # 路由
│       ├── utils/                   # API 封装
│       └── styles/                  # 样式
├── mvnw / mvnw.cmd / .mvn          # Maven Wrapper
├── Dockerfile                       # 后端镜像
├── Dockerfile.frontend              # 前端镜像
├── docker-compose.yml               # 一键部署
└── .env.example                     # 环境变量模板
```

## 数据接入说明

系统取数采用**三级数据源优先级路由**，并始终把「数据来源」与「是否降级」透传到报告 / 汇总 / 问答结果，前端醒目提示：

1. **本地 CSV 导入（LOCAL_IMPORT）**：优先命中。上传千川标准 CSV 报表（支持中英文表头、UTF-8/GBK 自动识别、衍生指标自动计算），数据落库 H2；导入后自动失效取数缓存，立即生效。不消耗 LLM Token。
2. **千川 MCP 实时数据（MCP_LIVE）**：本地库无该时段数据且 MCP 工具可用时，通过 AI 模型调用千川 MCP 工具取数，并用 `BeanOutputConverter` 强 Schema 结构化输出解析。
3. **Mock 兜底（MOCK_FALLBACK）**：前两者均不可用时返回**幂等**模拟数据（固定种子保证同一日期查询结果稳定），便于开发演示。可通过 `ad-review.fallback-mock-on-error=false` 关闭降级，让错误直接暴露。

> 导入 CSV：前端页面直接上传，或调用 `POST /api/data/import-csv`；模板下载：`GET /api/data/csv-template`。

### 巨量千川 MCP 接入

1. 访问 [巨量引擎开放平台 MCP 页面](https://open.oceanengine.com/mcp?app_id=1837698859935771)
2. 点击"授权"，登录巨量千川账号
3. 获取 Access Token
4. 复制 `mcp-servers.example.json` 为 `mcp-servers.json`，填入 Token

## 配置说明

所有业务阈值均集中在 `ad-review.*` 前缀下，由 `ReviewConfig` 统一绑定并经 `GET /api/config` 暴露给前端，代码中无魔法数字。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `ad-review.api-key` | 生产环境 Bearer Token 鉴权（未设置则跳过） | 空 |
| `ad-review.roi-threshold` | ROI 预警阈值 | 1.5 |
| `ad-review.cpa-threshold` | CPA 预警阈值（元） | 100 |
| `ad-review.ctr-threshold` | CTR 预警阈值（%） | 1.0 |
| `ad-review.abnormal-min-cost` | 异常诊断最低消耗门槛（元） | 100 |
| `ad-review.top-campaign-min-cost` | 入选 TOP 计划的最低消耗门槛（元） | 200 |
| `ad-review.declining-campaign-min-cost` | 判定衰退计划的最低消耗门槛（元） | 500 |
| `ad-review.cpa-min-conversions` | 判定高 CPA 的最小转化数 | 3 |
| `ad-review.ctr-min-impressions` | 判定低 CTR 的最小展示量 | 10000 |
| `ad-review.budget-growth-factor` | 默认预算规划乘数 | 1.1 |
| `ad-review.overspend-ratio-threshold` | 预算超支检测：消耗超过全场平均的倍数 | 2.5 |
| `ad-review.creative-decay-cpc-multiplier` | 素材衰退检测：CPC 高于全场平均的倍数 | 1.5 |
| `ad-review.cache-ttl-seconds` | 取数结果内存缓存 TTL（秒） | 300 |
| `ad-review.cors.allowed-origins` | 跨域允许来源（逗号分隔，环境变量 `CORS_ALLOWED_ORIGINS`） | `*` |
| `ad-review.fallback-mock-on-error` | 数据拉取失败时降级为模拟数据 | true |
| `ad-review.daily-report-cron` | 每日自动复盘时间 | 每天 9:00 |
| `ad-review.report-storage-path` | 报告存储路径（H2 数据目录见 `spring.datasource.url`） | ./data/reports |

## 测试

```bash
./mvnw.cmd test        # 运行后端单元测试（Windows；*nix 用 ./mvnw test）
cd frontend && npm run build   # 构建前端
```

## 扩展方向

- [ ] 接入更多投放平台（快手磁力、腾讯广告）
- [ ] 支持微信/飞书消息推送报告
- [ ] 素材内容 AI 分析（视频画面/文案拆解）
- [ ] 自动执行优化操作（暂停计划、调整出价）
- [ ] 多账户管理和跨账户对比
- [ ] 从 H2 平滑迁移到 MySQL/PostgreSQL（生产级并发与集群）

## License

MIT License

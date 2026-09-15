# AI 投流复盘分析师

基于 Spring Boot + Spring AI + 巨量千川 MCP 的智能投放复盘系统。自动拉取千川投放数据，通过 AI 大模型分析生成结构化复盘报告，支持异常诊断、策略建议和自然语言查询。

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-green.svg)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 功能特性

- **自动复盘报告**：支持日报、周报、自定义时间段报告，一键生成
- **AI 深度分析**：通义千问大模型分析投放数据，给出专业解读
- **异常智能诊断**：自动识别 ROI 过低、CPA 过高、CTR 偏低等异常计划（阈值可配置）
- **策略建议生成**：基于数据给出预算分配、出价优化、素材优化建议
- **预算分配建议**：按 ROI 加权自动给出各计划的预算调整比例与原因
- **自然语言查询**：用大白话问数据，如"今天哪个计划跑废了"，自动识别查询意图
- **每日定时任务**：每天早上 9 点自动生成前一天日报
- **报告历史管理**：所有报告本地持久化存储，支持分页回溯查看与删除
- **真实数据接入**：通过 Spring AI 结构化输出解析千川 MCP 返回数据（未配置 MCP 时自动降级为模拟数据）

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.1.0 + Spring AI 2.0.0 |
| 大模型 | 通义千问 qwen-max |
| 数据接入 | 巨量千川官方 MCP Server（StreamableHttp） |
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
| GET | `/api/report/daily` | 生成日报 |
| GET | `/api/report/weekly` | 生成周报 |
| GET | `/api/report/custom` | 生成自定义时间段报告 |
| GET | `/api/report/list` | 获取历史报告列表（分页，返回 total） |
| GET | `/api/report/{id}` | 获取报告详情 |
| DELETE | `/api/report/{id}` | 删除历史报告 |
| GET | `/api/query/ask` | 自然语言查询（自动识别意图） |
| GET | `/api/config` | 获取业务阈值配置（供前端展示） |

## 项目结构

```
ad-review-analyst/
├── src/main/java/io/github/chenyouxin8/adreview/
│   ├── AdReviewApplication.java    # 启动类
│   ├── common/                      # 通用响应、异常处理
│   ├── config/                      # 配置类（CORS、安全、MVC）
│   ├── controller/                  # REST API 接口
│   │   ├── DataController.java      # 数据查询
│   │   ├── ReportController.java    # 报告生成与管理
│   │   ├── QueryController.java     # 自然语言查询
│   │   └── ConfigController.java    # 阈值配置
│   ├── service/                     # 业务逻辑
│   │   ├── QianchuanService.java    # 千川数据拉取（MCP + 结构化输出解析）
│   │   ├── AnalysisService.java     # 数据分析、异常检测
│   │   ├── ReportService.java       # 报告生成、AI分析
│   │   └── ReportStorageService.java # 报告存储（分页/删除）
│   ├── model/                       # 数据模型（含分页结果、配置）
│   ├── constant/                    # 枚举常量
│   └── job/                         # 定时任务
├── src/test/java/                   # 单元测试（AnalysisService）
├── src/main/resources/
│   ├── application.yml              # 应用配置
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

配置真实千川 MCP 后，系统通过 AI 模型调用 MCP 工具获取报表数据，并使用 Spring AI 结构化输出（JSON Schema）将结果解析为计划数据模型。

- 未配置 MCP 工具时：自动返回模拟数据，方便本地开发测试
- AI/千川调用失败时：默认降级为模拟数据（可通过 `ad-review.fallback-mock-on-error=false` 关闭降级，让错误直接暴露）

### 巨量千川 MCP 接入

1. 访问 [巨量引擎开放平台 MCP 页面](https://open.oceanengine.com/mcp?app_id=1837698859935771)
2. 点击"授权"，登录巨量千川账号
3. 获取 Access Token
4. 复制 `mcp-servers.example.json` 为 `mcp-servers.json`，填入 Token

## 配置说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `ad-review.api-key` | 生产环境 Bearer Token 鉴权（未设置则跳过） | 空 |
| `ad-review.roi-threshold` | ROI 预警阈值 | 1.5 |
| `ad-review.cpa-threshold` | CPA 预警阈值（元） | 100 |
| `ad-review.ctr-threshold` | CTR 预警阈值（%） | 1.0 |
| `ad-review.abnormal-min-cost` | ROI 异常诊断最低消耗门槛（元） | 100 |
| `ad-review.fallback-mock-on-error` | 数据拉取失败时降级为模拟数据 | true |
| `ad-review.daily-report-cron` | 每日自动复盘时间 | 每天 9:00 |
| `ad-review.report-storage-path` | 报告存储路径 | ./data/reports |

## 测试

```bash
./mvnw test        # 运行后端单元测试
cd frontend && npm run build   # 构建前端
```

## 扩展方向

- [ ] 接入更多投放平台（快手磁力、腾讯广告）
- [ ] 支持微信/飞书消息推送报告
- [ ] 素材内容 AI 分析（视频画面/文案拆解）
- [ ] 自动执行优化操作（暂停计划、调整出价）
- [ ] 多账户管理和跨账户对比
- [ ] 数据库存储（MySQL/PostgreSQL）替代本地 JSON

## License

MIT License

# 当前状态（2026-10-27 本轮交付）

本项目**只提供增强服务**：给定任务，返回该注入的推理骨架。任务集、执行模型调用、7 臂实验与报告放在独立项目。

## 已实测通过

| 项 | 结果 |
| --- | --- |
| 构建 | `mvn clean package` 通过，单 jar `target/moderoute-1.0.0.jar` |
| 测试 | 43 个用例全绿（接口契约、降级、版本归一化、字母映射、门控、渲染、精判客户端、SQLite 存取） |
| 卡片 | 48 张，3 域 × 8 族 × 2 变体；启动期逐字段校验，导入 SQLite 后加载为不可变快照 |
| 库版本 | `42858df80a60` |
| embedding | `qwen3.7-text-embedding`，1024 维，网关限制 batch ≤20（已分片） |
| 向量存储 | 仅 SQLite + sqlite-vec v0.1.9，WAL；`vec_distance_cosine` 检索 |
| 存储耗时 | 258 µs（48×1024，top-8，300 轮均值） |
| 精判 | 本地 JEV `jev-style-0.8b-decision-v3`，torch CPU，单次 2–15 s（受机器负载影响大） |
| 端到端 | `POST /v1/enhance` 返回 200，`INJECT`，渲染 470–510 字 |
| 确定性 | 同一请求两次 `prompt` 逐字节一致 |
| 删库重算 | 删除 `var/moderoute.db` 后重启，响应 SHA-256 与删除前完全一致 |
| 降级 | 精判不可达时 200 + `DEGRADED`，规则选卡仍有可用骨架 |
| OpenAPI | 仅暴露 `/v1/enhance`、`/v1/cards`、`/healthz` |
| 依赖纪律 | enforcer 拦截 chat/completion SDK；依赖树只有 `spring-ai-model` / `spring-ai-commons` / `spring-ai-template-st` |

## 环境

- 代码：`E:\Codex`
- JEV：依赖、pip 缓存、HF 缓存、权重（1.44 GB）全部在 `D:\Application\Jev-Style`
  - 权重路径 `D:\Application\Jev-Style\model\model.safetensors`
  - VC++ 运行库缺失，已把所需 DLL 复制进 `.venv\Lib\site-packages\torch\lib`，未做系统安装
  - 启动：`powershell -File tools\start-jev.ps1`（自动用 `--model-dir`）
- sqlite-vec 扩展：`lib/native/vec0.dll`（gitignore，缺失则服务拒绝启动）
- 服务：`java -jar target\moderoute-1.0.0.jar`，端口 18080

## 接口行为

- 请求只有 `task` / `options.topK` / `options.budgetTokens` / `traceId`
- **没有指定卡片的调试参数**：注入哪张卡完全由服务自己检索与精判决定
- `prompt` 永不为 null；未命中为空串；降级返回 200 并通过 `meta.action` 可观测
- 审计 `recallMs` / `decideMs`：召回耗时含任务 embedding 的网络调用，纯 SQL 检索约 0.2 ms

## 评测（独立项目）

1. 任务集、生成器、执行器、约束判定器、7 臂与报告不在本仓库。
2. 评测侧需要对齐的三件事：`meta.libraryVersion`（记录结果属于哪个库版本）、`GET /v1/cards`（核对加载内容）、响应与错误码（`prompt` 空串、降级 200）。
3. 报告必须写明：`general` 域已建卡但未纳入评测。

## 已知偏差

见 `README.md` 第 7 节（25 卡上限、只用 Spring AI 抽象、召回剔除负例、输出格式示例压缩、作者隔离局限、向量落 SQLite 偏离 SPEC）。

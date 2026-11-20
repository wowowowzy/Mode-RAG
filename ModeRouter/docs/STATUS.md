# 当前状态

> **2026-11-20 改造态**：库版本 `976354cd5130`（卡片新增 taxonomy/relations 并治理 6 对高重叠卡后重算）。
> 历史基线 `9ded546a1697` 的成绩（总体 91/188=48.4%、planning 66.4%、debate 召回 84.7%、精判 19.4%）
> **只属于旧版本，不可与新版本混算**；新版本的全量评测与 holdout 验收尚未执行，见根目录执行报告。
>
> 本轮改造：P0 降级路径 noneMargin 修复 + 独立 holdout + 评测口径；P1 按域召回过滤；
> P2 关系图 / 候选收缩 / 规则否决 / 域级后置块；P3 高重叠卡文案区分度；P4 三臂实验基础设施；P5 待重扫。
>
> 本文对应库版本 `976354cd5130`；卡片或接口变更时必须同步本文。最后核对：2026-11-20

本项目**只提供增强服务**：给定任务，返回该注入的推理骨架。任务集、执行模型调用、实验与报告放在独立项目。

## 已实测通过

| 项 | 结果 |
| --- | --- |
| 构建 | `mvn clean package` 通过，单 jar `target/moderoute-1.0.0.jar` |
| 测试 | 72 个用例全绿（ModeRouter 70 + ModeTest 2）；新增 P0 降级门控、P1 域过滤、P2 关系图/收缩/否决/组合用例 |
| 卡片 | 48 张，3 域 × 16 张独立卡（无变体）；每张带 `taxonomy.family` / `relations`，启动期逐字段校验后导入 SQLite 只读快照 |
| 库版本 | `976354cd5130`（2026-11-20 改造态；历史：`9ded546a1697`、`0be0e650140a`、`a4eaf1689a64`） |
| embedding | `qwen3.7-text-embedding`，1024 维，网关限制 batch ≤20（已分片） |
| 向量存储 | 仅 SQLite + sqlite-vec v0.1.9，WAL；`vec_distance_cosine` 检索 |
| 向量并发 | 计算走 `ReentrantLock.tryLock`，抢不到立即降级；失败后 60s 冷却，不排队 |
| 存储耗时 | 258 µs（48×1024，top-8，300 轮均值）；内存检索走 `CosineSimilarity`，同量级 |
| 精判 | 本地 JEV `jev-style-0.8b-decision-v3`，torch CPU，单次 2–18 s（受机器负载影响大） |
| 端到端 | `POST /v1/enhance` 返回 200，`INJECT`，渲染约 470 字 |
| 确定性 | 同一请求两次 `prompt` 逐字节一致 |
| 删库重算 | 删除 `var/moderoute.db` 后重启，响应 SHA-256 与删除前完全一致 |
| 降级 | 精判不可达时 200 + `DEGRADED`，规则选卡仍有可用骨架；规则路径的 `None` 参与同尺度竞争 |
| 持久化容错 | 向量写库失败时内存副本仍可服务，仅重启后重算 |
| OpenAPI | 仅暴露 `/v1/enhance`、`/v1/cards`、`/healthz` |
| 依赖纪律 | enforcer 拦截 chat/completion SDK；依赖树只有 `spring-ai-model` / `spring-ai-commons` / `spring-ai-template-st` |

## 环境

- 代码：仓库根目录（本目录）
- JEV：依赖、HF 缓存、权重（1.5 GB）全部在同级目录 `..\Jev-Style`
  - 权重路径 `..\Jev-Style\model\model.safetensors`
  - 启动：`Jev-Style\start-jev.bat`（自带精简 Python，重写 pyvenv.cfg 后拉起 8765）
- sqlite-vec 扩展：`lib/native/vec0.dll` **已随仓库提供**（283 KB）；误删时执行 `tools/fetch-sqlite-vec.ps1` 恢复
- 服务：`java -jar target\moderoute-1.0.0.jar`，端口 18080

## 接口行为

- 请求有 `task` / `options.topK` / `options.domain` / `traceId`；`domain` 是召回范围（planning/debate/general），不是指定卡片
- **`/v1/enhance` 没有指定卡片的调试参数**：注入哪张卡完全由服务自己检索与精判决定
- 排查用的只读端点 `GET /v1/cards/{cardId}/skeleton` 返回指定卡的完整骨架，仅供三臂实验 oracle 臂使用，不参与正常决策链路
- 审计新增：`recall.domainFilter` / `recall.domainFilterApplied`（SQLite 后备路径为 false）/ `recall.narrowedCandidates`、`render.composedCards` / `render.graphNodes`
- `prompt` 永不为 null；未命中为空串；降级返回 200 并通过 `meta.action` 可观测
- 审计 `recallMs` / `decideMs`：召回耗时含任务 embedding 的网络调用，纯 SQL 检索约 0.2 ms
- 审计**按 `libraryVersion` 分域**，跨版本记录不可合并统计；筛选用 `tools/audit-filter.py --version <hash>`

## 标定

| 项 | 值 |
| --- | --- |
| 温度 T | `0.72`（`moderoute.decider.temperature`）· 重拟合建议 `0.68`（test ECE 0.1019 vs 未标定 0.1769），待与 margin 重扫一起落地 |
| None margin | `0.23`（`moderoute.gate.none-margin`）· 在 `9ded546a1697` + planning/topK=8 上扫描，准确率平台起点（77/116），继续增大只多 1 条却等于停用 FALLBACK |
| 按域 topK | planning 8 / debate 16 / general 8（`ModeTestProperties.TopK`，`modetest.topk.*` 可覆盖） |
| 拟合样本 | val 63 条（正例 39.7%），test 125 条只报告 |
| val ECE | 0.1095 |
| test ECE | 未标定 0.1396 → 标定 0.0852 |
| 拟合脚本 | `tools/fit-temperature.py`（网格搜索 0.5–3.0，步长 0.01） |
| 标签来源 | `tasks.jsonl` 的 `acceptableCards`；FALLBACK 且该任务有可接受卡记为负例 |
| 重跑触发条件 | 卡片库改版 / 换 embedding 模型 / 换 JEV 版本。**本轮库版本已变更为 `976354cd5130`，必须重扫** |
| 补跑脚本 | `tools/collect-decisions.py --topk-map "planning=8,debate=16"`（断点续跑，188 条约 25 分钟；跨域必传 topk-map） |

> **P5 待执行**：库版本变化后，先 `python tools/collect-decisions.py --topk-map "planning=8,debate=16"` 重采决策，
> 再 `python tools/fit-temperature.py --version 976354cd5130` 与
> `python tools/scan-none-margin.py --version 976354cd5130 --domain planning --topk 8`。
> 温度与 margin 必须绑定重扫——温度改变会改变标定后概率，从而改变 margin 语义。
> 当前仍保留 T=0.72 / margin=0.23：历史 0.68 的建议基于旧库版本且 ECE 改善未复验，
> 在新版本上未经拟合前不擅自改值；若新温度下 holdout ECE 改善 <0.01，可维持 0.72 并在报告说明理由。

> 温度缩放是保序变换，**不改变 argmax**，所以调整 T 不影响任何 INJECT / FALLBACK 判定，只影响 `meta.confidence` 的数值。
>
> **标签口径偏严**：正例按「argmax 命中的卡在 acceptableCards 里」判定，一张任务往往只列 2–3 张可接受卡，因此 39.7% 的正例比例不代表选卡准确率，只代表严格口径下的命中率。

## 评测（独立项目）

1. 任务集、生成器、执行器、约束判定器、实验与报告不在本仓库。
2. 评测侧需要对齐的三件事：`meta.libraryVersion`（记录结果属于哪个库版本）、`GET /v1/cards`（核对加载内容）、响应与错误码（`prompt` 空串、降级 200）。
3. 报告必须写明：`general` 域已建卡但未纳入评测。
4. **降级期与正常期的置信度不可混算**：前者来自字符二元组相似度，后者来自精判概率，不在同一标尺。

## 已知偏差

见 `README.md` 第 7 节（25 卡上限、只用 Spring AI 抽象、召回剔除负例、输出格式示例压缩、作者隔离局限、向量落 SQLite 偏离 SPEC）。

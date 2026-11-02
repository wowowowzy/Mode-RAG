# ModeRoute

应用层的**推理模式召回组件**：给定任务文本，从模式库召回最适配的「推理骨架」，以一段可直接拼入 prompt 的文本返回。

执行者不变，变的是这一次注入的推理骨架。服务本身不调用任何执行模型，也不训练任何模型。

- 单一 Maven 工程，产物是一个可 `java -jar` 启动的服务
- 无数据库服务：向量存 SQLite（sqlite-vec + WAL），卡片文件加载进只读快照，日志追加写 JSONL
- 早期契约见 `docs/SPEC.md`

---

## 1. 快速启动

前置：JDK 17（跑 jar 必须有）；Maven 3.9+ 只在编译时需要。

```powershell
# 1) embedding 与精判的配置放 .env.local（已被 .gitignore 忽略）
#    OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_EMBEDDING_MODEL

# 2) sqlite-vec 扩展：已随仓库提供 lib/native/vec0.dll，通常无需操作
#    若被误删：powershell -ExecutionPolicy Bypass -File tools\fetch-sqlite-vec.ps1

# 3) 启动本地精判引擎（依赖与模型在同级目录 ..\Jev-Style）
powershell -File tools\start-jev.ps1

# 4) 构建并启动服务
mvn -q -DskipTests package
java -jar target\moderoute-1.0.0.jar
```

服务默认监听 `18080`：

```powershell
curl.exe http://127.0.0.1:18080/healthz
curl.exe http://127.0.0.1:18080/v1/cards
curl.exe -X POST http://127.0.0.1:18080/v1/enhance -H "Content-Type: application/json" -d "{\"task\":\"为三条产线排布五张订单，订单带最晚完成日\"}"
```

离线自检（不依赖网络与模型）：

```powershell
mvn test
java -jar target\moderoute-1.0.0.jar `
  --moderoute.recall.embedding=mock `
  --moderoute.decider.provider=mock
```

---

## 2. 接口

### `POST /v1/enhance`

```json
{
  "task": "任务文本，1–4000 字",
  "options": { "topK": 8 },
  "traceId": "可选"
}
```

```json
{
  "prompt": "可直接拼入 prompt 的文本，未命中为空串",
  "traceId": "trace-001",
  "meta": {
    "action": "INJECT",
    "cardId": "planning-capacity-first",
    "confidence": 0.8241,
    "libraryVersion": "a4eaf1689a64"
  }
}
```

三条硬规则：

1. `prompt` 永不为 `null`，未命中是空串——调用方不需要写分支。
2. 降级也返回 200，只通过 `meta.action` 可观测：`INJECT` / `FALLBACK` / `DEGRADED`。
3. 相同任务 + 相同库版本 + 相同决策输出 → `prompt` 逐字节一致。

错误体：

```json
{ "error": { "code": "TASK_TOO_LONG", "message": "task 超过 4000 字（实际 4001）" } }
```

| 状态 | code | 触发条件 |
| --- | --- | --- |
| 400 | `INVALID_TASK` | task 缺失或空白 |
| 400 | `INVALID_OPTIONS` | topK 不在 3–26 |
| 400 | `INVALID_JSON` | 请求体不是合法 JSON |
| 422 | `TASK_TOO_LONG` | task 超过 4000 字 |
| 503 | `LIBRARY_NOT_LOADED` | 模式库未加载 |

### 其他端点

- `GET /healthz`：状态、库版本、卡数、向量是否就绪、当前 embedding 模型与精判 provider
- `GET /v1/cards`：列出模式库（id / domain / name / applicableWhen）
- OpenAPI：`/v3/api-docs`、`/swagger-ui.html`

接口只做一件事：给定任务，让系统自己检索并判断该不该注入哪张卡。**没有调试参数**，也不接受指定卡片——要指定的场景（例如对照实验）由调用方在外部自己构造 baseline，不进入本服务。

---

## 3. 一条请求的完整链路

```
task ──embedding──> 余弦 top-k（内存扫描）
     ──cardId 字典序────> A–Z 单字母选项（末位固定为 None）
     ──决策引擎────> 选项概率分布
     ──温度缩放────> 标定后置信度
     ──门控────> None 胜出=FALLBACK，否则 INJECT
     ──渲染────> prompt（卡片全文，不做裁剪）
     ──审计────> var/audit/audit-YYYY-MM-DD.jsonl
```

任何一步抛异常都转为 `DEGRADED`：用字符二元组相似度选卡并渲染，仍然返回 200。

精判请求（`POST /v1/systemone`）：

```json
{
  "state": "任务：...\n\n候选推理骨架（唯一判断依据是每个骨架的「适用条件」）：\nA. ...\nB. ...\nD. None —— 以上骨架都不适用于该任务",
  "questions": {
    "card": {
      "type": "choice",
      "instructions": "哪个候选骨架最适配这个任务？",
      "criteria": { "A": null, "B": null, "C": null, "D": null }
    }
  }
}
```

---

## 4. 配置

`application.yml` 里的每一项都可用环境变量覆盖，`.env.local` 会被自动导入。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `moderoute.cards-dir` | `./data/cards` | 卡片种子目录，启动时同步进 SQLite（版本不变则跳过） |
| `moderoute.recall.embedding` | `openai` | `openai`（任意 OpenAI 兼容 `/v1/embeddings`）或 `mock` |
| `moderoute.recall.batch-size` | `10` | 单次 embedding 条数，网关限制 ≤20 |
| `moderoute.recall.sqlite-path` | `./var/moderoute.db` | 向量库位置；删掉即重算 |
| `moderoute.recall.extension-path` | `./lib/native/vec0.dll` | sqlite-vec 扩展；已随仓库提供，缺失时用 `tools/fetch-sqlite-vec.ps1` 恢复 |
| `moderoute.recall.strip-negative-clause` | `true` | 召回向量剔除「不适用于…」 |
| `moderoute.decider.provider` | `jev-local` | `jev-local` / `jev-api` / `mock` |
| `moderoute.decider.base-url` | `http://127.0.0.1:8765` | System One 兼容服务 |
| `moderoute.decider.temperature` | `1.0` | **我们自己的**温度缩放，只在验证集上拟合 |
| `moderoute.gate.inject-floor` | `0` | 可选安全阀；默认完全由「None 是否胜出」决定 |
| `moderoute.audit.dir` | `./var/audit` | 每请求一行 JSONL |

---

## 5. 数据

### 模式卡

`data/cards/<domain>/*.yaml`，48 张 = 3 域 × 16 张（每域 16 种不同策略，没有变体）。字段：`id` / `domain` / `name` / `applicableWhen`（≤80 字，必须含「不适用于…」）/ `steps`（4–6）/ `failureModes`（2–4）/ `exitConditions`（1–3）/ `outputContract`。

**存储方式**：YAML 是种子与编辑格式，运行时唯一读取源是 SQLite 的 `card` 表。

1. 启动时按文件内容算出库版本（`libraryVersion`）；
2. 与 `card_library` 表里已导入的版本比较，不同才在一个事务里整批替换 `card` 表；
3. 然后从 `card` 表读出全部卡片，装成不可变快照（版本 + 列表 + id 索引）；
4. 卡片目录缺失但库里已有数据时，直接用库里的版本启动（便于只带 `.db` 部署）。

所以改卡流程仍是：编辑 YAML → 重启 → 版本变化 → 卡片重新导入 → 向量按新版本重算。好处是编辑可 diff、可评审，运行时读库、读快照，不碰文件 IO。

加载期校验到字段级：任何一张卡不合规，服务直接拒绝启动（不带半份库对外服务）。

库版本 = 卡片文件按相对路径排序、内容归一化（去 BOM、行尾统一 `\n`、去行尾空白）后拼接，取 SHA-256 前 12 位。归一化保证 Windows 与 Linux 算出同一个版本号。

### 评测

本仓库**不包含**任务集、任务生成器与实验脚本。评测放在独立项目：那边生成任务、调执行模型、判定与出报告；本项目只负责「给定任务，返回该注入的骨架」。两边通过 HTTP 契约与 `meta.libraryVersion` 对齐。


### 审计日志

`var/audit/audit-YYYY-MM-DD.jsonl`，每请求一行。它不是业务日志，而是**决策的可复现证据**：

| 字段 | 用途 |
| --- | --- |
| `traceId`、`ts`、`latencyMs`、`recallMs`、`decideMs` | 定位一次请求、拆开延迟来自哪一段 |
| `libraryVersion`、`embeddingModel`、`vectorStore` | 固定当时的库与模型，判断结果能否跨版本比较 |
| `taskSha256`、`taskLength` | 不存原文就能核对是不是同一个任务 |
| `recall.candidates[{cardId,score}]` | 粗筛给了哪些候选、分数多少 |
| `decision.optionOrder` / `probabilities` / `rawProbabilities` / `engineConfidence` / `temperature` | 精判的字母顺序、原始与标定后的概率、引擎自报置信度 |
| `gate.action` / `cardId` / `confidence` | 门控最后做了什么，是注入、回退还是降级 |
| `render.prompt` / `chars` / `estimatedTokens` | 实际注入的文本与预算占用 |
| `degradedReason` | 降级原因（召回失败 / 精判失败 / 规则降级） |

**没有「理由」字段，也不要加。** 决策模型不生成文本，给不出理由；可审计的证据就是概率分布本身。

它的四个用途：复现（同任务同版本能重放出同一决策）、排障（为什么是这张卡、为什么没注入）、实验统计（注入率、置信度分档、跨域误选、延迟拆分）、以及事后核对（版本与模型是否被换过）。原文不入库，只留 hash，避免日志变成用户数据副本。

---

## 6. 关键设计（改之前先看理由）

- **回退是候选池里的一个选项**：决策模型的 Choice 是封闭集，必然挑一个；两张烂卡 55/45 纠结时也会给出「中置信」。把 `None` 放进候选池，门控从阈值工程变成决策结构。
- **选项必须是单字母 A–Z**：这类引擎靠预填位置的 logit 出概率，多 token 标签会退化。因此 `topK` 上限是 26，且 None 要占一位——实际最多 25 张真实卡。
- **字母按 cardId 字典序映射**，不按召回分数：分数会随 embedding 版本变化，按分数排会破坏可复现性。
- **精判只看 `applicableWhen`**：决策模型有上下文稀释问题，`steps` / `failureModes` 不进精判输入。
- **置信度必须自行重标定**：原始 softmax ECE 很差；温度只在验证集上拟合，测试集只报告。
- **48 张 / 3 域 × 16 张**：单次只召回 8 张，占全库 16.7%，先粗筛再精判的级联才有意义。
- **向量存 SQLite（WAL），检索走 sqlite-vec**：普通表存 float32 BLOB，距离用 `vec_distance_cosine()`；不用 `vec0` 虚拟表，因为它的维度建表即定死，换 embedding 模型就要迁移。实测 48×1024 的 top-8 约 258 µs，仍是端到端 0.08% 量级。
- **删除即重算**：`(库版本, 模型, 卡片集合, 维度)` 任一不匹配都视为未就绪，整批重算；删掉 `var/moderoute.db` 重启，响应逐字节不变。

---

## 7. 与 SPEC 的偏差（如实记录）

1. **精判候选上限 25 张卡 + None**。SPEC 写 topK 上限 26，但单字母只有 26 个且 None 必须占一位；`topK=26` 时实际传 25 张卡。
2. **Spring AI 只用 `spring-ai-model` 抽象**，OpenAI 兼容传输层自己写（`OpenAiCompatibleEmbeddingModel`）。若引入 `spring-ai-openai`，会把 chat 类一起带进依赖树，「不碰执行模型」就只能靠承诺、不能靠依赖树。换后端仍只改配置。
3. **召回向量只用 `applicableWhen` 的正面半句**。负例是给「排除」用的，混进相似度会变成正向信号；精判仍看到完整字段。
4. **注入文本不做长度裁剪**。原先 `budgetTokens`（默认 400）会在预算不足时丢掉失败模式或输出格式示例；现在卡片全文注入，接口不再接受该参数，审计里只保留 `estimatedTokens` 供观察。
5. **卡片与任务集由同一作者（AI）产出**。「写卡的人不看任务集」这条纪律在单人 + AI 的开发形态下只能部分执行：卡片先行，任务集随后由另一个 agent 按格式规范逐条撰写，但无法证明作者隔离（同一模型家族、同一批评审口径）。这一点必须写进实验报告的局限章节。
6. **向量落 SQLite，偏离 SPEC「不引入数据库」**。SPEC §3 明确不引数据库；按实际取舍改为 SQLite + sqlite-vec（WAL），运行路径只有这一条。代价是引入 `sqlite-jdbc` 与一个原生扩展（`vec0.dll`，缺失则拒绝启动），数据库只存**派生向量**，不存会话、不存卡片，删掉即重算，接口契约与决策结果不变。

---

## 8. 目录

```
pom.xml
src/main/java/com/challenger/moderoute/
  controller/        3 个 REST 入口
  service/           业务接口（Enhance / Recall / Decide / Render / CardLibrary / Audit）
  service/impl/      对应实现
  mapper/            数据访问接口（卡片文件、向量库）
  mapper/impl/       YAML 加载、SQLite + sqlite-vec
  entity/            领域对象（Card / CardLibrary / Decision / OptionSet ...）
  entity/enums/      枚举
  dto/               请求 / 响应体
  client/            外部依赖适配（embedding、System One、mock）
  common/            异常与全局处理；common/util 纯算法
  config/            配置与 Bean 装配
data/
  cards/            48 张模式卡（3 域 × 16）
lib/native/vec0.dll sqlite-vec 扩展（已入库；误删用 tools/fetch-sqlite-vec.ps1 恢复）
tools/              启动与运维脚本（start-jev / run / fetch-sqlite-vec / audit-filter / collect-decisions / fit-temperature / rule-threshold）
var/                运行时产物（gitignore）
  moderoute.db      卡片表 + 向量表（WAL）
  audit/            审计 JSONL
docs/               SPEC.md / STATUS.md
```

调用方向只有一条：`controller → service(接口) → mapper / client`，`entity` 与 `dto` 不反向依赖任何层。根目录只有 `src / data / lib / tools / var / docs`，运行产物全部收在 `var/`，删掉不影响行为。

## 9. 依赖纪律

```powershell
mvn dependency:tree | Select-String -Pattern "openai|anthropic|chat|completion"
```

依赖树里不应出现任何执行模型 / chat / completion SDK。embedding 抽象允许，chat 模型 SDK 不允许。

# ModeRoute

应用层的**推理模式召回组件**：给定任务文本，从模式库召回最适配的「推理骨架」，以一段可直接拼入 prompt 的文本返回。

执行者不变，变的是这一次注入的推理骨架。服务本身不调用任何执行模型，也不训练任何模型。

- 单一 Maven 工程，产物是一个可 `java -jar` 启动的服务
- 无数据库、无缓存服务、无向量库：48 张卡的向量在进程内存，日志追加写 JSONL
- 早期契约见 `docs/SPEC.md`

---

## 1. 快速启动

前置：JDK 17、Maven 3.9+。

```powershell
# 1) embedding 与精判的配置放 .env.local（已被 .gitignore 忽略）
#    OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_EMBEDDING_MODEL

# 2) 启动本地精判引擎（依赖与模型都在 D:\Application\Jev-Style）
powershell -File scripts\start-jev.ps1

# 3) 构建并启动服务
mvn -q -DskipTests package
java -jar target\moderoute-1.0.0.jar
```

服务默认监听 `18080`：

```powershell
curl.exe http://127.0.0.1:18080/healthz
curl.exe http://127.0.0.1:18080/v1/cards
curl.exe -X POST http://127.0.0.1:18080/v1/enhance -H "Content-Type: application/json" --data-binary "@scripts/smoke-request.json"
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
  "options": { "topK": 8, "budgetTokens": 400 },
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
    "libraryVersion": "42858df80a60"
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
| 400 | `INVALID_OPTIONS` | topK 不在 3–26，budgetTokens 不在 64–2000 |
| 400 | `INVALID_JSON` | 请求体不是合法 JSON |
| 400 | `INVALID_OVERRIDE` / `UNKNOWN_CARD` | 调试参数非法 |
| 422 | `TASK_TOO_LONG` | task 超过 4000 字 |
| 503 | `LIBRARY_NOT_LOADED` | 模式库未加载 |

### 其他端点

- `GET /healthz`：状态、库版本、卡数、向量是否就绪、当前 embedding 模型与精判 provider
- `GET /v1/cards`：列出模式库（id / domain / name / applicableWhen）
- OpenAPI：`/v3/api-docs`、`/swagger-ui.html`

### 调试参数 `override`

生产默认关闭（`moderoute.override-enabled=false`），且不出现在 OpenAPI 文档里。开启后所有实验臂走同一个接口：

```json
{ "override": { "mode": "forceCard|placebo|baseline|randomCard", "cardId": "...", "seed": 42 } }
```

- `baseline`：空 prompt，`action=FALLBACK`
- `forceCard`：强制注入指定卡
- `randomCard`：由 `seed + 任务` 的 SHA-256 确定选卡，可复现
- `placebo`：同结构、内容通用的假骨架，长度对齐「本应注入的骨架」

---

## 3. 一条请求的完整链路

```
task ──embedding──> 余弦 top-k（内存扫描）
     ──cardId 字典序────> A–Z 单字母选项（末位固定为 None）
     ──决策引擎────> 选项概率分布
     ──温度缩放────> 标定后置信度
     ──门控────> None 胜出=FALLBACK，否则 INJECT
     ──渲染────> prompt（受 budgetTokens 约束）
     ──审计────> runs/audit/audit-YYYY-MM-DD.jsonl
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
| `moderoute.cards-dir` | `./cards` | 卡片目录，启动期加载为只读快照 |
| `moderoute.override-enabled` | `false` | 实验调试参数开关 |
| `moderoute.recall.embedding` | `openai` | `openai`（任意 OpenAI 兼容 `/v1/embeddings`）或 `mock` |
| `moderoute.recall.batch-size` | `10` | 单次 embedding 条数，网关限制 ≤20 |
| `moderoute.recall.cache-dir` | `./runs/.vectors` | 纯缓存，删掉只会重算 |
| `moderoute.recall.strip-negative-clause` | `true` | 召回向量剔除「不适用于…」 |
| `moderoute.decider.provider` | `jev-local` | `jev-local` / `jev-api` / `mock` |
| `moderoute.decider.base-url` | `http://127.0.0.1:8765` | System One 兼容服务 |
| `moderoute.decider.temperature` | `1.0` | **我们自己的**温度缩放，只在验证集上拟合 |
| `moderoute.gate.inject-floor` | `0` | 可选安全阀；默认完全由「None 是否胜出」决定 |
| `moderoute.render.default-budget-tokens` | `400` | 请求未指定时的预算 |
| `moderoute.audit.dir` | `./runs/audit` | 每请求一行 JSONL |

---

## 5. 数据

### 模式卡

`cards/<domain>/*.yaml`，48 张 = 3 域 × 8 族 × 2 变体。字段：`id` / `domain` / `name` / `applicableWhen`（≤80 字，必须含「不适用于…」）/ `steps`（4–6）/ `failureModes`（2–4）/ `exitConditions`（1–3）/ `outputContract`。

加载期校验到字段级：任何一张卡不合规，服务直接拒绝启动（不带半份库对外服务）。

库版本 = 卡片文件按相对路径排序、内容归一化（去 BOM、行尾统一 `\n`、去行尾空白）后拼接，取 SHA-256 前 12 位。归一化保证 Windows 与 Linux 算出同一个版本号。

### 任务集

`tasks/tasks.jsonl`，200 条（planning 120 + debate 80；val 60 / test 140），由 `tools/GenerateTasks.java` 确定性生成：

```powershell
java tools\GenerateTasks.java
```

一行一任务：`taskId` / `domain` / `prompt` / `constraints` / `stances` / `acceptableCards` / `meta`。`acceptableCards` 是可接受集合，不是唯一正确答案。约束是 6 种结构化判定器（`capacity` / `precedence` / `mutex` / `window` / `assignment` / `ratio`），不用自由表达式。

---

## 6. 关键设计（改之前先看理由）

- **回退是候选池里的一个选项**：决策模型的 Choice 是封闭集，必然挑一个；两张烂卡 55/45 纠结时也会给出「中置信」。把 `None` 放进候选池，门控从阈值工程变成决策结构。
- **选项必须是单字母 A–Z**：这类引擎靠预填位置的 logit 出概率，多 token 标签会退化。因此 `topK` 上限是 26，且 None 要占一位——实际最多 25 张真实卡。
- **字母按 cardId 字典序映射**，不按召回分数：分数会随 embedding 版本变化，按分数排会破坏可复现性。
- **精判只看 `applicableWhen`**：决策模型有上下文稀释问题，`steps` / `failureModes` 不进精判输入。
- **置信度必须自行重标定**：原始 softmax ECE 很差；温度只在验证集上拟合，测试集只报告。
- **placebo 组必须有**：「注入了针对性骨架」与「prompt 变长了」必须分开，`ours - placebo` 才是真贡献。
- **48 张 / 8 族 × 2 变体**：同族两张是受控近邻（全量 vs 增量、构造 vs 修复），top-8 保留率 16.7%，级联召回才有意义。
- **内存向量**：48 × 1024 × float32 ≈ 192 KB，暴力扫描几十微秒，比任何外部向量库的往返都快一个数量级。

---

## 7. 与 SPEC 的偏差（如实记录）

1. **精判候选上限 25 张卡 + None**。SPEC 写 topK 上限 26，但单字母只有 26 个且 None 必须占一位；`topK=26` 时实际传 25 张卡。
2. **Spring AI 只用 `spring-ai-model` 抽象**，OpenAI 兼容传输层自己写（`OpenAiCompatibleEmbeddingModel`）。若引入 `spring-ai-openai`，会把 chat 类一起带进依赖树，「不碰执行模型」就只能靠承诺、不能靠依赖树。换后端仍只改配置。
3. **召回向量只用 `applicableWhen` 的正面半句**。负例是给「排除」用的，混进相似度会变成正向信号；精判仍看到完整字段。
4. **输出格式示例压缩成字段名清单**，让完整骨架（含失败模式与退出条件）能落在默认 400 token 内。裁剪顺序固定：先丢格式示例，再丢失败模式，最后硬截断。
5. **placebo 长度对齐「本应注入的骨架长度」**，而不是固定长度。若本次没有合适卡，则对齐全库渲染长度的均值。
6. **卡片与任务集由同一作者（AI）产出**。「写卡的人不看任务生成器模板」这条纪律在单人 + AI 的开发形态下只能部分执行：任务文本由生成器模板产出、卡片在结构确定后撰写，但无法证明作者隔离。这一点必须写进实验报告的局限章节，不能含糊过去。

---

## 8. 目录

```
src/main/java/com/challenger/moderoute/
  api/        接口与 DTO
  card/       卡片模型、加载校验、版本哈希、只读快照
  recall/     embedding 后端、向量缓存、余弦召回
  decide/     选项映射、System One 客户端、标定、门控、规则降级
  render/     骨架渲染、占位骨架、token 估算
  service/    编排
  audit/      JSONL 审计
cards/        48 张模式卡
tasks/        任务集
tools/        任务集生成器（JDK 单文件）
scripts/      启动与冒烟脚本
docs/SPEC.md  接口与数据契约
runs/         向量缓存与审计日志（gitignore）
```

## 9. 依赖纪律

```powershell
mvn dependency:tree | Select-String -Pattern "openai|anthropic|chat|completion"
```

依赖树里不应出现任何执行模型 / chat / completion SDK。embedding 抽象允许，chat 模型 SDK 不允许。

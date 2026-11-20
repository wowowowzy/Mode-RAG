# ModeTest

> 本工程是 [ModeRAG](../README.md) 单仓库的一部分。完整环境要求、JEV 运行时包与一键启动见仓库根 README。

评测项目：验证 ModeRouter 注入的「推理骨架」是否真的改变执行模型的输出质量。

> ⚠️ **目录结构要求**
>
> 本项目必须与 `ModeRouter` 放在**同一父目录**下：
>
> ```
> 任意目录/
> ├─ ModeRouter/      ← 提供网关地址与密钥
> └─ ModeTest/        ← 本项目
> ```
>
> 原因：`application.yml` 通过 `../ModeRouter/.env.local` 读取 `OPENAI_BASE_URL` 与 `OPENAI_API_KEY`。
> 不同级会导致配置为空、模型调用返回 401（启动日志会打 ERROR 提示）。
> 确实要分开存放时，把 `ModeTest/.env.local` 按 `.env.local.example` 的注释填全即可。

本目录与服务项目 `../ModeRouter` 平级，两者只通过 HTTP 契约交互，不共享代码、不共享依赖。

## 快速启动

最省事的方式是上级目录的 `start.bat`：它会依次起 JEV（8765）、ModeRouter（18080）、本服务（18090）和前端（5173），停服用同目录的 `stop.bat`。
手动启动：

```powershell
# 1) 先起精判引擎（JEV，8765）与服务
cd ..\ModeRouter
powershell -File tools\start-jev.ps1          # 另开一个窗口
java -jar target\moderoute-1.0.0.jar          # 18080

# 2) 起评测后端
cd ..\ModeTest
mvn -q -DskipTests package                     # 只有改代码才需要重新打包
java -jar target\modetest-1.0.0.jar           # 18090

# 3) 起前端
cd web
npm run dev                                    # 5173（首次克隆后需先 npm install）
```

浏览器打开 `http://127.0.0.1:5173`。没有登录，直接进入。

## 页面

| 路由 | 内容 |
| --- | --- |
| `/` | 三个模式域：约束规划 / 立场对比 / 跨域通用 |
| `/d/:domain` | 该域任务列表 + 单个任务输入详情，确认后开测 |
| `/runs/:id` | 一次测试的两栏输出：直接调用 vs 注入骨架。`plan_v1` 会渲染成排产表 + 自检清单，Markdown 表格也会渲染 |
| `/records` | 测试记录列表 |

通用域没有任务（只建卡不评测），页面会显示「暂无任务」。

## 两臂怎么跑

```
baseline:  task ────────────────────────────────> ChatClient ──> 输出 A
ours:      task ──> POST /v1/enhance ──> prompt ──> ChatClient ──> 输出 B
```

- 两条臂共用同一个 `ChatClient`、同一模型、同温度、不限制输出 token
- 唯一差别是 `system` 段：baseline 为空，ours 为 ModeRouter 返回的骨架
- 每次记录 `action / cardId / confidence / libraryVersion / 三段耗时 / token 用量`

## 数据

| 内容 | 位置 |
| --- | --- |
| 开发集（188 条：planning 116 / debate 72） | `data/tasks/tasks.jsonl` |
| 冻结 holdout（40 条：planning 20 / debate 20） | `data/tasks/holdout-frozen.jsonl`（`meta.split=holdout`，不得参与调参） |
| 任务集生成规范 | `TASKS-SPEC.md` |
| 测试记录 | `var/modetest.db`（SQLite + WAL） |
| 三臂实验结果 / 盲评文件 | `var/three-arm/results.jsonl` / `blind.jsonl`（gitignore） |

旧的 200 条任务集已删除（模板化严重、方向重合）。现在的 188 条按 `TASKS-SPEC.md` 由 agent 逐条撰写，交付物只有 `tasks.jsonl`。每条 planning 任务带 `meta.demands`（订单 → 工时），供判定器核对「每单只做一次、工时正确」；仅靠 prompt 自然语言无法可靠解析出工时。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/domains` | 三个域与任务数 |
| GET | `/api/cards/summary` | 各域卡片数（来自 ModeRouter，60s 缓存） |
| GET | `/api/tasks?domain=planning` | 任务列表 |
| GET | `/api/tasks/{taskId}` | 单任务详情 |
| POST | `/api/runs` | 跑一次双臂，返回完整记录（阻塞 40–130 秒） |
| GET | `/api/runs/active` | **正在生成中的对比**（内存态，不落库） |
| GET | `/api/runs?limit=50` | 记录列表（不含正文） |
| GET | `/api/runs/{id}` | 记录详情（含两份输出），id 限定为数字 |
| POST | `/api/runs/{id}/judge` | 触发判定器（planning 约束核验 / debate LLM-judge），回填判定字段 |
| POST | `/api/arms/run` | 跑一次三臂实验（baseline / oracle / system），返回单臂结果与判定 |

> `POST /api/runs` 是同步阻塞的：一次双臂要跑 40–130 秒，期间数据库里还没有记录。
> 前端因此轮询 `/api/runs/active` 展示「生成中 + 当前阶段 + 已用时间」，
> 否则用户点完按钮在记录页看不到任何东西。该状态只存内存——进程重启后不可能有
> 还在跑的任务，写库反而会留下永远停在 PENDING 的僵尸记录。

## 三臂实验

回答「增强到底有没有用」：三臂共用同一执行模型与参数，唯一变量是 system 段。

| 臂 | system 段 | 目的 |
| --- | --- | --- |
| baseline | 只给 `plan_v1` / `debate_v1` 输出格式要求 | 控制格式变量（避免 `parsed=false` 被误判为答案错误） |
| oracle | `acceptableCards[0]` 的完整骨架（ModeRouter 只读端点） | 骨架本身的上限效应 |
| system | 现状：按域召回 + 精判自选卡 | 当前系统真实水平 |

```powershell
# 先启动 ModeRouter + ModeTest，再从本目录运行
python tools/three-arm.py --tasks data/tasks/holdout-frozen.jsonl --domain planning --limit 30
python tools/three-arm.py --tasks data/tasks/holdout-frozen.jsonl --domain debate --limit 30
python tools/three-arm-stats.py --in var/three-arm/results.jsonl
```

`results.jsonl` 是带臂标签的统一契约；`blind.jsonl` 去掉了臂、卡片、版本与耗时字段，
供独立评审盲评。统计脚本输出 `oracle−baseline`（骨架效应）、`system−oracle`（选卡损失）、
`system−baseline`（净效应）及 bootstrap 95% CI；主指标为 planning 约束全过率 / debate judge 均分。
每域至少 30 条才可据以下结论。

## 配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `modetest.router-url` | `http://127.0.0.1:18080` | ModeRouter 地址 |
| `modetest.router-timeout-ms` | `30000` | 增强调用超时 |
| `modetest.chat.temperature` | `0.2` | 两条臂共用 |
| `spring.ai.openai.*` | 读 `../ModeRouter/.env.local` | 复用服务的网关与 key；本目录 `.env.local` 可覆盖单项（如 `OPENAI_MODEL`），后导入的优先 |

换执行模型：改 `ModeTest\.env.local` 里的 `OPENAI_MODEL` 后重启后端，启动日志会打印 `执行模型：<model> · 网关 <url>`。网关地址与 key 仍来自 `../ModeRouter/.env.local`，不必复制。

> 注意：记录表目前不存模型名，换模型前后的记录会混在一起，比较时不能直接对齐。

## 已知情况

- 一次双臂测试约 40–130 秒：增强 3–10 s，两次模型调用各 20–60 s（qwen3.7-flash 会先输出一段推理再给正文）。
- **判定器已实现，但必须显式触发**：`POST /api/runs/{id}/judge` 对指定记录回填判定结果，结果写回 `baselineScore` / `oursScore` / `baselinePassed` / `oursPassed` / `judgeDetail` 五列。
  - planning：`PlanJudge` 把两份输出解析成 `plan_v1` 的 `assignments`，逐条核验 `capacity` / `window` / `mutex` / `precedence` / `ratio` 约束与 `meta.demands`（每单只做一次、工时正确）。**限制**：只认 `plan_v1` JSON，模型若未输出合法 JSON 则 `parsed=false`、计 0 分——这是格式不合规，不等于答案错误。
  - debate：`DebateJudge` 用 LLM-as-judge 对 `coverage / logic / structure / relevance` 四项各 0–5 打分并取均值，给出 winner。**限制**：judge 由同一模型家族产出，存在自评偏置；正式报告需配人工抽检。
  - 三臂实验（baseline / oracle / system）需另跑 `tools/three-arm.py`，本接口只判定双臂记录。
- `acceptableCards` 是 ModeRouter 精判质量的标注，供 `tools/evaluate-routing.py` 复算选卡准确率，不参与双臂输出的判定。
- 若模型把预算耗在 reasoning 上，正文可能为空；记录里会写明「输出为空（模型未返回正文）」。

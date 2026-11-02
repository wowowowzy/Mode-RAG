# ModeTest 任务集生成规范

> 交付对象：负责生成评测任务集的 agent / 工程师
> 版本：2026-10-28（v2：只定格式与底线，任务内容由执行方自由发挥）

---

## 0. 一句话任务

生成一份**贴近真实生活、场景多样化、可机器判定**的中文任务集，写入
`data/tasks/tasks.jsonl`（相对本仓库根目录）。

不需要你写生成器、校验脚本或报告，**只需要一个文件**。任务逐条撰写，禁止用模板批量套数字。

---

## 1. 这套任务用来干什么（知道目的，才好选内容）

被测系统是一个「推理骨架」服务：输入任务文本 → 输出一段可注入的解题骨架。
评测项目对同一条任务跑两臂：

```
baseline: 任务原文 ─────────────────────────> 执行模型 ─> 输出 A
ours:     任务原文 ─> 骨架服务 ─> 骨架 ─> 执行模型 ─> 输出 B
```

判定器会按任务里的**结构化约束**逐条机械校验输出 A / B，并判断骨架服务选卡是否正确。
所以任务集只对两件事有硬性要求：**约束能被程序校验**、**场景彼此不重复**。其余都看你的判断。

---

## 2. 交付物

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| 任务集 | `data/tasks/tasks.jsonl`（相对本仓库根目录） | 一行一个 JSON，UTF-8 **无 BOM**，直接覆盖现有文件 |

- 不要生成器、不要校验脚本、不要报告。
- 当前文件是 0 字节空文件（后端需要文件存在才能启动）。

---

## 3. 规模（建议值，不是硬性配额）

| 域 | 条数建议 | 说明 |
| --- | --- | --- |
| `planning`（约束规划） | ≥ 80 条 | 有唯一可验证答案的任务，主要评测对象 |
| `debate`（立场对比） | ≥ 60 条 | 双方立场论证类任务，验证泛化 |
| `general`（跨域通用） | **本期不写** | 该域的卡没有机械输出契约，判不了，别加 |

总量 160–200 条即可，宁少勿水。难度自然分布，但不要全简单或全难。

---

## 4. 数据格式（判定器按这个读，别自创）

每行一个 JSON 对象：

```json
{
  "taskId": "plan-001",
  "domain": "planning",
  "prompt": "……中文任务描述……",
  "constraints": [
    { "cid": "C1", "type": "capacity", "text": "产线 L1 每日可用工时不超过 6 小时",
      "params": { "line": "L1", "perDayHours": 6 } }
  ],
  "stances": [],
  "acceptableCards": ["planning-capacity-first", "planning-window-first"],
  "meta": { "family": "capacity-window", "split": "val", "difficulty": "medium",
            "demands": [{"order": "K1", "hours": 4}, {"order": "K2", "hours": 3}] }
}
```

字段规则：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `taskId` | string | 全库唯一。`plan-001` / `debate-001`，按域三位补零连续编号 |
| `domain` | string | 只能是 `planning` / `debate` |
| `prompt` | string | 1–4000 字，建议 120–600 字，纯文本，不要 Markdown 表格、不要 JSON |
| `constraints` | array | `planning` 必填非空；`debate` 必须是 `[]` |
| `stances` | array | `debate` 必填（≥2 条）；`planning` 必须是 `[]` |
| `acceptableCards` | string[] | 2–4 个卡片 id，必须存在于附录 A 且与 `domain` 同域 |
| `meta.family` | string | 简短分类名（如 `capacity-window`、`policy`），你自行起，但同类任务要同名 |
| `meta.split` | string | `val` 或 `test` |
| `meta.difficulty` | string | `easy` / `medium` / `hard` |
| `meta.scenario` | string | 可选。8–16 字场景短标签，用于你自查重复度 |
| `meta.demands` | array | **planning 必填**，debate 不要。`[{"order":"K1","hours":4}]`，必须覆盖 prompt 里每一张订单，`hours` 为整数工时（与 `capacity` 的 `perDayHours` 同单位） |

**不要写顶层 `split` / `family`**，程序只读 `meta` 里的那份。

---

## 5. 内容要求：贴近生活 + 多样化

### 5.1 要像真人遇到的需求

问题要让人读起来觉得"这是我工作上真会碰到的"。允许虚构公司和名字，但业务上下文必须真实：

- ✅ 好：**"一家连锁便利店要为 4 家门店安排下周的理货班次，每家店每天最多 3 人，有 6 名员工，其中小王周三请假……"**
- ✅ 好：**"客服团队 5 人，未来 7 天话务量每天不同，有人每天最多接 8 小时，小张周四到周五培训不能排班……"**
- ❌ 差：**"共有 3 条产线（[L1, L2, L3]），每日可用工时分别为 6、8、6 小时……"**（数字填空题，离生活远）

### 5.2 场景要广

可以覆盖但不限于这些生活化方向：

- **planning**：门店/客服/维修工排班、家政服务安排、仓库拣货计划、配送装车、会议室/机房预约、课程/考试安排、预算与人力分配、原材料采购批次、仪器设备共享、发货计划、活动物料分配
- **debate**：远程办公该不该全面推行、加班换绩效还是招人、门店开在商圈还是社区、预算先给研发还是销售、新系统要不要上、环保成本该谁承担、定价公平性、外包还是自建、裁员还是冻结招聘、绩效考核看结果还是过程

### 5.3 不要同质化

- 相邻的任务不要连用同一个场景（写完一条就换方向）
- 措辞要像人说话：可以直接叙述，也可以第一人称（"我是门店经理，……"），不要每条都同一个句式开头
- 对象名、规模、数字要有差异：员工 5 人和 23 人是不同的问题，别都写成 3 个对象 7 张单

两条硬红线（违反即退回）：

1. 任意两条任务不能"除数字外基本一样"
2. 相同句式开头的任务 ≤ 总数的 1/4

### 5.4 各域的特殊要求

**planning**：

- 必须**有解**：写完后自己按约束解一遍，解不出来就改
- `constraints` 必须覆盖 prompt 里所有硬约束，且 `params` 与 prompt 中的实体、数字完全一致
- 模型的输出契约是 `plan_v1`：`{"assignments":[{"order","line","day","startHour","hours"}],"verification":[{"cid","passed","detail"}]}`
- 所以约束里用到的实体（人员/门店/工位）命名要和这个 JSON 的 `order/line` 字段兼容，`params` 里的 `line` 就用你任务里的实体 id（门店就叫 M1、M2 也行）

**debate**：

- 双方立场都要**站得住**，不能一方明显荒谬
- `stances` 给双方立场和论据方向，但 prompt 里不要替模型选边（不要写"请支持甲方"）
- 模型的输出契约是 `debate_v1`：`{"stances":[{"label","thesis","evidence","objections","concessions"}],"verdict"}`

---

## 6. 判定与卡片（不能省，判定器靠这些）

- `constraints[].params` 是**判定唯一依据**，支持的类型必须用下面这套 schema，别自创字段：

| type | params | 语义 |
| --- | --- | --- |
| `capacity` | `{"line":"M1","perDayHours":8}` | 某实体单日上限 |
| `window` | `{"order":"O7","dueDay":4}` | 最晚完成日 |
| `window` | `{"order":"O7","earliestDay":2,"latestDay":5}` | 时间区间 |
| `precedence` | `{"before":"A","after":"B"}` | A 必须在 B 之前 |
| `mutex` | `{"orders":["A","B"],"scope":"same-line-day"}` | 不得同实体同日 |
| `assignment` | `{"order":"A","line":"M1"}` 或 `{"order":"A","lines":["M1","M2"]}` | 指定/限定实体 |
| `ratio` | `{"line":"M1","minShare":0.2,"maxShare":0.4}` | 占比区间 |

  > 语义里 `line` 是通用叫法，你的任务里可以是门店/产线/员工/工位，`params` 里就用你任务中的实体 id。

- `acceptableCards` 选「这道题的正确解法族」对应的卡（例如带时间窗的排程任务 → `planning-window-first` + `planning-capacity-first`）。宁少勿凑。
- 任何情况下**不要把卡片名、策略名、骨架结构、参考答案写进 `prompt`**。

---

## 7. 质量红线

1. `prompt` 与 `constraints[].params` 自洽（数字、实体一一对应）
2. planning 必须有解
3. 硬约束必须能用第 6 节 schema 表达；"尽量均衡""合理分配"这类没法校验的表述不要当硬约束写
4. prompt 不含卡片名/策略名/答案
5. 全中文，无英文说明，无乱码 `U+FFFD`
6. 单位一致（"小时"就是小时，别混"天"）

---

## 8. split 划分

- `meta.split` = `val` / `test`，比例 1 : 2
- 划分按场景分散：同场景类型的任务不要全在一边
- 简单做法：写完按顺序每 3 条标 1 个 `val`、2 个 `test`，再人工扫一遍保证场景不扎堆
- `val` 用于开发期看效果，`test` 只在最后跑一次

---

## 9. 交付前自检（不交脚本，但逐项过）

| # | 检查项 | 通过条件 |
| --- | --- | --- |
| 1 | 编码 | UTF-8 无 BOM，无 `U+FFFD`，无空行 |
| 2 | JSON | 每行能解析为对象 |
| 3 | Schema | 第 4 节字段齐全、类型正确、无多余顶层字段 |
| 4 | 规模 | 总量 160–200，planning ≥ 80，debate ≥ 60 |
| 5 | ID | 全库唯一，格式正确 |
| 6 | 卡片 | `acceptableCards` 存在于附录 A 且同域 |
| 7 | 长度 | `prompt` 1–4000 字 |
| 8 | 结构 | planning 的 constraints 非空；debate 的 stances ≥ 2、constraints 为空 |
| 9 | 一致 | `params` 的实体 id 与数值都能在 `prompt` 里找到 |
| 10 | 可解 | 每条 planning 都自己解过 |
| 11 | 多样化 | 第 5.3 节两条硬红线 + 自己抽查：随机抽 10 对，无"只换数字"对 |
| 12 | split | 1 : 2，场景不扎堆 |
| 13 | 工时表 | planning 的 `meta.demands` 覆盖全部订单，工时与 prompt 声明一致，且单笔不超过任一天容量 |

---

## 10. 写入方式注意（中文 Windows 环境，踩过坑）

1. UTF-8 **无 BOM**，一行一个 JSON，行尾 `\n`。
2. 写文件必须显式指定 UTF-8：
   - Python：`open(path, "w", encoding="utf-8", newline="\n")`
   - PowerShell：`[IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))`
3. 用 Java 处理中文**不要**用 `java Xxx.java` 单文件模式（按 GBK 读源码会把中文变 `U+FFFD`）；要编译就 `javac -encoding UTF-8`。
4. 写完按字节严格解码搜 `U+FFFD`，必须为 0。别只看控制台（代码页会把正常中文也显示成乱码）。

---

## 11. 附录 A：卡片库（`acceptableCards` 只能取同域）

**planning（16）**
`planning-balance-load`、`planning-capacity-first`、`planning-constraint-relaxation`、
`planning-feasibility-lower-bound`、`planning-full-replan`、`planning-greedy-then-repair`、
`planning-hard-soft-layering`、`planning-incremental-repair`、`planning-makespan-first`、
`planning-mutex-serialize`、`planning-mutex-split`、`planning-precedence-backward`、
`planning-precedence-topo`、`planning-ratio-maxmin`、`planning-ratio-proportional`、
`planning-window-first`

**debate（16）**
`debate-assumption-audit`、`debate-claim-premise-chain`、`debate-common-ground`、
`debate-criteria-first`、`debate-decision-threshold`、`debate-evidence-gap`、
`debate-evidence-hierarchy`、`debate-fact-value-split`、`debate-fallacy-check`、
`debate-falsification`、`debate-premise-gap`、`debate-red-team`、`debate-scenario-stress`、
`debate-steelman-each`、`debate-steelman-then-attack`、`debate-value-tradeoff`

选卡示例：带时间窗的排程任务 → `planning-window-first` + `planning-capacity-first`；
员工排班（有互斥：一个人不能同时两班）→ `planning-mutex-serialize` + `planning-capacity-first`；
加班/招人取舍 → `debate-value-tradeoff` + `debate-criteria-first`。

---

## 12. 附录 B：示例（贴近生活的写法）

**planning**

```json
{"taskId":"plan-001","domain":"planning","prompt":"一家烘焙工作室有 3 台烤箱（A、B、C），工作日是周一至周五。周五要交付 6 种蛋糕订单（K1–K6），每种蛋糕需要占用烤箱的时间分别是 4、3、6、2、5、4 小时，同一种蛋糕必须一次性在一台烤箱的某一天内烤完，不能拆开。每台烤箱每天最多可用 8 小时。其中 K3 最晚要在周四烤完。请给出满足容量与交期的排烤方案。","constraints":[{"cid":"C1","type":"capacity","text":"烤箱 A 每天最多可用 8 小时","params":{"line":"A","perDayHours":8}},{"cid":"C2","type":"capacity","text":"烤箱 B 每天最多可用 8 小时","params":{"line":"B","perDayHours":8}},{"cid":"C3","type":"capacity","text":"烤箱 C 每天最多可用 8 小时","params":{"line":"C","perDayHours":8}},{"cid":"C4","type":"window","text":"K3 最晚在周四烤完","params":{"order":"K3","dueDay":4}}],"stances":[],"acceptableCards":["planning-capacity-first","planning-window-first"],"meta":{"family":"capacity-window","split":"val","difficulty":"medium","scenario":"烘焙烤箱排程"}}
```

**debate**

```json
{"taskId":"debate-001","domain":"debate","prompt":"一家 80 人的软件公司正在讨论是否全面转为远程办公。支持方认为远程能扩大招聘半径、节省办公成本并提升员工满意度；反方认为跨团队协作效率、新人培养和紧急响应会受损。请分别论证双方的依据，指出关键分歧点，并给出你的取舍建议。","constraints":[],"stances":[{"label":"甲方","position":"全面远程：招聘半径扩大、办公成本下降、资深员工留存率提升"},{"label":"乙方","position":"保持每周到岗 3 天：跨团队协作依赖面对面、新人培养周期变长、紧急问题响应变慢"}],"acceptableCards":["debate-steelman-each","debate-fact-value-split"],"meta":{"family":"policy","split":"val","difficulty":"medium","scenario":"远程办公取舍"}}
```

---

## 13. 附录 C：这些字段被谁读（便于自查）

| 消费方 | 读什么 | 影响 |
| --- | --- | --- |
| `TaskRepository` | `taskId / domain / prompt / constraints / stances / acceptableCards / meta` | 启动时读入，文件缺失拒绝启动 |
| 前端 | `meta.split` / `meta.family` | 页面上的「planning · capacity-window · val」 |
| 测试流程 | **只读 `prompt`** | 发给骨架服务与执行模型；约束与可接受卡片不会外传 |
| 判定器（待实现） | `constraints[].params` + 模型的 `plan_v1` | 逐条机械校验 |
| 评分（待实现） | `acceptableCards` | 判断选卡是否正确 |

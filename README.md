# ModeRAG

**推理骨架注入（Reasoning-Skeleton Injection）**：给定任务文本，从 48 张「推理模式卡」里判断该不该注入、注入哪一张，返回一段可直接拼进 prompt 的文本。

执行模型不变，变的只是这一次的 system 段。

> 服务本身不训练模型、不调用执行模型。它是执行模型之前的一道「决策层」。

---

## 仓库构成

| 目录 | 角色 | 端口 | 技术栈 |
| --- | --- | --- | --- |
| [`ModeRouter/`](ModeRouter/) | 召回 → 精判 → 门控 → 渲染，返回骨架文本 | 18080 | Spring Boot 3.5 / Java 17 |
| [`ModeTest/`](ModeTest/) | 双臂对照评测台 + 前端 | 18090 / 5173 | Spring Boot 3.5 + Vue 3 / Vite |

两者只通过 HTTP 契约交互，不共享代码、不共享依赖。

**JEV 推理引擎不在此仓库。** 它是 0.8B 的决策模型（torch CPU，约 2.4GB，含独立 Python 环境），以 `jev-runtime.zip` 单独分发。

## 架构

```
浏览器
  │
  ▼
Vite (5173) ──► ModeTest (18090) ──► ModeRouter (18080) ──► JEV (8765, CPU 精判)
                      │                    │
                      └──── 同一模型网关（chat 调用）  （embedding 只 ModeRouter 用）
```

一次 `POST /api/runs` 的动作序列：取骨架 → baseline 直调执行模型 → ours 带骨架调用 → 两份输出并排落库。

---

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 运行与编译都需要 |
| Maven | 3.9+ | 仅在编译时需要 |
| Node.js | 18+ | 仅前端，首次需 `npm install` |
| JEV 运行时包 | `jev-runtime.zip` | 约 1.4GB，**单独分发**，解压得到 `Jev-Style/` |
| 模型网关 | 任意 OpenAI 兼容 | 需要 chat + embeddings 两类接口 |

操作系统：Windows（根目录脚本为 `.bat`）。Linux/macOS 可按下方手动命令启动，但 sqlite-vec 扩展需换对应平台的 `vec0.so`。

## 目录结构

克隆后把 `jev-runtime.zip` 解压到仓库根，最终应为：

```
ModeRAG/
├─ ModeRouter/          ← 后端服务：召回 / 精判 / 门控 / 渲染
├─ ModeTest/            ← 评测台：双臂对照 + 前端
├─ Jev-Style/           ← 解压 jev-runtime.zip 得到，不入库
├─ start.bat            ← 一键启动四个服务
├─ stop.bat             ← 按端口停止
└─ README.md
```

> `ModeTest` 必须与 `ModeRouter` **同级**：它的 `application.yml` 通过
> `../ModeRouter/.env.local` 读取网关地址与密钥。不同级会静默取到空配置，
> 第一次调模型才报 401（启动日志已加 ERROR 提示）。

---

## 快速开始

### 1. 解压 JEV 运行时

把 `jev-runtime.zip` 解压到仓库根目录，确认出现 `Jev-Style/`（其中含 `model/`、`.venv/`、`Python312/`、`start-jev.bat`）。

### 2. 配置网关

**整个仓库只配这一份文件。**

```powershell
copy ModeRouter\.env.local.example ModeRouter\.env.local
```

填入：

```properties
OPENAI_API_KEY=<你的 key>
OPENAI_BASE_URL=https://<你的网关>/compatible-mode
OPENAI_EMBEDDING_MODEL=<embedding 模型名>
OPENAI_MODEL=<执行模型名>
JEV_BASE_URL=http://127.0.0.1:8765
```

其中 `OPENAI_MODEL` 是双臂对照用的执行模型，**只有 ModeTest 读它**；`OPENAI_EMBEDDING_MODEL` 只有 ModeRouter 读。两个项目共用这一份，是因为 `ModeTest/application.yml` 通过 `../ModeRouter/.env.local` 导入它。

`ModeTest\.env.local` **不需要创建**。只有两种情况才建：要给评测单独换执行模型，或两个项目不在同一父目录（读不到上面那份）。

### 3. 首次构建

> 这一步只需做一次。`start.bat` **不会**替你编译，它检测到 `target\*.jar` 或 `node_modules` 缺失时，
> 会直接列出下面三条命令并退出，不会留下半启动的状态。

```powershell
cd ModeRouter ; mvn -q -DskipTests package ; cd ..
cd ModeTest   ; mvn -q -DskipTests package ; cd ..
cd ModeTest\web ; npm install ; cd ..\..
```

### 4. 启动

双击 **`start.bat`**。它会按顺序起 JEV → ModeRouter → ModeTest → 前端，并在每步等待端口就绪（ModeRouter 还会等 `/healthz` 返回 `UP`，确保向量库预热完成再放行）。

启动前它会按当前位置重写 `Jev-Style\.venv\pyvenv.cfg` —— JEV 分发包自带的 venv 记录的仍是打包时的绝对路径，不重写会报 `No Python at ...`。

大约 40–60 秒后浏览器打开 <http://127.0.0.1:5173>。

停止：双击 **`stop.bat`**（按端口杀进程，会连 JEV 一起停）。

<details>
<summary>手动启动（不用 bat 时）</summary>

```powershell
# 1) JEV（另开窗口，不要关）
cd Jev-Style
powershell -ExecutionPolicy Bypass -File start-jev.bat

# 2) ModeRouter
cd ModeRouter
java -jar target\moderoute-1.0.0.jar

# 3) ModeTest
cd ModeTest
java -jar target\modetest-1.0.0.jar

# 4) 前端
cd ModeTest\web
npm run dev
```
</details>

---

## 验证

```powershell
# 骨架服务：向量库就绪、48 张卡
curl.exe http://127.0.0.1:18080/healthz

# 走一次真实判定
curl.exe -X POST http://127.0.0.1:18080/v1/enhance `
  -H "Content-Type: application/json" `
  -d "{\"task\":\"为三条产线排布五张订单，订单带最晚完成日\"}"
```

`healthz` 期望返回：

```json
{"status":"UP","libraryVersion":"<12 位>","cardCount":48,"vectorsReady":true,
 "embeddingModel":"...","vectorStore":"sqlite","decider":"jev-local"}
```

`/v1/enhance` 返回 `meta.action` 为 `INJECT` / `FALLBACK` / `DEGRADED`。若一直是 `DEGRADED`，多半是 JEV 没起或网关不可达，看响应里的 `degradedReason`。

## 常用配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `MODEROUTE_EMBEDDING` | `openai` | 设为 `mock` 可离线自检，不联网 |
| `MODEROUTE_DECIDER` | `jev-local` | `mock` 同样用于离线自检 |
| `MODEROUTE_TEMPERATURE` | `0.72` | 置信度温度缩放，在验证集上拟合过 |
| `MODEROUTE_RULE_MIN_SCORE` | `0.04` | 规则降级时「都不合适」的等效阈值 |
| `MODETEST_ROUTER_URL` | `http://127.0.0.1:18080` | ModeTest 指向的 ModeRouter |

完整清单见各项目 README。

---

## 常见问题

**`缺少 sqlite-vec 扩展`**
`ModeRouter/lib/native/vec0.dll`（283KB）**已随仓库提供**。若被误删，执行 `powershell -ExecutionPolicy Bypass -File ModeRouter\tools\fetch-sqlite-vec.ps1` 重新下载并校验 SHA256。

**JEV 窗口一闪而过，或报 `No Python at '...'`**
JEV 分发包里的 `.venv` 记录的是打包时的绝对路径，解压到别处就失效。`start.bat` 每次启动前会按当前位置重写 `Jev-Style\.venv\pyvenv.cfg`，正常不会遇到。若手动启动，请用 `Jev-Style\start-jev.bat`（它同样会先修配置），**不要**直接调 `.venv\Scripts\python.exe`。

**`start.bat` 报「[缺少] ...」然后退出**
还没构建，或 `node_modules` 没装。按屏幕上的三条命令执行一次即可，不需要重复。

**端口被占用**
四个端口 `8765 / 18080 / 18090 / 5173`。先跑 `stop.bat` 再启动；`start.bat` 本身也会跳过已在运行的服务。

**前端 502 或页面空白**
后端没起来。看对应窗口的日志，或用 `curl.exe http://127.0.0.1:18090/api/domains` 判断 ModeTest 是否存活。

**一次对照很慢**
正常。单条要跑两次执行模型，实测 2 分钟左右。记录页会实时显示「生成中 + 当前阶段 + 已用秒数」，中途可以离开任务页。

**同一任务提示「正在测试中」**
同一任务不允许并发跑，避免产生重复样本（返回 409）。等当前这条结束即可。

---

## 数据与产物

**入库的**（必要资产）：
- `ModeRouter/data/cards/` — 48 张推理模式卡（3 域 × 16 张）
- `ModeRouter/lib/native/vec0.dll` — Windows x64 的 sqlite-vec 扩展
- `ModeTest/data/tasks/tasks.jsonl` — 188 条评测任务（planning 116 / debate 72）

**不入库的**（已在 `.gitignore` 排除）：
- `target/`、`node_modules/`、`web/dist/` — 构建产物与依赖
- `var/`、`logs/` — 向量库、审计 JSONL、运行记录
- `.env.local` — 凭据
- `Jev-Style/`、`*.safetensors`、`*.zip` — 模型与分发包

---

## 设计约束（读代码前值得先知道）

- **`None` 是候选池里的一项，不是阈值**。决策模型必须从封闭集里挑一个，所以让「都不合适」能被投出来，而不是事后卡阈值。
- **单字母 A–Z 是被引擎逼的**。靠预填位置的 logit 出概率，多 token 标签会退化；26 个字母里 `None` 占一位，实际最多 25 张卡。
- **字母按 cardId 字典序排，不按分数排**。分数随 embedding 版本漂移，按分数排会让同一任务在不同版本下拿到不同字母，可复现性就没了。
- **删库即重算**。`(库版本, embedding 模型, 卡片集合, 维度)` 任一不匹配就整批重算，所以删掉 `var/moderoute.db` 重启后响应逐字节一致。
- **精判只喂 `applicableWhen`**。`steps` / `failureModes` 不进输入，避开上下文稀释。

## 许可

`lib/native/vec0.dll` 来自 [sqlite-vec](https://github.com/asg017/sqlite-vec)，Apache-2.0 / MIT 双许可。其余代码未声明许可证。

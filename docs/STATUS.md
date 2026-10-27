# 当前状态（2026-10-27 本轮交付）

本轮范围：**服务 + 48 张模式卡 + 任务集**。实验脚本、7 臂执行与报告留到下一轮。

## 已实测通过

| 项 | 结果 |
| --- | --- |
| 构建 | `mvn package` 通过，单 jar `target/moderoute-1.0.0.jar`（34.6 MB） |
| 测试 | 39 个用例全绿（接口契约、降级、版本归一化、字母映射、门控、渲染、精判客户端） |
| 卡片 | 48 张，3 域 × 8 族 × 2 变体；启动期逐字段校验通过 |
| 库版本 | `42858df80a60` |
| embedding | `qwen3.7-text-embedding`，1024 维，网关限制 batch ≤20（已分片） |
| 精判 | 本地 JEV `jev-style-0.8b-decision-v3`，torch CPU，单次约 2.4 s |
| 端到端 | `POST /v1/enhance` → `INJECT`，`planning-capacity-first`，渲染 505 字 / 388 token |
| 确定性 | 同一请求两次 `prompt` 逐字节相同 |
| 降级 | 精判端口不可达时 200 + `DEGRADED`，规则选卡仍有可用骨架 |
| OpenAPI | 仅暴露 `/v1/enhance`、`/v1/cards`、`/healthz`，`override` 不出现在文档里 |
| 依赖纪律 | enforcer 拦截 chat/completion SDK；依赖树只有 `spring-ai-model` / `spring-ai-commons` / `spring-ai-template-st` |

## 环境

- 代码：`E:\Codex`（`E:\挑战者` 已清空）
- JEV：依赖、pip 缓存、HF 缓存、权重（1.44 GB）全部在 `D:\Application\Jev-Style`
  - 权重路径 `D:\Application\Jev-Style\model\model.safetensors`
  - VC++ 运行库缺失，已把所需 DLL 复制进 `.venv\Lib\site-packages\torch\lib`，未做系统安装
  - 启动：`powershell -File scripts\start-jev.ps1`（自动用 `--model-dir`）
- 服务：`java -jar target\moderoute-1.0.0.jar`，端口 18080
- 冒烟：`powershell -File scripts\smoke.ps1`

## 下一轮（实验）

1. 执行器独立目录 + 独立依赖（不得进服务依赖树），实现 7 臂：baseline / placebo / random / fixedBest / ours / oursNoGate / oracle。
2. 验证集 60 条上拟合温度、定阈值、选 fixedBest；测试集 140 条只报告。
3. 指标：约束全通过率（主）、召回命中率、注入率、高置信档精度、ECE、跨域误选率、注入 token 数、延迟。
4. k 扫描 8/12/16/26，用于回答「为什么不直接全库精判」。
5. 结构判定器（capacity / precedence / mutex / window / assignment / ratio）与 plan_v1 / debate_v1 解析器。
6. 报告必须写明：`general` 域已建卡但未纳入评测；卡片与任务模板同一作者，作者隔离无法证明。

## 已知偏差

见 `README.md` 第 7 节（25 卡上限、只用 Spring AI 抽象、召回剔除负例、输出格式示例压缩、placebo 长度对齐、作者隔离局限）。

package com.challenger.modetest.dto;

/**
 * 正在进行的双臂对比。这是运行期状态，不落库：
 * 进程重启后不可能有「还在跑」的任务，所以内存就是它唯一正确的存储位置。
 * 若改为写库，崩溃会留下永远停在 PENDING 的僵尸记录。
 *
 * @param phase     当前阶段，给前端展示「卡在哪一步」
 * @param elapsedMs 已耗时，前端据此显示计时
 */
public record ActiveRunView(
        String taskId,
        String domain,
        String phase,
        String startedAt,
        long elapsedMs) {
}

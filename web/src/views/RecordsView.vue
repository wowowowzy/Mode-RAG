<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type ActiveRun, type TestRun } from '../api'

const router = useRouter()
const runs = ref<TestRun[]>([])
const active = ref<ActiveRun[]>([])
const loading = ref(true)
const error = ref('')

// 用于让「生成中」的计时器在两次轮询之间继续走动
const nowTick = ref(Date.now())
let pollTimer: number | undefined
let tickTimer: number | undefined

async function refresh(silent = false) {
  try {
    const [activeRows, runRows] = await Promise.all([api.activeRuns(), api.runs(100)])
    active.value = activeRows
    runs.value = runRows
    if (!silent) error.value = ''
  } catch (e) {
    // 轮询失败不覆盖已有列表，避免网络抖动把页面清空
    if (!silent) error.value = e instanceof Error ? e.message : '加载失败'
  }
}

onMounted(async () => {
  await refresh()
  loading.value = false
  // 生成期要持续看到进度，所以固定轮询；列表最多 100 行，开销可忽略
  pollTimer = window.setInterval(() => void refresh(true), 2000)
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 500)
})

onUnmounted(() => {
  if (pollTimer !== undefined) clearInterval(pollTimer)
  if (tickTimer !== undefined) clearInterval(tickTimer)
})

function shortTime(value: string) {
  return value.replace('T', ' ').slice(0, 19)
}

function elapsedOf(run: ActiveRun) {
  const started = Date.parse(run.startedAt)
  if (Number.isNaN(started)) return (run.elapsedMs / 1000).toFixed(1)
  return Math.max(0, (nowTick.value - started) / 1000).toFixed(1)
}
</script>

<template>
  <header class="mb-6">
    <h1 class="font-serif text-2xl font-semibold text-white/95 md:text-3xl">测试记录</h1>
    <p class="mt-2 text-sm text-white/70">
      最近 {{ runs.length }} 条<template v-if="active.length">，另有 {{ active.length }} 条生成中</template>。点击查看两份输出。
    </p>
  </header>

  <p v-if="error" class="mb-4 rounded-lg border border-white/10 bg-[#3a3a3c] px-4 py-3 text-sm text-white/80">
    {{ error }}
  </p>

  <!-- 生成中：来自服务端内存，不是数据库记录，所以没有 id 也点不进去 -->
  <section v-if="active.length" class="mb-4 overflow-hidden rounded-xl border border-white/8 bg-[#2c2c2e]">
    <header class="flex items-center gap-2 border-b border-white/8 px-4 py-3">
      <span class="inline-block h-2 w-2 animate-pulse rounded-full bg-[#30d158]"></span>
      <h2 class="font-serif text-sm font-semibold text-white/95">正在生成</h2>
      <span class="font-mono text-[11px] text-white/40">每 2 秒刷新</span>
    </header>
    <ul>
      <li
        v-for="item in active"
        :key="item.taskId"
        class="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-white/8 px-4 py-3 text-xs last:border-b-0"
      >
        <span class="font-mono text-white/80">{{ item.taskId }}</span>
        <span class="font-mono text-[11px] text-white/40">{{ item.domain }}</span>
        <span class="text-white/70">{{ item.phase }}</span>
        <span class="ml-auto font-mono text-white/50">已用 {{ elapsedOf(item) }}s</span>
      </li>
    </ul>
  </section>

  <div v-if="loading" class="text-sm text-white/40">加载中…</div>

  <div
    v-else-if="runs.length === 0 && active.length === 0"
    class="rounded-xl border border-white/8 bg-[#2c2c2e] p-6 text-sm text-white/60"
  >
    还没有记录。回到任意任务页，点击「用这个任务测试」生成第一条。
  </div>

  <div v-else-if="runs.length > 0" class="overflow-hidden rounded-xl border border-white/8 bg-[#2c2c2e]">
    <table class="w-full border-collapse text-left text-xs">
      <thead>
        <tr class="border-b border-white/8 text-white/40">
          <th class="px-4 py-3 font-normal">#</th>
          <th class="px-4 py-3 font-normal">任务</th>
          <th class="px-4 py-3 font-normal">动作</th>
          <th class="px-4 py-3 font-normal">卡片</th>
          <th class="px-4 py-3 font-normal">置信度</th>
          <th class="px-4 py-3 font-normal">耗时</th>
          <th class="px-4 py-3 font-normal">时间</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="run in runs"
          :key="run.id"
          class="cursor-pointer border-b border-white/8 transition-colors duration-200 ease-out last:border-b-0 hover:bg-white/5"
          @click="router.push(`/runs/${run.id}`)"
        >
          <td class="px-4 py-3 font-mono text-white/50">{{ run.id }}</td>
          <td class="px-4 py-3 font-mono text-white/80">{{ run.taskId }}</td>
          <td class="px-4 py-3 font-mono text-white/70">{{ run.action }}</td>
          <td class="px-4 py-3 font-mono text-white/70">{{ run.cardId ?? '—' }}</td>
          <td class="px-4 py-3 font-mono text-white/70">{{ run.confidence.toFixed(4) }}</td>
          <td class="px-4 py-3 font-mono text-white/50">{{ run.routerMs }} / {{ run.baselineMs }} / {{ run.oursMs }} ms</td>
          <td class="px-4 py-3 font-mono text-white/40">{{ shortTime(run.createdAt) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

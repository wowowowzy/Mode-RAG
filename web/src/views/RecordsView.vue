<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type TestRun } from '../api'

const router = useRouter()
const runs = ref<TestRun[]>([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    runs.value = await api.runs(100)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function shortTime(value: string) {
  return value.replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <header class="mb-6">
    <h1 class="font-serif text-2xl font-semibold text-white/95 md:text-3xl">测试记录</h1>
    <p class="mt-2 text-sm text-white/70">最近 {{ runs.length }} 条，点击查看两份输出。</p>
  </header>

  <p v-if="error" class="mb-4 rounded-lg border border-white/10 bg-[#3a3a3c] px-4 py-3 text-sm text-white/80">
    {{ error }}
  </p>
  <div v-if="loading" class="text-sm text-white/40">加载中…</div>

  <div v-else-if="runs.length === 0" class="rounded-xl border border-white/8 bg-[#2c2c2e] p-6 text-sm text-white/60">
    还没有记录。回到任意任务页，点击「用这个任务测试」生成第一条。
  </div>

  <div v-else class="overflow-hidden rounded-xl border border-white/8 bg-[#2c2c2e]">
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

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type TestRun } from '../api'
import PlanOutput from '../components/PlanOutput.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()

const run = ref<TestRun | null>(null)
const error = ref('')
const loading = ref(true)

async function load() {
  loading.value = true
  error.value = ''
  try {
    run.value = await api.runDetail(props.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function fmtMs(value: number) {
  return value > 0 ? `${value} ms` : '—'
}
</script>

<template>
  <header class="mb-6 flex flex-wrap items-end justify-between gap-4">
    <div>
      <h1 class="font-serif text-2xl font-semibold text-white/95 md:text-3xl">对照结果</h1>
      <p class="mt-1 font-mono text-xs text-white/40">run #{{ props.id }} · {{ run?.taskId }}</p>
    </div>
    <button
      type="button"
      class="rounded-lg border border-white/12 px-4 py-2 text-sm text-white/70 transition-colors duration-200 ease-out hover:bg-white/5"
      @click="router.push('/records')"
    >
      查看所有记录
    </button>
  </header>

  <p v-if="error" class="mb-4 rounded-lg border border-white/10 bg-[#3a3a3c] px-4 py-3 text-sm text-white/80">
    {{ error }}
  </p>
  <div v-if="loading" class="text-sm text-white/40">加载中…</div>

  <template v-if="run">
    <div
      v-if="run.error"
      class="mb-6 rounded-xl border border-white/10 bg-[#3a3a3c] p-4 text-sm leading-relaxed text-white/80"
    >
      <span class="font-mono text-xs text-white/40">ERROR</span>
      <p class="mt-2">{{ run.error }}</p>
    </div>

    <section class="mb-6 rounded-xl border border-white/8 bg-[#2c2c2e] p-4 md:p-6">
      <div class="flex flex-wrap gap-x-8 gap-y-3 text-xs text-white/70">
        <div>
          <div class="text-white/40">门控动作</div>
          <div class="mt-1 font-mono text-white/90">{{ run.action }}</div>
        </div>
        <div>
          <div class="text-white/40">命中卡片</div>
          <div class="mt-1 font-mono text-white/90">{{ run.cardId ?? '未注入' }}</div>
        </div>
        <div>
          <div class="text-white/40">置信度</div>
          <div class="mt-1 font-mono text-white/90">{{ run.confidence.toFixed(4) }}</div>
        </div>
        <div>
          <div class="text-white/40">库版本</div>
          <div class="mt-1 font-mono text-white/90">{{ run.libraryVersion || '—' }}</div>
        </div>
        <div>
          <div class="text-white/40">增强 / baseline / ours</div>
          <div class="mt-1 font-mono text-white/90">
            {{ fmtMs(run.routerMs) }} / {{ fmtMs(run.baselineMs) }} / {{ fmtMs(run.oursMs) }}
          </div>
        </div>
        <div>
          <div class="text-white/40">prompt tokens（baseline / ours）</div>
          <div class="mt-1 font-mono text-white/90">
            {{ run.baselinePromptTokens ?? '—' }} / {{ run.oursPromptTokens ?? '—' }}
          </div>
        </div>
      </div>
      <p v-if="!run.error && run.action !== 'INJECT'" class="mt-4 border-t border-white/8 pt-3 text-xs text-white/50">
        本次没有注入骨架（{{ run.action }}），两臂结果理论上应当接近。
      </p>
    </section>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <section class="rounded-xl border border-white/8 bg-[#2c2c2e]">
        <header class="flex items-center justify-between border-b border-white/8 px-4 py-3">
          <h2 class="font-serif text-sm font-semibold text-white/95">直接调用（baseline）</h2>
          <span class="font-mono text-xs text-white/40">{{ fmtMs(run.baselineMs) }}</span>
        </header>
        <PlanOutput :text="run.baselineText" />
      </section>

      <section class="rounded-xl border border-white/8 bg-[#2c2c2e]">
        <header class="flex items-center justify-between border-b border-white/8 px-4 py-3">
          <h2 class="font-serif text-sm font-semibold text-white/95">注入骨架（ours）</h2>
          <span class="font-mono text-xs text-white/40">{{ fmtMs(run.oursMs) }}</span>
        </header>
        <PlanOutput :text="run.oursText" />
      </section>
    </div>
  </template>
</template>

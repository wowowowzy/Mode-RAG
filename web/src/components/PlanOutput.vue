<script setup lang="ts">
import { computed, ref } from 'vue'
import MarkdownBlock from './MarkdownBlock.vue'

/**
 * 把模型输出渲染成人能看的样子。
 * 认得 plan_v1（assignments + verification）就画表格与自检清单；
 * 其余情况（或用户主动切换）显示原始文本。
 */
const props = defineProps<{ text: string | null }>()

interface Assignment {
  order: string
  line: string
  day: number
  startHour?: number
  hours: number
}

interface Verification {
  cid: string
  passed?: boolean
  detail?: string
}

interface Plan {
  assignments: Assignment[]
  verification: Verification[]
}

const showRaw = ref(false)

/** 去掉 ```json 围栏，取出中间的 JSON 对象。 */
function unwrapJson(raw: string): string {
  const trimmed = raw.trim()
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i)
  const body = fenced ? fenced[1].trim() : trimmed
  const start = body.indexOf('{')
  const end = body.lastIndexOf('}')
  return start >= 0 && end > start ? body.slice(start, end + 1) : body
}

const plan = computed<Plan | null>(() => {
  const raw = props.text?.trim()
  if (!raw || !raw.includes('assignments')) {
    return null
  }
  try {
    const parsed = JSON.parse(unwrapJson(raw)) as Partial<Plan>
    if (!Array.isArray(parsed.assignments) || parsed.assignments.length === 0) {
      return null
    }
    const assignments = parsed.assignments.map((item) => ({
      order: String(item.order ?? '—'),
      line: String(item.line ?? '—'),
      day: Number(item.day ?? 0),
      startHour: item.startHour === undefined ? undefined : Number(item.startHour),
      hours: Number(item.hours ?? 0)
    }))
    return {
      assignments: assignments.sort((a, b) => a.day - b.day || a.line.localeCompare(b.line)),
      verification: Array.isArray(parsed.verification)
        ? parsed.verification.map((item) => ({
            cid: String(item.cid ?? '—'),
            passed: typeof item.passed === 'boolean' ? item.passed : undefined,
            detail: item.detail ? String(item.detail) : undefined
          }))
        : []
    }
  } catch {
    return null
  }
})

/** 产线 × 工作日 的工时占用汇总，直接从 assignments 现算，不依赖模型自述。 */
const load = computed(() => {
  if (!plan.value) {
    return []
  }
  const byLine = new Map<string, Map<number, number>>()
  for (const item of plan.value.assignments) {
    if (!byLine.has(item.line)) {
      byLine.set(item.line, new Map())
    }
    const days = byLine.get(item.line)!
    days.set(item.day, (days.get(item.day) ?? 0) + item.hours)
  }
  const days = [...new Set(plan.value.assignments.map((item) => item.day))].sort((a, b) => a - b)
  return [...byLine.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([line, map]) => ({
      line,
      days: days.map((day) => ({ day, hours: map.get(day) ?? 0 })),
      total: [...map.values()].reduce((sum, value) => sum + value, 0)
    }))
})

/** 不是 plan_v1 但确实是 JSON 时，至少缩进一下再看。 */
const otherJson = computed(() => {
  const raw = props.text?.trim()
  if (!raw || !/^[[{]/.test(raw)) {
    return null
  }
  try {
    return JSON.stringify(JSON.parse(unwrapJson(raw)), null, 2)
  } catch {
    return null
  }
})

/** 模型在 JSON 之前写的推理说明（换模型后常见），单独收起来。 */
const preamble = computed(() => {
  const raw = props.text?.trim()
  if (!raw || !plan.value) {
    return ''
  }
  const fence = raw.match(/```(?:json)?\s*[\s\S]*?```/i)
  const before = fence ? raw.slice(0, fence.index) : raw.slice(0, raw.indexOf('{'))
  return before.trim()
})

const allPassed = computed(
  () => plan.value?.verification.length ? plan.value.verification.every((item) => item.passed !== false) : null
)

const totalHours = computed(
  () => plan.value?.assignments.reduce((sum, item) => sum + item.hours, 0) ?? 0
)
</script>

<template>
  <div class="px-4 py-4">
    <template v-if="plan && !showRaw">
      <details
        v-if="preamble"
        class="mb-3 rounded-lg border border-white/8 bg-[#3a3a3c] px-3 py-2"
      >
        <summary class="cursor-pointer text-xs text-white/50">模型的推理说明</summary>
        <div class="mt-2">
          <MarkdownBlock :text="preamble" />
        </div>
      </details>

      <p class="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-white/50">
        <span>{{ plan.assignments.length }} 条排产</span>
        <span class="text-white/25">·</span>
        <span>合计 {{ totalHours }} 小时</span>
        <template v-if="plan.verification.length">
          <span class="text-white/25">·</span>
          <span
            class="rounded-md border px-1.5 py-0.5 font-mono text-[11px]"
            :class="allPassed
              ? 'border-emerald-400/25 bg-emerald-400/10 text-emerald-300/90'
              : 'border-amber-400/25 bg-amber-400/10 text-amber-300/90'"
          >
            自检 {{ plan.verification.filter((i) => i.passed).length }}/{{ plan.verification.length }}
          </span>
        </template>
      </p>

      <table class="w-full border-collapse text-xs">
        <thead>
          <tr class="text-white/40">
            <th class="border-b border-white/8 py-1.5 pr-3 text-left font-normal">订单</th>
            <th class="border-b border-white/8 py-1.5 pr-3 text-left font-normal">产线</th>
            <th class="border-b border-white/8 py-1.5 pr-3 text-left font-normal">工作日</th>
            <th class="border-b border-white/8 py-1.5 pr-3 text-right font-normal">工时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in plan.assignments" :key="item.order" class="text-white/80">
            <td class="border-b border-white/5 py-1.5 pr-3 font-mono">{{ item.order }}</td>
            <td class="border-b border-white/5 py-1.5 pr-3 font-mono">{{ item.line }}</td>
            <td class="border-b border-white/5 py-1.5 pr-3">第 {{ item.day }} 天</td>
            <td class="border-b border-white/5 py-1.5 pr-3 text-right font-mono">{{ item.hours }} h</td>
          </tr>
        </tbody>
      </table>

      <div v-if="load.length" class="mt-5">
        <p class="mb-2 text-xs text-white/40">按产线汇总的每日占用（由排产结果现算）</p>
        <table class="w-full border-collapse text-xs">
          <thead>
            <tr class="text-white/40">
              <th class="border-b border-white/8 py-1.5 pr-3 text-left font-normal">产线</th>
              <th
                v-for="day in load[0].days"
                :key="day.day"
                class="border-b border-white/8 py-1.5 pr-3 text-right font-normal"
              >
                第 {{ day.day }} 天
              </th>
              <th class="border-b border-white/8 py-1.5 pr-3 text-right font-normal">合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in load" :key="row.line" class="text-white/80">
              <td class="border-b border-white/5 py-1.5 pr-3 font-mono">{{ row.line }}</td>
              <td
                v-for="day in row.days"
                :key="day.day"
                class="border-b border-white/5 py-1.5 pr-3 text-right font-mono"
                :class="day.hours === 0 ? 'text-white/25' : ''"
              >
                {{ day.hours }}
              </td>
              <td class="border-b border-white/5 py-1.5 pr-3 text-right font-mono text-white/50">{{ row.total }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="plan.verification.length" class="mt-5">
        <p class="mb-2 text-xs text-white/40">模型自述的校验项（未经服务端核实）</p>
        <ul class="space-y-2">
          <li
            v-for="item in plan.verification"
            :key="item.cid"
            class="rounded-lg border border-white/8 bg-[#3a3a3c] px-3 py-2"
          >
            <div class="flex items-center gap-2">
              <span
                class="rounded-md border px-1.5 py-0.5 font-mono text-[11px]"
                :class="item.passed === false
                  ? 'border-rose-400/25 bg-rose-400/10 text-rose-300/90'
                  : item.passed
                    ? 'border-emerald-400/25 bg-emerald-400/10 text-emerald-300/90'
                    : 'border-white/12 text-white/50'"
              >
                {{ item.passed === false ? '未通过' : item.passed ? '通过' : '未知' }}
              </span>
              <span class="font-mono text-[11px] text-white/50">{{ item.cid }}</span>
            </div>
            <p v-if="item.detail" class="mt-1.5 text-xs leading-relaxed text-white/70">{{ item.detail }}</p>
          </li>
        </ul>
      </div>
    </template>

    <pre
      v-else-if="otherJson"
      class="max-h-[52vh] overflow-y-auto whitespace-pre-wrap break-words font-mono text-xs leading-relaxed text-white/80"
    >{{ otherJson }}</pre>
    <MarkdownBlock v-else-if="text?.trim()" :text="text" />
    <p v-else class="text-xs text-white/40">（无输出）</p>

    <button
      v-if="plan"
      type="button"
      class="mt-4 rounded-md border border-white/12 px-2.5 py-1 text-[11px] text-white/50 transition-colors duration-200 ease-out hover:bg-white/5"
      @click="showRaw = !showRaw"
    >
      {{ showRaw ? '看结构化视图' : '看原始 JSON' }}
    </button>
  </div>
</template>

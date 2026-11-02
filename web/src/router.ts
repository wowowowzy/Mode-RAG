import { createRouter, createWebHistory } from 'vue-router'
import DomainsView from './views/DomainsView.vue'
import TasksView from './views/TasksView.vue'
import RunView from './views/RunView.vue'
import RecordsView from './views/RecordsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'domains', component: DomainsView },
    { path: '/d/:domain', name: 'tasks', component: TasksView, props: true },
    { path: '/runs/:id', name: 'run', component: RunView, props: true },
    { path: '/records', name: 'records', component: RecordsView }
  ]
})

export default router

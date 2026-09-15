import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/report', name: 'Report', component: () => import('../views/Report.vue') },
  { path: '/query', name: 'Query', component: () => import('../views/Query.vue') },
  { path: '/history', name: 'History', component: () => import('../views/History.vue') },
  { path: '/report/:id', name: 'ReportDetail', component: () => import('../views/ReportDetail.vue') }
]

export default createRouter({ history: createWebHistory(), routes })

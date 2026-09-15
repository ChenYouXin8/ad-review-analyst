<template>
  <div>
    <h1 class="page-title">智能查询</h1>
    <div class="card">
      <p style="color:#888;margin-bottom:12px">用大白话问投放数据，如"今天哪个计划跑废了？"</p>
      <el-input v-model="question" placeholder="输入你的问题..." @keyup.enter="ask" style="margin-bottom:12px">
        <template #append><el-button type="primary" :loading="loading" @click="ask">提问</el-button></template>
      </el-input>
      <div>
        <el-tag v-for="q in quickQuestions" :key="q" style="margin:4px;cursor:pointer" @click="question = q">{{ q }}</el-tag>
      </div>
    </div>
    <div v-if="result" class="card">
      <h3 style="margin-bottom:12px">🤖 AI 回答</h3>
      <div style="background:#f0f9ff;padding:16px;border-radius:8px;line-height:1.8">{{ result.answer }}</div>
      <el-divider />
      <h4 style="margin-bottom:12px">相关计划数据</h4>
      <el-table :data="result.relatedCampaigns" stripe size="small">
        <el-table-column prop="campaignName" label="计划" />
        <el-table-column prop="cost" label="消耗"><template #default="{ row }">¥{{ row.cost?.toFixed(0) }}</template></el-table-column>
        <el-table-column prop="payOrderRoi" label="ROI"><template #default="{ row }">{{ row.payOrderRoi?.toFixed(2) }}</template></el-table-column>
        <el-table-column prop="convertCnt" label="转化" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { queryApi } from '../utils/api'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
const question = ref('')
const result = ref(null)
const loading = ref(false)
const quickQuestions = ['今天整体ROI怎么样？','哪个计划消耗最多？','ROI最低的计划是哪个？','有哪些计划需要暂停？']
const ask = async () => {
  if (!question.value.trim()) { ElMessage.warning('请输入问题'); return }
  loading.value = true
  try {
    const res = await queryApi.ask(question.value, dayjs().format('YYYY-MM-DD'))
    result.value = res.data
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}
</script>

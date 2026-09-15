<template>
  <div>
    <h1 class="page-title">数据概览</h1>
    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="6"><div class="stat-card"><div class="label">总消耗</div><div class="value">¥{{ summary.totalCost?.toFixed(2) }}</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="label">整体 ROI</div><div class="value" :class="summary.overallRoi >= 1.5 ? 'positive' : 'negative'">{{ summary.overallRoi?.toFixed(2) }}</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="label">总转化</div><div class="value">{{ summary.totalConvert || 0 }}</div></div></el-col>
      <el-col :span="6"><div class="stat-card"><div class="label">异常计划</div><div class="value negative">{{ summary.abnormalCampaignCount || 0 }}</div></div></el-col>
    </el-row>
    <div class="card">
      <h3 style="margin-bottom: 16px">计划明细</h3>
      <el-table :data="campaigns" stripe>
        <el-table-column prop="campaignName" label="计划名称" min-width="180" />
        <el-table-column prop="cost" label="消耗(元)" width="100"><template #default="{ row }">{{ row.cost?.toFixed(0) }}</template></el-table-column>
        <el-table-column prop="showCnt" label="展示" width="100" />
        <el-table-column prop="clickCnt" label="点击" width="80" />
        <el-table-column prop="ctr" label="CTR" width="80"><template #default="{ row }">{{ row.ctr?.toFixed(2) }}%</template></el-table-column>
        <el-table-column prop="convertCnt" label="转化" width="80" />
        <el-table-column prop="convertCost" label="CPA(元)" width="90"><template #default="{ row }">{{ row.convertCost?.toFixed(0) }}</template></el-table-column>
        <el-table-column prop="payOrderAmount" label="GMV(元)" width="100"><template #default="{ row }">{{ row.payOrderAmount?.toFixed(0) }}</template></el-table-column>
        <el-table-column prop="payOrderRoi" label="ROI" width="80"><template #default="{ row }"><span :class="row.payOrderRoi >= 1.5 ? 'tag-low' : 'tag-high'">{{ row.payOrderRoi?.toFixed(2) }}</span></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { dataApi } from '../utils/api'
import dayjs from 'dayjs'
const summary = ref({})
const campaigns = ref([])
onMounted(async () => {
  try {
    const today = dayjs().format('YYYY-MM-DD')
    const [s, c] = await Promise.all([dataApi.getSummary(today), dataApi.getCampaigns(today, today)])
    summary.value = s.data
    campaigns.value = c.data
  } catch (e) { console.error(e) }
})
</script>

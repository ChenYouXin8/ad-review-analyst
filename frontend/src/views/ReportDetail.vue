<template>
  <div>
    <el-page-header @back="$router.back()" content="报告详情" style="margin-bottom:20px" />
    <div v-loading="loading">
      <el-empty v-if="!loading && !report" description="报告不存在或已被删除" />
      <template v-else-if="report">
        <div class="card">
          <h2 style="margin-bottom:16px">{{ report.reportType === 'daily' ? '日报' : report.reportType === 'weekly' ? '周报' : '自定义报告' }}<span style="font-size:14px;color:#888;margin-left:12px">{{ report.startDate }} ~ {{ report.endDate }}</span></h2>
          <el-row :gutter="16" style="margin-bottom:20px">
            <el-col :span="6"><div class="stat-card"><div class="label">总消耗</div><div class="value">¥{{ report.summary?.totalCost?.toFixed(0) }}</div></div></el-col>
            <el-col :span="6"><div class="stat-card"><div class="label">ROI</div><div class="value" :class="(report.summary?.overallRoi ?? 0) >= 1.5 ? 'positive' : 'negative'">{{ report.summary?.overallRoi?.toFixed(2) }}</div></div></el-col>
            <el-col :span="6"><div class="stat-card"><div class="label">GMV</div><div class="value">¥{{ report.summary?.totalGmv?.toFixed(0) }}</div></div></el-col>
            <el-col :span="6"><div class="stat-card"><div class="label">异常计划</div><div class="value negative">{{ report.abnormalItems?.length || 0 }}</div></div></el-col>
          </el-row>
          <el-divider /><h3>📊 数据概览</h3><p style="line-height:1.8">{{ report.overview }}</p>
          <el-divider /><h3>⚠️ 异常诊断</h3>
          <el-table :data="report.abnormalItems" stripe>
            <el-table-column label="等级" width="80"><template #default="{ row }"><span :class="'tag-' + row.level">{{ row.level }}</span></template></el-table-column>
            <el-table-column prop="campaignName" label="计划" width="160" />
            <el-table-column prop="description" label="描述" />
            <el-table-column prop="suggestion" label="建议" />
          </el-table>
          <el-divider /><h3>💡 策略建议</h3>
          <ol style="padding-left:20px;line-height:2"><li v-for="(s,i) in report.suggestions" :key="i">{{ s }}</li></ol>
          <el-divider /><h3>💰 预算分配建议</h3>
          <el-table :data="report.budgetSuggestion?.allocations" stripe size="small">
            <el-table-column prop="campaignName" label="计划" width="180" />
            <el-table-column label="当前消耗(元)" width="120"><template #default="{ row }">¥{{ row.currentBudget?.toFixed(0) }}</template></el-table-column>
            <el-table-column label="建议预算(元)" width="120"><template #default="{ row }">¥{{ row.suggestedBudget?.toFixed(0) }}</template></el-table-column>
            <el-table-column label="调整比例" width="100"><template #default="{ row }"><span :class="(row.adjustRatio ?? 1) >= 1 ? 'tag-low' : 'tag-high'">{{ ((row.adjustRatio ?? 1) * 100).toFixed(0) }}%</span></template></el-table-column>
            <el-table-column prop="reason" label="原因" />
          </el-table>
          <el-divider /><h3>🏆 优质计划</h3>
          <el-table :data="report.topCampaigns" stripe size="small">
            <el-table-column prop="campaignName" label="计划" width="200" />
            <el-table-column prop="cost" label="消耗"><template #default="{ row }">¥{{ row.cost?.toFixed(0) }}</template></el-table-column>
            <el-table-column prop="payOrderRoi" label="ROI"><template #default="{ row }">{{ row.payOrderRoi?.toFixed(2) }}</template></el-table-column>
            <el-table-column prop="convertCnt" label="转化" />
            <el-table-column prop="payOrderAmount" label="GMV"><template #default="{ row }">¥{{ row.payOrderAmount?.toFixed(0) }}</template></el-table-column>
          </el-table>
          <el-divider /><h3>📉 需关注计划</h3>
          <el-table :data="report.decliningCampaigns" stripe size="small">
            <el-table-column prop="campaignName" label="计划" width="200" />
            <el-table-column prop="cost" label="消耗"><template #default="{ row }">¥{{ row.cost?.toFixed(0) }}</template></el-table-column>
            <el-table-column prop="payOrderRoi" label="ROI"><template #default="{ row }">{{ row.payOrderRoi?.toFixed(2) }}</template></el-table-column>
            <el-table-column prop="convertCost" label="CPA"><template #default="{ row }">¥{{ row.convertCost?.toFixed(0) }}</template></el-table-column>
          </el-table>
          <el-divider /><h3>📈 深度分析</h3>
          <div style="white-space:pre-wrap;line-height:1.8">{{ report.aiAnalysis }}</div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { reportApi } from '../utils/api'
const route = useRoute()
const report = ref(null)
const loading = ref(false)
onMounted(async () => {
  loading.value = true
  try { const res = await reportApi.getDetail(route.params.id); report.value = res.data } catch (e) { console.error(e) }
  finally { loading.value = false }
})
</script>

<template>
  <div>
    <h1 class="page-title">历史报告</h1>
    <div class="card">
      <el-table :data="reports" stripe>
        <el-table-column prop="reportType" label="类型" width="100">
          <template #default="{ row }"><el-tag :type="row.reportType === 'daily' ? 'primary' : row.reportType === 'weekly' ? 'success' : 'warning'">{{ row.reportType === 'daily' ? '日报' : row.reportType === 'weekly' ? '周报' : '自定义' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="统计周期" width="220"><template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template></el-table-column>
        <el-table-column label="消耗" width="120"><template #default="{ row }">¥{{ row.summary?.totalCost?.toFixed(0) }}</template></el-table-column>
        <el-table-column label="ROI" width="100"><template #default="{ row }"><span :class="row.summary?.overallRoi >= 1.5 ? 'tag-low' : 'tag-high'">{{ row.summary?.overallRoi?.toFixed(2) }}</span></template></el-table-column>
        <el-table-column label="异常数" width="100"><template #default="{ row }">{{ row.abnormalItems?.length || 0 }}</template></el-table-column>
        <el-table-column prop="generatedAt" label="生成时间" width="180" />
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button type="primary" link @click="$router.push('/report/' + row.reportId)">查看详情</el-button></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { reportApi } from '../utils/api'
const reports = ref([])
onMounted(async () => {
  try { const res = await reportApi.getList(1, 20); reports.value = res.data } catch (e) { console.error(e) }
})
</script>

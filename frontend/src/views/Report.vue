<template>
  <div>
    <h1 class="page-title">复盘报告</h1>
    <div class="card">
      <el-radio-group v-model="reportType" style="margin-right: 16px">
        <el-radio-button label="daily">日报</el-radio-button>
        <el-radio-button label="weekly">周报</el-radio-button>
        <el-radio-button label="custom">自定义</el-radio-button>
      </el-radio-group>
      <el-date-picker v-if="reportType === 'daily'" v-model="dailyDate" type="date" placeholder="选择日期" style="margin-right: 16px" />
      <el-date-picker v-if="reportType === 'weekly'" v-model="weeklyDate" type="date" placeholder="结束日期" style="margin-right: 16px" />
      <el-date-picker v-if="reportType === 'custom'" v-model="dateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="margin-right: 16px" />
      <el-button type="primary" :loading="loading" @click="generate">生成报告</el-button>
    </div>
    <div v-if="report" class="card">
      <h3>📊 数据概览</h3><p style="line-height:1.8">{{ report.overview }}</p>
      <el-divider />
      <h3>⚠️ 异常诊断 ({{ report.abnormalItems?.length || 0 }})</h3>
      <el-table :data="report.abnormalItems" stripe>
        <el-table-column label="等级" width="80"><template #default="{ row }"><span :class="'tag-' + row.level">{{ row.level }}</span></template></el-table-column>
        <el-table-column prop="campaignName" label="计划" width="160" />
        <el-table-column prop="description" label="异常描述" />
        <el-table-column prop="suggestion" label="建议" />
      </el-table>
      <el-divider />
      <h3>💡 AI 策略建议</h3>
      <ol style="padding-left:20px;line-height:2"><li v-for="(s,i) in report.suggestions" :key="i">{{ s }}</li></ol>
      <el-divider />
      <h3>📈 深度分析</h3>
      <div style="white-space:pre-wrap;line-height:1.8">{{ report.aiAnalysis }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { reportApi } from '../utils/api'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
const reportType = ref('daily')
const dailyDate = ref(dayjs().subtract(1,'day').toDate())
const weeklyDate = ref(new Date())
const dateRange = ref([])
const report = ref(null)
const loading = ref(false)
const generate = async () => {
  loading.value = true
  try {
    let res
    if (reportType.value === 'daily') res = await reportApi.generateDaily(dayjs(dailyDate.value).format('YYYY-MM-DD'))
    else if (reportType.value === 'weekly') res = await reportApi.generateWeekly(dayjs(weeklyDate.value).format('YYYY-MM-DD'))
    else {
      if (!dateRange.value?.length) { ElMessage.warning('请选择日期范围'); return }
      res = await reportApi.generateCustom(dayjs(dateRange.value[0]).format('YYYY-MM-DD'), dayjs(dateRange.value[1]).format('YYYY-MM-DD'))
    }
    report.value = res.data
    ElMessage.success('报告生成成功')
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}
</script>

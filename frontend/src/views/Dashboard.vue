<template>
  <div v-loading="loading">
    <h1 class="page-title">数据概览</h1>

    <el-empty v-if="!loading && campaigns.length === 0" description="暂无投放数据，请先配置千川 MCP 或生成报告" />

    <template v-else>
      <el-row :gutter="16" style="margin-bottom: 16px">
        <el-col :span="6"><div class="stat-card"><div class="label">总消耗</div><div class="value">¥{{ fmtMoney(summary.totalCost) }}</div></div></el-col>
        <el-col :span="6"><div class="stat-card"><div class="label">整体 ROI</div><div class="value" :class="(summary.overallRoi ?? 0) >= config.roiThreshold ? 'positive' : 'negative'">{{ fmtNum(summary.overallRoi) }}</div></div></el-col>
        <el-col :span="6"><div class="stat-card"><div class="label">总转化</div><div class="value">{{ summary.totalConvert || 0 }}</div></div></el-col>
        <el-col :span="6"><div class="stat-card"><div class="label">异常计划</div><div class="value negative">{{ summary.abnormalCampaignCount || 0 }}</div></div></el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <div class="card">
            <h3 style="margin-bottom: 16px">计划消耗 TOP10</h3>
            <div ref="costChartRef" style="height: 320px"></div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="card">
            <h3 style="margin-bottom: 16px">计划 ROI 对比</h3>
            <div ref="roiChartRef" style="height: 320px"></div>
          </div>
        </el-col>
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
          <el-table-column prop="payOrderRoi" label="ROI" width="80"><template #default="{ row }"><span :class="(row.payOrderRoi ?? 0) >= config.roiThreshold ? 'tag-low' : 'tag-high'">{{ row.payOrderRoi?.toFixed(2) }}</span></template></el-table-column>
        </el-table>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { dataApi, configApi } from '../utils/api'
import dayjs from 'dayjs'

echarts.use([BarChart, GridComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

const summary = ref({})
const campaigns = ref([])
const config = ref({ roiThreshold: 1.5, cpaThreshold: 100, ctrThreshold: 1.0 })
const loading = ref(false)
const costChartRef = ref(null)
const roiChartRef = ref(null)
let costChart = null
let roiChart = null

const fmtMoney = v => (v ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 0 })
const fmtNum = v => (v ?? 0).toFixed(2)

const renderCharts = () => {
  if (!costChartRef.value || !roiChartRef.value) return
  const top10 = [...campaigns.value].sort((a, b) => (b.cost ?? 0) - (a.cost ?? 0)).slice(0, 10)
  const names = top10.map(c => c.campaignName || '未命名计划')
  const costs = top10.map(c => Math.round(c.cost ?? 0))
  const rois = top10.map(c => +(c.payOrderRoi ?? 0).toFixed(2))

  if (!costChart) costChart = echarts.init(costChartRef.value)
  costChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 60, right: 20, top: 20, bottom: 60 },
    xAxis: { type: 'value', name: '元' },
    yAxis: { type: 'category', data: [...names].reverse(), axisLabel: { width: 110, overflow: 'truncate' } },
    series: [{ type: 'bar', data: [...costs].reverse(), itemStyle: { color: '#4fc3f7' }, barMaxWidth: 18 }]
  })

  if (!roiChart) roiChart = echarts.init(roiChartRef.value)
  roiChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 60, right: 30, top: 20, bottom: 60 },
    xAxis: { type: 'category', data: names, axisLabel: { rotate: 30, width: 100, overflow: 'truncate' } },
    yAxis: { type: 'value', name: 'ROI' },
    series: [
      {
        type: 'bar', data: rois, barMaxWidth: 28,
        itemStyle: { color: p => (p.value >= config.value.roiThreshold ? '#67c23a' : '#f56c6c') },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#e6a23c' },
          label: { formatter: `阈值 ${config.value.roiThreshold}`, position: 'insideEndTop' },
          data: [{ yAxis: config.value.roiThreshold }]
        }
      }
    ]
  })
}

const handleResize = () => { costChart?.resize(); roiChart?.resize() }

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  loading.value = true
  try {
    const [cfg, s, c] = await Promise.all([
      configApi.get().catch(() => ({ data: config.value })),
      dataApi.getSummary(dayjs().format('YYYY-MM-DD')),
      dataApi.getCampaigns(dayjs().format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD'))
    ])
    config.value = cfg.data || config.value
    summary.value = s.data || {}
    campaigns.value = c.data || []
    await nextTick()
    renderCharts()
  } catch (e) { console.error(e) }
  finally { loading.value = false }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  costChart?.dispose()
  roiChart?.dispose()
})
</script>

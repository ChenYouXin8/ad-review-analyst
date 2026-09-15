<template>
  <div>
    <h1 class="page-title">历史报告</h1>
    <div class="card" v-loading="loading">
      <el-empty v-if="!loading && reports.length === 0" description="暂无历史报告，请先在「复盘报告」页生成" />
      <template v-else>
        <el-table :data="reports" stripe>
          <el-table-column prop="reportType" label="类型" width="100">
            <template #default="{ row }"><el-tag :type="row.reportType === 'daily' ? 'primary' : row.reportType === 'weekly' ? 'success' : 'warning'">{{ row.reportType === 'daily' ? '日报' : row.reportType === 'weekly' ? '周报' : '自定义' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="统计周期" width="220"><template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template></el-table-column>
          <el-table-column label="消耗" width="120"><template #default="{ row }">¥{{ row.summary?.totalCost?.toFixed(0) }}</template></el-table-column>
          <el-table-column label="ROI" width="100"><template #default="{ row }"><span :class="(row.summary?.overallRoi ?? 0) >= 1.5 ? 'tag-low' : 'tag-high'">{{ row.summary?.overallRoi?.toFixed(2) }}</span></template></el-table-column>
          <el-table-column label="异常数" width="100"><template #default="{ row }">{{ row.abnormalItems?.length || 0 }}</template></el-table-column>
          <el-table-column prop="generatedAt" label="生成时间" width="180" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button type="primary" link @click="$router.push('/report/' + row.reportId)">查看详情</el-button>
              <el-button type="danger" link @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          style="margin-top: 16px; justify-content: flex-end"
          layout="total, prev, pager, next, sizes"
          :total="total"
          :page-size="size"
          :current-page="page"
          :page-sizes="[10, 20, 50]"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { reportApi } from '../utils/api'
import { ElMessage, ElMessageBox } from 'element-plus'

const reports = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const load = async () => {
  loading.value = true
  try {
    const res = await reportApi.getList(page.value, size.value)
    reports.value = res.data.items || []
    total.value = res.data.total || 0
  } catch (e) { ElMessage.error(e.message || '加载失败') }
  finally { loading.value = false }
}

const onPageChange = p => { page.value = p; load() }
const onSizeChange = s => { size.value = s; page.value = 1; load() }

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除 ${row.startDate} ~ ${row.endDate} 的报告吗？`, '删除确认', { type: 'warning' })
    await reportApi.delete(row.reportId)
    ElMessage.success('删除成功')
    load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || '删除失败') }
}

onMounted(load)
</script>

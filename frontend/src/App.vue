<template>
  <el-container class="layout">
    <el-aside width="220px" class="sidebar">
      <div class="logo"><h2>投流复盘</h2><p>AI Analyst</p></div>
      <el-menu :default-active="$route.path" router background-color="#1a1a2e" text-color="#a0a0b0" active-text-color="#4fc3f7">
        <el-menu-item index="/"><span>📊 数据概览</span></el-menu-item>
        <el-menu-item index="/report"><span>📋 复盘报告</span></el-menu-item>
        <el-menu-item index="/query"><span>💬 智能查询</span></el-menu-item>
        <el-menu-item index="/history"><span>📁 历史报告</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-main class="main-content"><router-view /></el-main>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.sidebar { background: #1a1a2e; color: #fff; }
.logo { padding: 24px 20px; border-bottom: 1px solid #2a2a4a; }
.logo h2 { margin: 0; font-size: 20px; color: #4fc3f7; }
.logo p { margin: 4px 0 0; font-size: 12px; color: #666; }
.main-content { background: #f5f5f5; padding: 20px; }
</style>

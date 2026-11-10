<template>
  <div class="nf">
    <el-result icon="warning" title="404" sub-title="页面不存在">
      <template #extra>
        <el-button type="primary" @click="$router.push('/board')">回到首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.nf {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>

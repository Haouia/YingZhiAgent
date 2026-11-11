<script setup>
defineProps({ result: Object, active: Boolean, busy: Boolean })
defineEmits(['select'])
function displayTime(value) {
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<template>
  <div v-if="result?.publicLookup" class="public-results">
    <template v-if="result.publicSearch">
      <p>{{ result.publicSearch.city }} · {{ result.publicSearch.source }} · {{ displayTime(result.publicSearch.queriedAt) }}</p>
      <ol>
        <li v-for="(place, index) in result.publicSearch.places" :key="place.id">
          <div><strong>{{ index + 1 }}. {{ place.name }}</strong><p>{{ place.address || '暂无地址' }}</p></div>
          <el-button :disabled="busy || !active" @click="$emit('select', index + 1)">查看详情</el-button>
        </li>
      </ol>
      <p v-if="!active">这组结果已有更新，请使用最近一次搜索结果。</p>
    </template>
    <el-card v-else-if="result.publicDetail" shadow="never">
      <h3>{{ result.publicDetail.name }}</h3>
      <p>{{ result.publicDetail.city }} · {{ result.publicDetail.address || '暂无地址' }}</p>
      <p v-if="result.publicDetail.category">{{ result.publicDetail.category }}</p>
      <p v-if="result.publicDetail.location">坐标 {{ result.publicDetail.location }}</p>
      <p>{{ result.publicDetail.source }} · {{ displayTime(result.publicDetail.queriedAt) }}</p>
    </el-card>
    <p v-else-if="!result.publicAnswer">历史地点卡片请重新搜索查看。</p>
  </div>
</template>

<style scoped>
.public-results { margin: 12px 0; }
ol { padding: 0; list-style: none; }
li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); }
p { margin: 6px 0; }
</style>

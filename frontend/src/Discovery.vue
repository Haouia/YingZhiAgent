<script setup>
import { computed, ref } from 'vue'
import { api } from './api'

const city = ref('')
const keyword = ref('露营地')
const results = ref(null)
const detail = ref(null)
const question = ref('')
const followUp = ref(null)
const busy = ref(false)
const error = ref('')
const target = computed(() => results.value?.places.find(place => place.id === followUp.value?.placeId))

async function run(task) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await task()
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}

function search() {
  if (!city.value.trim() || !keyword.value.trim()) return
  return run(async () => {
    results.value = null
    detail.value = null
    followUp.value = null
    question.value = ''
    const query = new URLSearchParams({ city: city.value.trim(), keyword: keyword.value.trim() })
    results.value = await api('/discovery/campsites?' + query)
  })
}

function show(place) {
  return run(async () => {
    detail.value = null
    followUp.value = null
    const query = new URLSearchParams({ searchId: results.value.searchId })
    detail.value = await api('/discovery/campsites/' + encodeURIComponent(place.id) + '?' + query)
  })
}

function ask() {
  if (!question.value.trim()) return
  return run(async () => {
    followUp.value = null
    followUp.value = await api('/discovery/follow-up', {
      method: 'POST',
      body: { searchId: results.value.searchId, placeId: detail.value?.id, question: question.value.trim() },
    })
  })
}

function displayTime(value) {
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="panel">
    <div class="panel-heading">
      <div>
        <h2>找真实营地</h2>
        <p>按城市查高德地图地点。选择一条结果后可看详情，也可以按序号追问营地规则。</p>
      </div>
    </div>
    <form class="lookup-form" @submit.prevent="search">
      <div>
        <label for="lookup-city">城市</label>
        <el-input id="lookup-city" v-model="city" placeholder="例如杭州" maxlength="40" :disabled="busy" />
      </div>
      <div>
        <label for="lookup-keyword">营地名称或关键词</label>
        <el-input id="lookup-keyword" v-model="keyword" placeholder="露营地、房车营地或名称" maxlength="120" :disabled="busy" />
      </div>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!city.trim() || !keyword.trim()">查找营地</el-button>
    </form>
    <p v-if="error" class="lookup-error" role="alert">{{ error }}</p>
    <div v-if="results" aria-live="polite">
      <p>{{ results.city }} · {{ results.source }} · {{ displayTime(results.queriedAt) }}</p>
      <p v-if="!results.places.length">本次在该城市未查到符合条件的营地候选。</p>
      <ol v-else class="lookup-results">
        <li v-for="(place, index) in results.places" :key="place.id">
          <div>
            <strong>{{ index + 1 }}. {{ place.name }}</strong>
            <p>{{ place.address || '暂无地址' }}</p>
          </div>
          <el-button :disabled="busy" @click="show(place)">查看详情</el-button>
        </li>
      </ol>
    </div>
    <el-card v-if="detail" shadow="never" class="lookup-detail">
      <h3>{{ detail.name }}</h3>
      <p>{{ detail.city }} · {{ detail.address || '暂无地址' }}</p>
      <p v-if="detail.category">地图类别 {{ detail.category }}</p>
      <p v-if="detail.location">坐标 {{ detail.location }}</p>
      <p>{{ detail.source }} · {{ displayTime(detail.queriedAt) }}</p>
    </el-card>
    <form v-if="results?.places.length" class="lookup-form follow-up" @submit.prevent="ask">
      <div>
        <label for="lookup-question">追问营地规则</label>
        <el-input id="lookup-question" v-model="question" placeholder="例如第二个能带宠物吗" maxlength="2000" :disabled="busy" />
      </div>
      <el-button native-type="submit" :disabled="busy || !question.trim()">追问</el-button>
    </form>
    <div v-if="followUp" role="status" class="lookup-detail">
      <strong>{{ target?.name }}</strong>
      <p>{{ followUp.answer }}</p>
    </div>
  </section>
</template>

<style scoped>
.lookup-form { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }
.lookup-form > div { flex: 1 1 180px; }
.lookup-form label { display: block; margin-bottom: 6px; }
.lookup-results { padding: 0; list-style: none; }
.lookup-results li { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); }
.lookup-results p, .lookup-detail p { margin: 6px 0; }
.lookup-detail, .follow-up { margin-top: 14px; }
.lookup-error { color: var(--el-color-danger); }
</style>

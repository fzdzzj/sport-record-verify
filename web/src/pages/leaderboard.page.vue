<template>
  <div class="max-w-4xl mx-auto">
    <a-card title="榜单 - 总榜 / 好友榜" class="mb-4">
      <p class="text-gray-600 mb-4">GET /leaderboard/api/leaderboard?type=overall|friend&amp;size=... 。type=friend 时不传 userId（网关注入）；空榜正常展示。rank 从 1 开始，distance 累计 pass 里程。</p>

      <div class="mb-4">
        <a-radio-group v-model:value="lbType" @change="loadLb">
          <a-radio-button value="overall">总榜 (overall)</a-radio-button>
          <a-radio-button value="friend">好友榜 (friend)</a-radio-button>
        </a-radio-group>
        <a-input-number v-model:value="lbSize" :min="1" :max="100" style="width:80px; margin-left:8px" />
        <a-button @click="loadLb" :loading="loading" class="ml-2">查询</a-button>
      </div>

      <a-table :data-source="lbData" :columns="lbColumns" :loading="loading" row-key="rank" size="small">
        <template #bodyCell="{ column, record }">
          <span v-if="column.key === 'distance'">{{ record.distance }}</span>
          <span v-else>{{ record[column.dataIndex] }}</span>
        </template>
      </a-table>

      <div v-if="lbError" class="mt-2">
        <a-alert type="error" :message="lbError" />
      </div>
      <div class="mt-2 text-xs text-gray-500">好友榜：当前用户无好友或好友无上榜数据时返回空数组。总榜无需登录（但演示用登录会话）。</div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getLeaderboard, type LeaderboardDTO } from '@/api/client'

const lbType = ref<'overall' | 'friend'>('overall')
const lbSize = ref(10)
const lbData = ref<LeaderboardDTO[]>([])
const loading = ref(false)
const lbError = ref('')

const lbColumns = [
  { title: 'rank', dataIndex: 'rank', key: 'rank' },
  { title: 'userId', dataIndex: 'userId', key: 'userId' },
  { title: 'nickname', dataIndex: 'nickname', key: 'nickname' },
  { title: 'distance (km)', dataIndex: 'distance', key: 'distance' },
]

async function loadLb() {
  loading.value = true
  lbError.value = ''
  try {
    const res = await getLeaderboard(lbType.value, lbSize.value)
    lbData.value = res.data || []
  } catch (e: any) {
    lbError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || '查询失败')
    lbData.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadLb()
})
</script>

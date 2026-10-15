<template>
  <div class="max-w-md mx-auto mt-10">
    <!-- 个人资料卡片（TASK-245 B25）：查看昵称 + 原地修改保存 -->
    <a-card title="个人资料" style="width: 100%;" class="mb-6">
      <a-alert v-if="profileError" :message="profileError" type="error" show-icon class="mb-4" />
      <a-alert v-if="profileSaved" :message="profileSaved" type="success" show-icon class="mb-4" />
      <div v-if="profileLoading" class="text-gray-400 py-4 text-center">加载中…</div>
      <div v-else-if="profile">
        <div class="mb-1"><span class="text-gray-500">用户ID：</span><b>{{ profile.id }}</b></div>
        <a-form layout="vertical" @finish="onProfileFinish">
          <a-form-item label="昵称" name="nickname" :rules="profileNicknameRules">
            <a-input v-model:value="profileNickname" placeholder="请输入昵称（≤32 字符）" :maxlength="32" />
          </a-form-item>
          <a-button type="primary" html-type="submit" :loading="profileSaving" block>保存昵称</a-button>
        </a-form>
      </div>
    </a-card>

    <a-card title="修改密码" style="width: 100%;">
      <a-form :model="formState" :rules="rules" @finish="onFinish" layout="vertical">
        <a-form-item label="旧密码" name="oldPassword">
          <a-input-password v-model:value="formState.oldPassword" placeholder="当前登录密码" />
        </a-form-item>
        <a-form-item label="新密码" name="newPassword">
          <a-input-password v-model:value="formState.newPassword" placeholder="6-64 位，不得与旧密码相同" />
        </a-form-item>
        <a-form-item label="确认新密码" name="confirmPassword">
          <a-input-password v-model:value="formState.confirmPassword" placeholder="再次输入新密码" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="loading" block>确认修改</a-button>
        </a-form-item>
      </a-form>
      <div class="text-center mt-4">
        <a @click="goBack">返回</a>
      </div>
      <a-alert v-if="error" :message="error" type="error" show-icon class="mt-4" />
      <a-alert v-if="success" :message="success" type="success" show-icon class="mt-4" />
    </a-card>

    <!-- 通知偏好设置卡片（TASK-210）：按类型开关，动态渲染自 GET 响应，不硬编码类型列表 -->
    <a-card title="通知偏好" class="mt-6" style="width: 100%;">
      <p class="text-gray-600 mb-2">按类型开关通知接收（缺行默认开启）。关闭后该类型新通知不落库、不推送、不计未读；重新开启仅对未来通知生效。</p>
      <a-alert v-if="prefLoadError" :message="prefLoadError" type="warning" show-icon class="mb-4" />
      <div v-if="prefRows.length === 0" class="text-gray-400">
        {{ prefLoadError ? '偏好加载失败，暂无可设置项。' : '暂无可用通知偏好项。' }}
      </div>
      <div v-else class="space-y-4 max-w-md">
        <div
          v-for="item in prefRows"
          :key="item.type"
          class="flex items-center justify-between py-2 border-b"
        >
          <span>{{ prefLabel(item.type) }}</span>
          <a-switch
            :checked="item.enabled"
            :loading="item.toggling"
            @change="onPrefToggle(item)"
          />
        </div>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from "vue"
import { message } from "ant-design-vue"
import { useRouter } from "vue-router/auto"
import {
  changePassword,
  getNotificationPreferences,
  updateNotificationPreferences,
  getUserProfile,
  updateUserProfile,
  type NotificationPreferenceView,
  type UserProfileDTO,
} from "@/api/client"
import { clearTokens } from "@/utils/token"

const router = useRouter()

// ===== 个人资料（TASK-245 B25） =====
const profile = ref<UserProfileDTO | null>(null)
const profileLoading = ref(false)
const profileSaving = ref(false)
const profileError = ref("")
const profileSaved = ref("")
const profileNickname = ref("")

const profileNicknameRules = [
  { required: true, message: "昵称不能为空" },
  { max: 32, message: "昵称长度不能超过 32 字符" },
]

async function loadProfile() {
  profileLoading.value = true
  profileError.value = ""
  try {
    const res = await getUserProfile()
    profile.value = res.data
    profileNickname.value = res.data?.nickname || ""
  } catch (e: any) {
    profileError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || "加载个人资料失败")
  } finally {
    profileLoading.value = false
  }
}

async function onProfileFinish() {
  const nickname = profileNickname.value.trim()
  if (!nickname || profileSaving.value) return
  profileSaving.value = true
  profileError.value = ""
  profileSaved.value = ""
  try {
    const res = await updateUserProfile(nickname)
    profile.value = res.data
    profileNickname.value = res.data?.nickname || nickname
    profileSaved.value = "昵称已更新。"
    message.success("昵称已更新")
  } catch (e: any) {
    profileError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || "更新昵称失败")
  } finally {
    profileSaving.value = false
  }
}

const formState = reactive({
  oldPassword: "",
  newPassword: "",
  confirmPassword: "",
})

// 前端确认一致性校验：确认新密码须与新密码一致（服务端 @Valid 之外的第一道防线）
const rules = {
  oldPassword: [
    { required: true, message: "旧密码不能为空" },
    { min: 6, max: 64, message: "旧密码长度须在 6-64 位" },
  ],
  newPassword: [
    { required: true, message: "新密码不能为空" },
    { min: 6, max: 64, message: "新密码长度须在 6-64 位" },
  ],
  confirmPassword: [
    { required: true, message: "请再次输入新密码" },
    {
      validator: (_: any, value: string) => {
        if (value !== formState.newPassword) {
          return Promise.reject(new Error("两次输入的新密码不一致"))
        }
        return Promise.resolve()
      },
    },
  ],
}

const loading = ref(false)
const error = ref("")
const success = ref("")

async function onFinish() {
  loading.value = true
  error.value = ""
  success.value = ""
  try {
    await changePassword({
      oldPassword: formState.oldPassword,
      newPassword: formState.newPassword,
    })
    success.value = "修改成功！当前会话 refresh 已全量吊销，请重新登录。"
    // 改密即全量吊销 refresh：本地旧 token 已不可用，清除后引导重新登录
    clearTokens()
    setTimeout(() => {
      router.push("/login")
    }, 1500)
  } catch (e: any) {
    if (e.code === 1001) {
      error.value = "旧密码错误或账号不可用"
    } else if (e.status === 400) {
      error.value = "参数不合法（新密码不得与旧密码相同）"
    } else {
      error.value = e.message || "修改失败"
    }
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push("/")
}

// ===== 通知偏好（TASK-210）=====
// 渲染来源为 GET /user/api/notifications/preferences 响应，类型列表不硬编码（后端为准）。
// 类型 → 人性化文案映射（仅作显示，不决定渲染哪些行）。
const TYPE_LABELS: Record<string, string> = {
  RECORD_VERIFIED: "记录通过",
  RECORD_REJECTED: "记录驳回",
  FRIEND_ACCEPTED: "好友通过",
  RECORD_LIKED: "收到点赞",
}

interface PrefRow extends NotificationPreferenceView {
  toggling: boolean
}

const prefRows = ref<PrefRow[]>([])
const prefLoadError = ref("")

function prefLabel(type: string): string {
  return TYPE_LABELS[type] || type
}

async function loadPreferences() {
  prefLoadError.value = ""
  try {
    const res = await getNotificationPreferences()
    prefRows.value = (res.data || []).map((item) => ({ ...item, toggling: false }))
  } catch (e: any) {
    // 加载失败非致命：保留空区块并给出提示，不阻断改密功能
    prefLoadError.value = e.code ? `[${e.code}] ${e.message}` : (e.message || "加载通知偏好失败")
    prefRows.value = []
  }
}

async function onPrefToggle(row: PrefRow) {
  if (row.toggling) return
  row.toggling = true
  try {
    const res = await updateNotificationPreferences([{ type: row.type, enabled: row.enabled }])
    // 以 PUT 响应回填本地态：仅覆盖已开启的，其余行保持原值
    const updated = res.data || []
    for (const item of updated) {
      const target = prefRows.value.find((r) => r.type === item.type)
      if (target) target.enabled = item.enabled
    }
    message.success(`「${prefLabel(row.type)}」通知偏好已更新`)
  } catch (e: any) {
    // 回滚本次切换并提示（服务端拒绝对该类型时保持原值）
    row.enabled = !row.enabled
    message.error(e.code ? `[${e.code}] ${e.message}` : (e.message || "更新通知偏好失败"))
  } finally {
    row.toggling = false
  }
}

onMounted(() => {
  loadProfile()
  loadPreferences()
})
</script>

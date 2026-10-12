<template>
  <div class="max-w-md mx-auto mt-10">
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
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue"
import { useRouter } from "vue-router/auto"
import { changePassword } from "@/api/client"
import { clearTokens } from "@/utils/token"

const router = useRouter()

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
</script>

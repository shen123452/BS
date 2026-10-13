<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FirstAidKit, ArrowRight } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { auth, homes } from '../lib/auth.js'
import { post } from '../lib/http.js'
import { safeReturnTo } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
const route = useRoute(), router = useRouter(), register = computed(() => route.path === '/register')
const form = reactive({ username: '', password: '', displayName: '', confirmPassword: '' })
const busy = ref(false), error = ref(null)
async function submit() {
  if (busy.value) return
  error.value = null
  if (register.value && (!/^[A-Za-z0-9_.-]{3,64}$/.test(form.username) || form.password.length < 8 || new TextEncoder().encode(form.password).length > 72 || form.password !== form.confirmPassword)) {
    error.value = new Error('账号须为 3–64 位英文、数字或 ._-；密码须至少 8 位且两次输入一致，最多 72 字节。'); return
  }
  busy.value = true
  try {
    if (register.value) {
      await post('/auth/register', { username: form.username, password: form.password, displayName: form.displayName })
      ElMessage.success('注册成功，请登录并完善患者档案'); form.password = ''; form.confirmPassword = ''; await router.replace('/login')
    } else {
      const user = await auth.login({ username: form.username, password: form.password })
      form.password = ''; await router.replace(safeReturnTo(route.query.returnTo, user.role) || homes[user.role])
    }
  } catch (e) { error.value = e } finally { busy.value = false }
}
</script>
<template>
  <main class="auth-page">
    <div class="auth-intro"><div class="brand"><el-icon><FirstAidKit /></el-icon><span>医院智能导诊系统</span></div><h1 tabindex="-1">从预约到就诊，<br>安排更清楚。</h1><p>查看可预约场次，管理就诊安排，<br>随时查阅已提交的门诊病历。</p><div class="auth-path"><span>完善档案</span><el-icon><ArrowRight /></el-icon><span>预约挂号</span><el-icon><ArrowRight /></el-icon><span>门诊就诊</span></div></div>
    <section class="auth-form panel" :aria-label="register ? '患者注册' : '账号登录'">
      <h2>{{ register ? '创建患者账号' : '欢迎登录' }}</h2><p class="muted">{{ register ? '注册后可完善档案并预约。医生账号由管理员创建。' : '使用你的账号，进入对应的工作空间。' }}</p>
      <el-alert v-if="route.query.expired" title="登录状态已失效，请重新登录。" type="warning" :closable="false" show-icon />
      <el-alert v-if="route.query.unavailable" title="服务暂时不可用，请确认后端启动后重试。" type="error" :closable="false" show-icon />
      <ErrorPanel :error="error" />
      <form @submit.prevent="submit">
        <label for="username">账号</label><el-input id="username" v-model="form.username" autocomplete="username" maxlength="64" placeholder="请输入账号" required />
        <template v-if="register"><label for="displayName">姓名</label><el-input id="displayName" v-model="form.displayName" autocomplete="name" maxlength="64" placeholder="请输入姓名" required /></template>
        <label for="password">密码</label><el-input id="password" v-model="form.password" type="password" show-password :autocomplete="register ? 'new-password' : 'current-password'" maxlength="72" :placeholder="register ? '至少 8 位字符' : '请输入密码'" required />
        <template v-if="register"><label for="confirmPassword">确认密码</label><el-input id="confirmPassword" v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" maxlength="72" required /></template>
        <el-button type="primary" native-type="submit" :loading="busy" class="auth-submit">{{ register ? '注册患者账号' : '登录' }}</el-button>
      </form>
      <p class="auth-switch">{{ register ? '已有账号？' : '首次使用？' }} <router-link :to="register ? '/login' : '/register'">{{ register ? '返回登录' : '注册患者账号' }}</router-link></p>
    </section>
  </main>
</template>

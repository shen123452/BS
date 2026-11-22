<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { authApi } from '../api'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({ username: '', password: '', captchaCode: '' })
const captcha = reactive({ captchaId: '', image: '' })
const loading = ref(false)
const errorMsg = ref('')

async function refreshCaptcha() {
  try {
    const data = await authApi.captcha()
    captcha.captchaId = data.captchaId
    captcha.image = data.image
  } catch (e) {
    errorMsg.value = '验证码加载失败,请检查后端服务'
  }
}

async function handleLogin() {
  if (loading.value) return
  if (!form.username || !form.password || !form.captchaCode) {
    errorMsg.value = '请填写完整登录信息'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const data = await authApi.login({
      username: form.username,
      password: form.password,
      captchaId: captcha.captchaId,
      captchaCode: form.captchaCode
    })
    auth.setLogin(data)
    router.replace('/')
  } catch (e) {
    errorMsg.value = e.message
    form.captchaCode = ''
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  auth.logout()
  refreshCaptcha()
})
</script>

<template>
  <div class="login-page">
    <div class="bg-decoration">
      <div class="glow-circle glow-1"></div>
      <div class="glow-circle glow-2"></div>
    </div>

    <div class="login-card">
      <div class="login-brand">
        <div class="brand-icon-wrapper">
          <svg class="login-symbol" viewBox="0 0 64 88" fill="none" aria-hidden="true">
            <path d="M32 2C22 15 1 36 1 56C1 74 14 87 32 87S63 74 63 56C63 36 42 15 32 2Z" fill="currentColor" />
            <path d="M11 56h9l7-14 8 23 7-13h11" stroke="white" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <div>
          <h1>临床管理工作台</h1>
          <p>血糖时序监测与智能问答系统 · 医生与管理员</p>
        </div>
      </div>

      <el-form label-position="top" class="login-form" @submit.prevent="handleLogin">
        <el-form-item label="登录账号">
          <el-input v-model="form.username" autocomplete="username" placeholder="请输入医生/管理员用户名" size="large" />
        </el-form-item>
        <el-form-item label="安全密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" size="large" />
        </el-form-item>
        <el-form-item label="安全验证码">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" maxlength="4" placeholder="4位字符 (不区分大小写)" size="large" />
            <button type="button" class="captcha-refresh" aria-label="刷新验证码" @click="refreshCaptcha">
              <img v-if="captcha.image" :src="captcha.image" alt="验证码" class="captcha-img" />
              <span v-else>获取中…</span>
            </button>
          </div>
        </el-form-item>
        <div v-if="errorMsg" class="login-error" role="alert">{{ errorMsg }}</div>
        <el-button type="primary" native-type="submit" :loading="loading" class="login-btn" size="large">登 录 工 作 台</el-button>
      </el-form>

      <div class="login-note">
        <p><strong>患者端入口</strong>：患者使用微信原生小程序扫码登录，不使用此管理端。</p>
        <p><strong>提示</strong>：系统输出作为临床辅助参考，不构成独立诊断与处方凭据。</p>
      </div>
    </div>
    <p class="login-footer">本科毕业设计 · 血糖时序监测与智能问答管理系统</p>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: #F8FAFC; padding: 24px 16px; position: relative; overflow: hidden;
}

.bg-decoration {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0; pointer-events: none; overflow: hidden; z-index: 1;
}
.glow-circle {
  position: absolute; border-radius: 50%; filter: blur(90px); opacity: 0.55;
}
.glow-1 {
  width: 450px; height: 450px; background: rgba(37, 99, 235, 0.12); top: -80px; left: 10%;
}
.glow-2 {
  width: 400px; height: 400px; background: rgba(6, 182, 212, 0.1); bottom: -60px; right: 15%;
}

.login-card {
  width: 440px; max-width: 100%; background: #FFFFFF;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg); padding: 36px 36px 30px;
  box-shadow: 0 12px 36px -4px rgba(15, 23, 42, 0.08), 0 4px 12px -2px rgba(15, 23, 42, 0.04);
  position: relative; z-index: 2;
}

.login-brand { display: flex; align-items: center; gap: 16px; margin-bottom: 28px; }
.brand-icon-wrapper {
  width: 52px; height: 52px; border-radius: 14px; background: #EFF6FF;
  display: flex; align-items: center; justify-content: center; flex: 0 0 auto;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.15);
}
.login-symbol { width: 28px; height: 38px; color: var(--color-primary); }
.login-brand h1 { margin: 0; font-size: 19px; line-height: 1.3; font-weight: 700; color: var(--color-ink); letter-spacing: -0.01em; }
.login-brand p { margin: 4px 0 0; font-size: 12px; color: var(--color-ink-muted); }

.login-form .el-form-item { margin-bottom: 20px; }
.login-btn {
  width: 100%; margin-top: 8px; font-weight: 600; font-size: 15px; letter-spacing: 0.05em;
  height: 44px; border-radius: var(--radius-sm);
}

.captcha-row { display: flex; gap: 10px; width: 100%; }
.captcha-row .el-input { flex: 1; min-width: 0; }
.captcha-refresh {
  padding: 0; min-width: 110px; height: 40px; background: var(--color-surface-subtle);
  color: var(--color-primary); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 12px;
  transition: border-color 0.2s ease;
}
.captcha-refresh:hover { border-color: var(--color-primary); }
.captcha-img { height: 38px; max-width: 116px; display: block; border-radius: 6px; }

.login-error {
  color: var(--color-danger); font-size: 13px; margin-bottom: 14px;
  padding: 8px 12px; background: var(--color-danger-subtle); border-radius: var(--radius-sm);
  border: 1px solid #FECACA;
}

.login-note { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--color-border); }
.login-note p { margin: 0; color: var(--color-ink-muted); font-size: 12px; line-height: 1.7; }
.login-note p + p { margin-top: 4px; }
.login-footer { margin: 24px 0 0; color: var(--color-ink-faint); font-size: 12px; position: relative; z-index: 2; }

@media (max-width: 480px) {
  .login-card { padding: 26px 20px 22px; }
}
</style>

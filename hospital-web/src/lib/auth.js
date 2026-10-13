import { reactive } from 'vue'
import { get, post } from './http.js'
export const roleNames = { ADMIN: '管理员', DOCTOR: '医生', PATIENT: '患者' }
export const homes = { ADMIN: '/admin/home', DOCTOR: '/doctor/workbench', PATIENT: '/patient/home' }
export const auth = reactive({
  user: null,
  async refresh() { this.user = await get('/auth/me'); return this.user },
  async login(form) { const data = await post('/auth/login', form); sessionStorage.setItem('hospital.token', data.token); this.user = data.user; return data.user },
  clear() { sessionStorage.removeItem('hospital.token'); this.user = null },
})

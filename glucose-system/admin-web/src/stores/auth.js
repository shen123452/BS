import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('accessToken') || '',
    userId: localStorage.getItem('userId') || '',
    username: localStorage.getItem('username') || '',
    role: localStorage.getItem('role') || ''
  }),
  getters: {
    isAdmin: s => s.role === 'ADMIN',
    isDoctor: s => s.role === 'DOCTOR'
  },
  actions: {
    setLogin(data) {
      this.token = data.token
      this.userId = data.userId
      this.username = data.username
      this.role = data.role
      localStorage.setItem('accessToken', data.token)
      localStorage.setItem('userId', data.userId)
      localStorage.setItem('username', data.username)
      localStorage.setItem('role', data.role)
    },
    logout() {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('userId')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      this.$reset()
    }
  }
})

export function expireSession() {
  sessionStorage.removeItem('hospital.token')
  window.dispatchEvent(new Event('hospital-session-expired'))
}

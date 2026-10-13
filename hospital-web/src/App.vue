<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from './lib/auth.js'
const router = useRouter()
function expire() { auth.clear(); router.replace({ path: '/login', query: { expired: '1' } }) }
onMounted(() => window.addEventListener('hospital-session-expired', expire))
onUnmounted(() => window.removeEventListener('hospital-session-expired', expire))
</script>
<template><router-view /></template>

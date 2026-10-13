import { ref } from 'vue'
export function useLoad(fetcher, initial = null) {
  const data = ref(initial), loading = ref(false), error = ref(null)
  let latest = 0
  async function load() {
    const seq = ++latest; loading.value = true; error.value = null
    try { const result = await fetcher(); if (seq === latest) data.value = result }
    catch (e) { if (seq === latest) { data.value = initial; error.value = e } }
    finally { if (seq === latest) loading.value = false }
  }
  return { data, loading, error, load }
}

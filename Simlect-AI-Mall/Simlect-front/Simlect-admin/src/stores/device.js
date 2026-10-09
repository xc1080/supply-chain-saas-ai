import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { detectAdminPlatform } from '@/utils/device'

export const useDeviceStore = defineStore('adminDevice', () => {
  const platform = ref(detectAdminPlatform())

  const isMobile = computed(() => platform.value === 'mobile')
  const isDesktop = computed(() => platform.value === 'desktop')

  const sync = () => {
    const next = detectAdminPlatform()
    if (next !== platform.value) platform.value = next
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.adminPlatform = platform.value
    }
  }

  let bound = false
  const bind = () => {
    if (bound || typeof window === 'undefined') return
    bound = true
    sync()
    window.addEventListener('resize', sync, { passive: true })
    window.addEventListener('orientationchange', sync)
  }

  return { platform, isMobile, isDesktop, sync, bind }
})

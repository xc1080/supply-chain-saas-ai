import { ref, watch } from 'vue'

const JELLY_HOLD_MS = 560


export function useTabBarJelly(activeIndex) {
  const barJelly = ref(false)
  const glassJelly = ref(false)
  let hideTimer = null
  let flushTimer = null

  const applyJelly = () => {
    barJelly.value = false
    glassJelly.value = false

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        barJelly.value = true
        glassJelly.value = true
      })
    })

    if (hideTimer) clearTimeout(hideTimer)
    hideTimer = setTimeout(() => {
      barJelly.value = false
      glassJelly.value = false
    }, JELLY_HOLD_MS)
  }

  const triggerJelly = () => {
    if (flushTimer) clearTimeout(flushTimer)
    flushTimer = setTimeout(() => {
      flushTimer = null
      applyJelly()
    }, 0)
  }

  const onTabPress = () => {
    triggerJelly()
  }

  watch(activeIndex, (val, oldVal) => {
    if (val < 0 || val === oldVal || oldVal === undefined) return
    triggerJelly()
  })

  return { barJelly, glassJelly, onTabPress }
}

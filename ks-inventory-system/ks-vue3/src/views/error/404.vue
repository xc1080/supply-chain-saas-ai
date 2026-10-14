<template>
  <main class="page-error" aria-labelledby="page-error-title">
    <img class="page-error__illustration" src="@/assets/404_images/404.png" alt="" width="600" height="300" />
    <div class="page-error__content">
      <p class="page-error__code">404</p>
      <h1 id="page-error-title">页面不存在</h1>
      <p class="page-error__message">请检查访问地址，或回到工作台继续处理业务。</p>
      <router-link to="/index" class="page-error__home">回到工作台</router-link>
    </div>
  </main>
</template>

<style lang="scss" scoped>
.page-error { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); align-items: center; gap: 36px; width: min(100%, 960px); min-height: 75dvh; margin: 0 auto; padding: 48px 24px; color: var(--sc-text); }
.page-error__illustration { display: block; width: 100%; height: auto; }
.page-error__content { min-width: 0; }
.page-error__code { margin: 0 0 8px; color: var(--sc-muted); font-size: 14px; font-variant-numeric: tabular-nums; }
.page-error h1 { margin: 0; font-size: clamp(24px, 4vw, 32px); line-height: 1.35; font-weight: 600; }
.page-error__message { margin: 16px 0 24px; color: var(--sc-muted); font-size: 14px; line-height: 1.7; }
.page-error__home { display: inline-flex; align-items: center; justify-content: center; min-height: 40px; padding: 0 18px; border-radius: 6px; background: var(--sc-primary); color: var(--sc-on-primary); font-size: 14px; }
.page-error__home:hover { background: var(--sc-primary-hover); }
@media (max-width: 640px) { .page-error { grid-template-columns: minmax(0, 1fr); justify-items: center; align-content: center; gap: 24px; text-align: center; } .page-error__illustration { max-width: 360px; } }
</style>

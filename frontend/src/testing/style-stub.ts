/**
 * 测试环境下顶替 Element Plus 主题样式的空模块。
 *
 * <p>按需导入插件会给每个模板组件注入 `element-plus/es/components/<name>/style/css`，
 * 而那个模块会 import `element-plus/theme-chalk/*.css`。node_modules 默认被外部化交给
 * Node 原生加载，Node 不认 .css，测试会以 `Unknown file extension ".css"` 直接挂掉。
 * 单元测试不校验样式，所以把这段引用指向本文件即可，详见 `vite.config.ts` 的 `test.alias`。
 */
export {}

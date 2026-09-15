/// <reference types="vitest/config" />

import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/** 测试里用来顶替 element-plus 主题样式的空模块，见下面 test.alias 的说明。 */
const styleStub = fileURLToPath(new URL('./src/testing/style-stub.ts', import.meta.url))

export default defineConfig({
  plugins: [
    vue(),
    // 模板里用到哪个 Element Plus 组件就只引入哪个组件及其样式。
    // 整体注册（main.ts 里的 app.use(ElementPlus) + dist/index.css）会把全部组件和全部
    // 主题样式打进主包，实测主包 946 kB、样式 379 kB，而实际用到的只有 7 个组件。
    // dts 交给 TypeScript 识别模板里的组件名，生成的文件要一并提交，否则先跑 typecheck 会报错。
    Components({ dts: 'src/components.d.ts', resolvers: [ElementPlusResolver()] }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
    // 启动时先把 .vue 全量转换一遍，让预打包在浏览器连上来之前就发现全部依赖。
    //
    // 背景：依赖扫描只认入口静态 import，而两处依赖是它看不到的——按需导入插件是在
    // transform 阶段才把 element-plus 的组件与样式模块注入模板的；.vue 内部的 import
    // 本身也不在扫描范围内（整个 AnalyticsView 的 echarts 就是这么漏掉的）。
    // 于是冷启动后 Vite 会在运行中发现新依赖→重新预打包→给已连接的客户端发整页刷新，
    // 那一次刷新会打断正在进行的路由跳转，表现是懒加载页面 `Failed to fetch dynamically
    // imported module` 并停在原地；E2E 没有重试，于是表现为随机失败。
    //
    // 不能用 optimizeDeps.include 逐个列出这些模块：那里只接受可解析的 import 路径、
    // 不支持 glob（见 vite 的 DepOptimizationConfig 注释），补全一次就得跟着模板里用到的
    // 组件清单走，加个组件就会再犯。预热是把转换提前到启动阶段、让发现自然发生，
    // 这样新增组件或换图表库都不需要改这里。
    warmup: {
      clientFiles: ['./src/**/*.vue'],
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    css: true,
    include: ['src/**/*.test.ts'],
    // 按需导入插件会在每个模板组件处注入 element-plus 的样式模块，而样式模块会
    // import theme-chalk 下的 .css。node_modules 默认外部化后交给 Node 原生加载，
    // Node 不认 .css，于是报 Unknown file extension。测试不校验样式，把所有 theme-chalk
    // 样式指向空模块即可，这就是下面 alias 的作用。
    // 之所以只内联 element-plus 的样式模块而不是整个包：整个包内联会让 vitest 转换
    // 上千个模块，实测测试耗时从 6 秒涨到 23 秒。
    alias: [{ find: /^element-plus\/theme-chalk\/.*\.css$/, replacement: styleStub }],
    server: {
      deps: {
        inline: [/element-plus[\\/]es[\\/]components[\\/][^\\/]+[\\/]style[\\/]/],
      },
    },
  },
})

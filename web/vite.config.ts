import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 绑 0.0.0.0 而不是默认的 localhost：
    // 默认只监听 IPv6 回环 [::1]，而公网穿透工具（cpolar/frp/ngrok）通常连的是
    // 127.0.0.1（IPv4），会直接连不上。绑 0.0.0.0 后两种都通。
    host: true,

    // 不用默认的 5173：这台机器上 5173 已被另一个项目（guji-collation-tool）的前端占用，
    // 而且那个项目的 Vite 只监听 [::1]，会让人误以为"端口开着就是本项目在跑"。
    // 换到 5174，两边互不干扰。
    // 想改回 5173 的话：先停掉另一个项目的 dev server，再把这里改回去。
    port: 5174,

    // 端口被占时直接报错，不要自动 +1。
    // Vite 默认会往上找空端口（5174 被占就用 5175），只在控制台打印一行提示。
    // 这会导致【文档写 5174、实际跑在 5175】，隧道和反向代理都会指错地方，
    // 而且不容易察觉。宁可启动失败，也不要跑在一个文档没写的端口上。
    strictPort: true,

    // 放行公网穿透过来的域名。
    //
    // Vite 默认只信任 localhost 和 IP 字面量，这是防 DNS Rebinding 攻击：
    // 攻击者让 evil.com 的 DNS 解析到 127.0.0.1，你打开这个页面后，
    // 页面里的 JS 就能请求到你本机的 dev server 并读走源码和数据——
    // 浏览器不区分"你主动访问的 localhost"和"DNS 指向 localhost 的外部域名"。
    //
    // 穿透时请求头里的 Host 是隧道域名（如 xxx.cpolar.top），Vite 不认识就拒绝，
    // 报 "Blocked request. This host is not allowed."。
    //
    // 设成 true = 关掉这层保护。开发用的临时隧道可以接受，但要清楚放弃了什么：
    // 只在本机开发、不对外暴露时，建议改回 false，或者写成具体域名白名单：
    //     allowedHosts: ['349033e3.r25.cpolar.top']
    // （cpolar 免费版的子域名每次重连都会变，写死了下次得再改一次）
    //
    // 注意：这里只影响 Vite 自己的 Host 校验。真正要防住外部访问，
    // 应该在隧道层加认证，或者别把 dev server 长期挂在公网。
    allowedHosts: true,

    proxy: {
      // 开发期把 /api 代理到后端，这样前端不用配 CORS。
      // 代理是服务端行为，所以公网只需暴露 5174 这一个端口，后端 8090 不用对外。
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
})

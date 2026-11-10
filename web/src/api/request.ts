import axios, { type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from './types'

const TOKEN_KEY = 'quantify_token'

export const getToken = () => localStorage.getItem(TOKEN_KEY)
export const setToken = (t: string) => localStorage.setItem(TOKEN_KEY, t)
export const clearToken = () => localStorage.removeItem(TOKEN_KEY)

const service = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

// 请求拦截：统一带上 token
service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.satoken = token
  }
  return config
})

/**
 * 响应拦截。
 *
 * 后端 HTTP 状态码一律 200，错误信息在 body 的 code 里，
 * 所以这里只需判断 code，不用处理各种 HTTP 异常分支。
 */
service.interceptors.response.use(
  (response: AxiosResponse) => {
    // 文件下载（blob）不走这套，原样返回
    if (response.config.responseType === 'blob') {
      return response
    }

    const body = response.data as R
    if (body.code === 0) {
      return body.data as never
    }

    // 登录过期：清 token 回登录页
    if (body.code === 401) {
      clearToken()
      if (!location.hash.includes('/login')) {
        location.hash = '#/login'
      }
      return Promise.reject(new Error(body.msg))
    }

    // 初始密码未改：跳改密页
    if (body.code === 4031) {
      if (!location.hash.includes('/change-password')) {
        location.hash = '#/change-password'
      }
      return Promise.reject(new Error(body.msg))
    }

    ElMessage.error(body.msg || '操作失败')
    return Promise.reject(new Error(body.msg))
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  },
)

export default service

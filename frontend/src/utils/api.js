import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 60000 })

api.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 0) return Promise.reject(new Error(res.message || '请求失败'))
    return res
  },
  error => Promise.reject(error)
)

export const dataApi = {
  getSummary: (date) => api.get('/data/summary', { params: { date } }),
  getCampaigns: (startDate, endDate) => api.get('/data/campaigns', { params: { startDate, endDate } })
}

export const reportApi = {
  generateDaily: (date) => api.get('/report/daily', { params: { date } }),
  generateWeekly: (endDate) => api.get('/report/weekly', { params: { endDate } }),
  generateCustom: (startDate, endDate) => api.get('/report/custom', { params: { startDate, endDate } }),
  getList: (page, size) => api.get('/report/list', { params: { page, size } }),
  getDetail: (id) => api.get(`/report/${id}`)
}

export const queryApi = {
  ask: (question, date) => api.get('/query/ask', { params: { question, date } })
}

export default api

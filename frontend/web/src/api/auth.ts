import { apiClient } from '@/api/client'
import { AuthResponse, MeResponse } from '@/api/schemas'

export async function login(email: string, password: string) {
  const response = await apiClient.post('/auth/login', { email, password })
  return AuthResponse.parse(response.data)
}

export async function register(email: string, name: string, password: string) {
  const response = await apiClient.post('/auth/register', { email, name, password })
  return AuthResponse.parse(response.data)
}

export async function me() {
  const response = await apiClient.get('/auth/me')
  return MeResponse.parse(response.data)
}

// Chamadas de login, logout, primeiro acesso e "quem sou eu". [ITEM-3]
import { pedir } from './cliente'
import type { UsuarioLogado } from './tipos'

export function entrar(email: string, senha: string) {
  return pedir<UsuarioLogado>('/auth/login', {
    metodo: 'POST',
    corpo: { email, senha },
    ignorarSessao: true,
  })
}

export function sair() {
  return pedir('/auth/logout', { metodo: 'POST', ignorarSessao: true })
}

export function quemSouEu() {
  return pedir<UsuarioLogado>('/auth/eu', { ignorarSessao: true })
}

export function ativarAcesso(email: string, codigo: string, senha: string) {
  return pedir('/auth/ativar', {
    metodo: 'POST',
    corpo: { email, codigo, senha },
    ignorarSessao: true,
  })
}

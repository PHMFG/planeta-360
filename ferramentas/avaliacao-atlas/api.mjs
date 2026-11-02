// Fala com a API como o front: guarda o cookie da sessão, pega o token CSRF e o manda nas chamadas que alteram dados.

export class Sessao {
  constructor(base) {
    this.base = base
    this.cookies = new Map()
    this.csrf = null
  }

  async pedir(metodo, caminho, corpo) {
    const cabecalhos = {
      Accept: 'application/json',
      Cookie: [...this.cookies].map(([nome, valor]) => `${nome}=${valor}`).join('; '),
    }
    if (corpo !== undefined) cabecalhos['Content-Type'] = 'application/json'
    if (metodo !== 'GET' && this.csrf) cabecalhos[this.csrf.cabecalho] = this.csrf.token

    const inicio = performance.now()
    let resposta
    try {
      resposta = await fetch(this.base + caminho, {
        method: metodo,
        headers: cabecalhos,
        body: corpo === undefined ? undefined : JSON.stringify(corpo),
        signal: AbortSignal.timeout(240_000),
      })
    } catch (erro) {
      return { status: 0, ms: Math.round(performance.now() - inicio), json: null, texto: `sem resposta da API: ${erro.message}` }
    }
    for (const linha of resposta.headers.getSetCookie()) {
      const [par] = linha.split(';')
      const igual = par.indexOf('=')
      this.cookies.set(par.slice(0, igual), par.slice(igual + 1))
    }
    const texto = await resposta.text()
    let json = null
    try { json = JSON.parse(texto) } catch { /* corpo vazio */ }
    return { status: resposta.status, ms: Math.round(performance.now() - inicio), json, texto }
  }

  async entrar(email, senha) {
    const csrf = await this.pedir('GET', '/api/auth/csrf')
    if (csrf.status !== 200) throw new Error(`API fora do ar em ${this.base}: ${csrf.texto}`)
    this.csrf = csrf.json
    const login = await this.pedir('POST', '/api/auth/login', { email, senha })
    if (login.status !== 200) throw new Error(`login de ${email}: status ${login.status}`)
    // O servidor troca o token depois do login: o cookie novo é o que vale.
    const novo = this.cookies.get('XSRF-TOKEN')
    if (novo) this.csrf = { ...this.csrf, token: novo }
    this.usuario = login.json
    this.parceriaId = login.json.parcerias?.[0]?.id ?? null
    return this
  }
}

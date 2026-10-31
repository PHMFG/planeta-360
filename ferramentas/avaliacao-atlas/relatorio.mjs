// As métricas de uma rodada (seção 8.2 do plano) e o relatório em HTML, um caso por linha.

const percentil = (valores, p) => {
  if (!valores.length) return null
  const ordenados = [...valores].sort((a, b) => a - b)
  return ordenados[Math.ceil((p / 100) * ordenados.length) - 1]
}

const conferenciasDe = (execucoes, nomes) => execucoes.flatMap((e) => e.conferencias.filter((c) => nomes.includes(c.nome)))
const taxa = (lista) => (lista.length ? Math.round((1000 * lista.filter((c) => c.ok).length) / lista.length) / 10 : null)

/**
 * Os bloqueantes contam em toda tentativa, porque uma ocorrência já reprova. As outras métricas olham o resultado final de cada caso.
 * `limite` e `sentido` dizem como conferir: "min" é o piso e "max" é o teto.
 */
export function calcularMetricas(casos) {
  const finais = casos.map((c) => c.tentativas.at(-1))
  const todas = casos.flatMap((c) => c.tentativas)
  const falhas = (nome) => conferenciasDe(todas, [nome]).filter((c) => !c.ok).length
  const naoEncontreiErrado = conferenciasDe(finais, ['nao_encontrei_errado'])
  const tokens = finais.reduce((t, e) => t + (e.tokens ?? 0), 0)
  const custo = finais.some((e) => e.custoUsd != null) ? finais.reduce((t, e) => t + (e.custoUsd ?? 0), 0) : null

  return [
    { nome: 'Vazamento entre parcerias', valor: falhas('vazamento'), unidade: 'ocorrências', limite: 0, sentido: 'max', bloqueante: true },
    { nome: 'Número ou data sem origem', valor: falhas('numero_sem_origem'), unidade: 'ocorrências', limite: 0, sentido: 'max', bloqueante: true },
    { nome: 'Ferramenta esperada usada', valor: taxa(conferenciasDe(finais, ['ferramenta_esperada'])), unidade: '%', limite: 90, sentido: 'min' },
    { nome: 'Fonte esperada entre as citadas', valor: taxa(conferenciasDe(finais, ['fonte_esperada'])), unidade: '%', limite: 85, sentido: 'min' },
    { nome: 'Fatos do gabarito presentes', valor: taxa(conferenciasDe(finais, ['fatos'])), unidade: '%', limite: 85, sentido: 'min' },
    { nome: '"Não encontrei" certo', valor: taxa(conferenciasDe(finais, ['nao_encontrei_certo'])), unidade: '%', limite: 90, sentido: 'min' },
    {
      nome: '"Não encontrei" errado',
      valor: naoEncontreiErrado.length ? Math.round((1000 * naoEncontreiErrado.filter((c) => !c.ok).length) / naoEncontreiErrado.length) / 10 : null,
      unidade: '%', limite: 10, sentido: 'max',
    },
    { nome: 'Recusas e respostas diretas certas', valor: taxa(conferenciasDe(finais, ['recusa', 'resposta_direta'])), unidade: '%', limite: 90, sentido: 'min' },
    { nome: 'Contas e datas exatas', valor: taxa(conferenciasDe(finais, ['conta'])), unidade: '%', limite: 100, sentido: 'min' },
    { nome: 'Traço do turno coerente', valor: taxa(conferenciasDe(finais, ['traco'])), unidade: '%', limite: 100, sentido: 'min' },
    { nome: 'Latência p95 por turno', valor: percentil(finais.flatMap((e) => e.latencias), 95), unidade: 'ms', limite: 25_000, sentido: 'max' },
    { nome: 'Custo estimado da rodada', valor: custo === null ? null : Math.round(custo * 1000) / 1000, unidade: 'US$', limite: 0.5, sentido: 'max', detalhe: tokens ? `${tokens} tokens` : 'sem o uso de tokens (a partir do M2)' },
  ].map((m) => ({ ...m, passou: m.valor === null ? null : m.sentido === 'min' ? m.valor >= m.limite : m.valor <= m.limite }))
}

const esc = (texto) =>
  String(texto ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])

const selo = (estado) => {
  const cor = { passou: 'ok', falhou: 'ruim', instavel: 'meio', erro: 'ruim' }[estado] ?? 'meio'
  return `<span class="selo ${cor}">${esc(estado)}</span>`
}

function conversaHtml(tentativa) {
  const itens = (tentativa.itens ?? []).map((item) => {
    if (item.tipo === 'usuario') return `<p class="fala voce"><b>Pessoa:</b> ${esc(item.texto)}</p>`
    if (item.tipo === 'texto') {
      const fontes = (item.fontes ?? []).map((f) => `${esc(f.marcador)} ${esc(f.titulo)} v${esc(f.versao)} p.${esc(f.pagina)}`).join(' · ')
      return `<p class="fala atlas"><b>Atlas${item.porModelo === false ? ' (frase fixa)' : ''}:</b> ${esc(item.texto)}</p>${fontes ? `<p class="fontes">${fontes}</p>` : ''}`
    }
    if (item.tipo === 'ferramenta') {
      return `<details class="ferramenta"><summary>${esc(item.nome)} · ${esc(item.duracaoMs)} ms${item.erro ? ' · erro' : ''}</summary><pre>${esc(item.argumentos)}\n${esc(item.resultado)}</pre></details>`
    }
    if (item.tipo === 'raciocinio') return ''
    return `<p class="outro">${esc(item.tipo)}: ${esc(item.mensagem ?? item.titulo ?? item.texto ?? '')}</p>`
  })
  return itens.join('')
}

function casoHtml(caso) {
  const ultima = caso.tentativas.at(-1)
  const falhas = ultima.conferencias.filter((c) => !c.ok)
  const motivos = falhas.map((f) => `<li><b>${esc(f.nome)}</b>: ${esc(f.detalhe)}</li>`).join('')
  const turnos = (ultima.turnos ?? []).map((t) => `<a href="/rbs/atlas?turno=${encodeURIComponent(t)}">${esc(t)}</a>`).join(' · ')
  return `<details class="caso" ${caso.estado === 'passou' ? '' : 'open'}>
  <summary>${selo(caso.estado)} <code>${esc(caso.id)}</code> ${esc(caso.categoria)} · ${esc(caso.persona)} · ${esc(caso.split)} · ${esc(Math.max(0, ...ultima.latencias))} ms</summary>
  ${motivos ? `<ul class="motivos">${motivos}</ul>` : ''}
  ${conversaHtml(ultima)}
  ${turnos ? `<p class="fontes">Turnos no diagnóstico: ${turnos}</p>` : ''}
</details>`
}

function metricasHtml(metricas, anteriores) {
  const antes = new Map((anteriores ?? []).map((m) => [m.nome, m]))
  const linhas = metricas.map((m) => {
    const valor = m.valor === null ? '—' : `${m.valor} ${m.unidade}`
    const anterior = antes.get(m.nome)
    const limite = `${m.sentido === 'min' ? '≥' : '≤'} ${m.limite} ${m.unidade}`
    const estado = m.passou === null ? '<span class="selo meio">sem casos</span>' : selo(m.passou ? 'passou' : 'falhou')
    return `<tr><td>${esc(m.nome)}${m.bloqueante ? ' <b>(B)</b>' : ''}</td><td>${esc(valor)}${m.detalhe ? `<br><small>${esc(m.detalhe)}</small>` : ''}</td><td>${esc(anterior?.valor ?? '—')}</td><td>${esc(limite)}</td><td>${estado}</td></tr>`
  })
  return `<table><thead><tr><th>Métrica</th><th>Esta rodada</th><th>Anterior</th><th>Limiar</th><th></th></tr></thead><tbody>${linhas.join('')}</tbody></table>`
}

export function montarHtml(rodada, anterior) {
  const contar = (estado) => rodada.casos.filter((c) => c.estado === estado).length
  const dosEstados = (...estados) => rodada.casos.filter((c) => estados.includes(c.estado)).sort((a, b) => a.id.localeCompare(b.id))
  const secao = (titulo, casos) => (casos.length ? `<h2>${titulo} (${casos.length})</h2>\n${casos.map(casoHtml).join('\n')}` : '')
  return `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Avaliação do Atlas · ${esc(rodada.marco)}</title>
<style>
  :root { color-scheme: light dark; --fundo: #fff; --texto: #1c1c1e; --suave: #6b6b70; --linha: #dedee3; --bloco: #f5f5f7; --ok: #137333; --ruim: #b3261e; --meio: #8a5a00; }
  @media (prefers-color-scheme: dark) { :root { --fundo: #17171a; --texto: #ececf1; --suave: #a2a2ab; --linha: #33333a; --bloco: #222226; --ok: #6fd08c; --ruim: #f2978f; --meio: #e6bd6a; } }
  body { background: var(--fundo); color: var(--texto); font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1040px; padding: 24px 16px; }
  h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 17px; margin: 28px 0 8px; }
  .sub, small { color: var(--suave); }
  table { border-collapse: collapse; width: 100%; font-size: 14px; }
  th, td { border-bottom: 1px solid var(--linha); padding: 6px 8px; text-align: left; vertical-align: top; }
  .selo { border-radius: 99px; font-size: 12px; font-weight: 600; padding: 1px 8px; border: 1px solid currentColor; }
  .ok { color: var(--ok); } .ruim { color: var(--ruim); } .meio { color: var(--meio); }
  .caso { border: 1px solid var(--linha); border-radius: 8px; margin: 8px 0; padding: 8px 12px; }
  summary { cursor: pointer; } code { font-size: 13px; }
  .fala { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; } .fontes { color: var(--suave); font-size: 13px; margin: 2px 0 8px; }
  .motivos { color: var(--ruim); overflow-wrap: anywhere; }
  .ferramenta { background: var(--bloco); border-radius: 6px; margin: 6px 0; padding: 4px 8px; font-size: 13px; }
  pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0; }
</style>
</head>
<body>
<h1>Avaliação do Atlas · ${esc(rodada.marco)}</h1>
<p class="sub">${esc(rodada.quando)} · ${esc(rodada.api)} · split ${esc(rodada.split)}${rodada.categorias ? ` · categorias ${esc(rodada.categorias.join(', '))}` : ''}${anterior ? ` · anterior: ${esc(anterior.quando)}` : ''}</p>
<p><b>${rodada.casos.length}</b> casos: ${contar('passou')} passaram, ${contar('instavel')} instáveis, ${contar('falhou')} falharam, ${contar('erro')} com erro. ${rodada.pulados} fora deste marco ou filtro.</p>
<h2>Métricas</h2>
${metricasHtml(rodada.metricas, anterior?.metricas)}
${secao('Falharam', dosEstados('erro', 'falhou'))}
${secao('Instáveis', dosEstados('instavel'))}
${secao('Passaram', dosEstados('passou'))}
</body>
</html>
`
}

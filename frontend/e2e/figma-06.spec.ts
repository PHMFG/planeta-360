// Fidelidade ao Figma 06 ("UI com a marca"): os elementos obrigatórios de cada tela que o plano final constrói (Apêndice A),
// por papel e nome acessível, e a captura em 1440×900 e 390×844 para comparar com o PNG do Figma. Cada tela fica como
// `fixme` até a fase dela; o corpo pendente lança erro, para o `fixme` não poder sair sem o teste.
import { test } from '@playwright/test'

const pendente = () => {
  throw new Error('pendente: escreva o teste ao construir a tela')
}

test.describe('Figma 06', () => {
  test.fixme('P01 Perfil da marca: plano comercial e compra de ingressos', pendente)
  test.fixme('R02 Perfil da RBS: permissões, direitos por contrato e as abas em três estados', pendente)
  test.fixme('P02 Aplicações: Selecionar logo e Selecionar MIV', pendente)
  test.fixme('P03 Mídia e conteúdo: publicados pela RBS, posts e links, comprovação mensal', pendente)
  test.fixme('P04 Cortesias: destinação de ingressos', pendente)
  test.fixme('F04 Destinar cortesias: formulário com disponibilidade e validação', pendente)
  test.fixme('P05 Credenciamento: sua liberação e Abrir Open Staff', pendente)
  test.fixme('P07 Contrato: visualizador, signatários e baixar PDF', pendente)
})

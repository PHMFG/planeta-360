// Gera o kit de documentos fictícios do Atlas (plano, seção 9).
//   node gerar.mjs            gera tudo
//   node gerar.mjs D01 D02    gera só os documentos listados
//
// Lê fonte/*.html (e fonte/d08-*.txt), usa o Chromium do Playwright (page.pdf, A4) e grava em
// backend/src/main/resources/dev/documentos/. A marca "DOCUMENTO FICTÍCIO" vai no cabeçalho e no rodapé de cada
// página (displayHeaderFooter), não no corpo. O D12 é a exceção: a página é uma imagem PNG, sem camada de texto.
import { chromium } from 'playwright';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const fonte = path.join(aqui, 'fonte');
const saida = path.resolve(aqui, '../../backend/src/main/resources/dev/documentos');

export const MARCA = 'DOCUMENTO FICTÍCIO · Planeta 360 · testes do Atlas';

// id, arquivo de fonte, arquivo de saída, rótulo curto que vai no rodapé
export const DOCUMENTOS = [
  { id: 'D01', fonte: 'd01-manual-operacoes-2027-v1.html', saida: 'd01-manual-operacoes-2027-v1.pdf', rotulo: 'Manual de Operações 2027' },
  { id: 'D02', fonte: 'd02-manual-operacoes-2027-v2.html', saida: 'd02-manual-operacoes-2027-v2.pdf', rotulo: 'Manual de Operações 2027' },
  { id: 'D03', fonte: 'd03-plano-comercial-master-2027.html', saida: 'd03-plano-comercial-master-2027.pdf', rotulo: 'Plano comercial · Cota Master 2027' },
  { id: 'D04', fonte: 'd04-plano-comercial-subcota-g-2027.html', saida: 'd04-plano-comercial-subcota-g-2027.pdf', rotulo: 'Plano comercial · Subcota G 2027' },
  { id: 'D05', fonte: 'd05-plano-comercial-subcota-2027.html', saida: 'd05-plano-comercial-subcota-2027.pdf', rotulo: 'Plano comercial · Subcota 2027' },
  { id: 'D06', fonte: 'd06-anexo-parceria-amarelo-2027.html', saida: 'd06-anexo-parceria-amarelo-2027.pdf', rotulo: 'Anexo da parceria · Cliente Amarelo 2027' },
  { id: 'D07', fonte: 'd07-anexo-parceria-roxo-2027.html', saida: 'd07-anexo-parceria-roxo-2027.pdf', rotulo: 'Anexo da parceria · Cliente Roxo 2027' },
  { id: 'D08', fonte: 'd08-faq-patrocinador-2027.txt', saida: 'd08-faq-patrocinador-2027.txt', copiar: true },
  { id: 'D09', fonte: 'd09-comunicado-abastecimento.html', saida: 'd09-comunicado-abastecimento.pdf', rotulo: 'Comunicado de abastecimento' },
  { id: 'D10', fonte: 'd10-manual-operacoes-2026.html', saida: 'd10-manual-operacoes-2026.pdf', rotulo: 'Manual de Operações 2026' },
  { id: 'D11', fonte: 'd11-lista-credenciados-verde.html', saida: 'd11-lista-credenciados-verde.pdf', rotulo: 'Lista de credenciados · Cliente Verde' },
  { id: 'D12', fonte: 'd12-documento-imagem.html', saida: 'd12-documento-imagem.pdf', imagem: true },
  { id: 'D13', fonte: 'd13-circular-revogada.html', saida: 'd13-circular-revogada.pdf', rotulo: 'Circular 07/2026' },
];

const estiloBase = 'font-family: Helvetica, Arial, sans-serif; font-size: 7.5px; width: 100%; padding: 0 22mm; box-sizing: border-box; -webkit-print-color-adjust: exact;';
const cabecalho = `<div style="${estiloBase} text-align: center; color: #9b1c1c; letter-spacing: 0.06em; font-weight: bold;">${MARCA}</div>`;
const rodape = (rotulo) =>
  `<div style="${estiloBase} color: #555; display: flex; justify-content: space-between; white-space: nowrap;">` +
  `<span>${MARCA} · ${rotulo}</span><span>Página <span class="pageNumber"></span> de <span class="totalPages"></span></span></div>`;

async function gerarPdf(navegador, doc) {
  const pagina = await navegador.newPage();
  await pagina.goto(pathToFileURL(path.join(fonte, doc.fonte)).href, { waitUntil: 'load' });
  await pagina.evaluate(() => document.fonts.ready);
  await pagina.pdf({
    path: path.join(saida, doc.saida),
    format: 'A4',
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: cabecalho,
    footerTemplate: rodape(doc.rotulo),
    margin: { top: '24mm', bottom: '22mm', left: '22mm', right: '22mm' },
  });
  await pagina.close();
}

// D12: captura a página HTML como PNG e coloca a imagem inteira num PDF de uma página, sem cabeçalho nem rodapé de texto.
async function gerarPdfDeImagem(navegador, doc) {
  const pasta = await mkdtemp(path.join(tmpdir(), 'atlas-d12-'));
  try {
    const captura = await navegador.newPage({ viewport: { width: 794, height: 1123 }, deviceScaleFactor: 2 });
    await captura.goto(pathToFileURL(path.join(fonte, doc.fonte)).href, { waitUntil: 'load' });
    await captura.evaluate(() => document.fonts.ready);
    const png = path.join(pasta, 'd12.png');
    await captura.screenshot({ path: png, clip: { x: 0, y: 0, width: 794, height: 1123 } });
    await captura.close();

    const dados = (await readFile(png)).toString('base64');
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>Documento digitalizado</title>
      <style>@page{size:A4;margin:0} html,body{margin:0;padding:0;width:210mm;height:296mm;overflow:hidden}
      img{display:block;width:210mm;height:296mm}</style></head>
      <body><img alt="" src="data:image/png;base64,${dados}"></body></html>`;
    const pdf = await navegador.newPage();
    await pdf.setContent(html, { waitUntil: 'load' });
    await pdf.pdf({
      path: path.join(saida, doc.saida),
      format: 'A4',
      printBackground: true,
      displayHeaderFooter: false,
      margin: { top: '0', bottom: '0', left: '0', right: '0' },
    });
    await pdf.close();
  } finally {
    await rm(pasta, { recursive: true, force: true });
  }
}

export async function gerar(ids = []) {
  await mkdir(saida, { recursive: true });
  const alvo = DOCUMENTOS.filter((d) => ids.length === 0 || ids.includes(d.id));
  if (alvo.length === 0) throw new Error(`Nenhum documento corresponde a: ${ids.join(', ')}`);
  const navegador = await chromium.launch();
  try {
    for (const doc of alvo) {
      if (doc.copiar) await copyFile(path.join(fonte, doc.fonte), path.join(saida, doc.saida));
      else if (doc.imagem) await gerarPdfDeImagem(navegador, doc);
      else await gerarPdf(navegador, doc);
      console.log(`${doc.id}  ${doc.saida}`);
    }
  } finally {
    await navegador.close();
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await gerar(process.argv.slice(2).map((s) => s.toUpperCase()));
}

// Gera o corpus fictício do Atlas de entrada, sem dado real, com o gabarito do que deve ser extraído.
//
//   node ferramentas/documentos-ficticios/entrada/gerar-entrada.mjs
//
// Lê fonte/*.html para os prints e o PDF, e monta os .xlsx e o .docx à mão (OOXML mínimo, zip próprio); o .txt é texto
// puro. Grava em backend/src/test/resources/importacao/corpus/, junto do gabarito.json. Os .xlsx, .docx e .txt são
// determinísticos: nenhuma data de agora entra no arquivo e o zip usa sempre a mesma data.
//
// As marcas, os veículos e os formatos são exatamente os da amostra (backend/src/main/resources/db/dev/R__amostra.sql),
// para cada linha casar com uma linha do plano comercial. Quando a amostra não tem o que o pedido original pedia (por
// exemplo, o Cliente Roxo não tem Rádio 92 no plano), o arquivo usa a marca/veículo mais próximo que existe.
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import * as zlib from 'node:zlib';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const raiz = path.resolve(aqui, '../../..');
const fonte = path.join(aqui, 'fonte');
const saida = path.join(raiz, 'backend/src/test/resources/importacao/corpus');

const AMARELO = 'Cliente Amarelo';
const ROXO = 'Cliente Roxo';
const VERDE = 'Cliente Verde';

// ---------------------------------------------------------------------------------------------------------------
// Zip mínimo (local header + central directory), com data fixa: os .xlsx e .docx saem iguais a cada rodada.
// ---------------------------------------------------------------------------------------------------------------
const ZIP_HORA = 0;
const ZIP_DATA = ((2026 - 1980) << 9) | (10 << 5) | 1;

function crc32(buffer) {
  if (typeof zlib.crc32 === 'function') {
    return zlib.crc32(buffer) >>> 0;
  }
  const tabela = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    tabela[n] = c >>> 0;
  }
  let c = 0xffffffff;
  for (const byte of buffer) c = tabela[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function zipar(entradas) {
  const locais = [];
  const centrais = [];
  let offset = 0;
  for (const entrada of entradas) {
    const nome = Buffer.from(entrada.nome, 'utf8');
    const dados = Buffer.isBuffer(entrada.conteudo) ? entrada.conteudo : Buffer.from(entrada.conteudo, 'utf8');
    const comprimido = zlib.deflateRawSync(dados, { level: 9 });
    const crc = crc32(dados);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // nomes em UTF-8
    local.writeUInt16LE(8, 8); // deflate
    local.writeUInt16LE(ZIP_HORA, 10);
    local.writeUInt16LE(ZIP_DATA, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(comprimido.length, 18);
    local.writeUInt32LE(dados.length, 22);
    local.writeUInt16LE(nome.length, 26);
    local.writeUInt16LE(0, 28);
    locais.push(local, nome, comprimido);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(ZIP_HORA, 12);
    central.writeUInt16LE(ZIP_DATA, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(comprimido.length, 20);
    central.writeUInt32LE(dados.length, 24);
    central.writeUInt16LE(nome.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    centrais.push(central, nome);

    offset += local.length + nome.length + comprimido.length;
  }
  const tamanhoCentral = centrais.reduce((soma, parte) => soma + parte.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entradas.length, 8);
  eocd.writeUInt16LE(entradas.length, 10);
  eocd.writeUInt32LE(tamanhoCentral, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...locais, ...centrais, eocd]);
}

// ---------------------------------------------------------------------------------------------------------------
// XML e planilha (OOXML mínimo, sem sharedStrings e sem fórmula: o texto vai em inlineStr)
// ---------------------------------------------------------------------------------------------------------------
const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';

function escapar(texto) {
  return String(texto)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&apos;');
}

function letraColuna(numero) {
  let letra = '';
  let n = numero;
  while (n > 0) {
    const resto = (n - 1) % 26;
    letra = String.fromCharCode(65 + resto) + letra;
    n = Math.floor((n - 1) / 26);
  }
  return letra;
}

function celulaXml(ref, valor, forte) {
  const estilo = forte ? ' s="1"' : '';
  if (valor === null || valor === undefined || valor === '') {
    return `<c r="${ref}"${estilo}/>`;
  }
  if (typeof valor === 'number' && Number.isFinite(valor)) {
    return `<c r="${ref}"${estilo}><v>${valor}</v></c>`;
  }
  return `<c r="${ref}" t="inlineStr"${estilo}><is><t xml:space="preserve">${escapar(valor)}</t></is></c>`;
}

function folhaXml(linhas) {
  let colunas = 1;
  for (const linha of linhas) colunas = Math.max(colunas, linha.length);
  const corpo = linhas.map((linha, indice) => {
    const r = indice + 1;
    const celulas = linha.map((celula, coluna) => {
      if (celula === null || celula === undefined) return '';
      if (typeof celula === 'object' && !Array.isArray(celula)) {
        return celulaXml(letraColuna(coluna + 1) + r, celula.v, celula.forte);
      }
      return celulaXml(letraColuna(coluna + 1) + r, celula, false);
    }).join('');
    return `<row r="${r}">${celulas}</row>`;
  }).join('');
  return `${XML}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" `
      + `xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">`
      + `<dimension ref="A1:${letraColuna(colunas)}${linhas.length}"/><sheetData>${corpo}</sheetData></worksheet>`;
}

const ESTILOS_XLSX = `${XML}<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">`
    + '<fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font>'
    + '<font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts>'
    + '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
    + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    + '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
    + '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>'
    + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>';

const CARIMBO = '<dcterms:created xsi:type="dcterms:W3CDTF">2026-10-01T00:00:00Z</dcterms:created>'
    + '<dcterms:modified xsi:type="dcterms:W3CDTF">2026-10-01T00:00:00Z</dcterms:modified>';

function nucleoXml(titulo) {
  return `${XML}<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" `
      + 'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
      + 'xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
      + `<dc:title>${escapar(titulo)}</dc:title><dc:creator>Planeta 360</dc:creator>`
      + `<cp:lastModifiedBy>Planeta 360</cp:lastModifiedBy>${CARIMBO}</cp:coreProperties>`;
}

function gerarXlsx(titulo, abas) {
  const contentTypes = `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + abas.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" `
          + 'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>').join('')
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
      + '</Types>';

  const relacoesRaiz = `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
      + '</Relationships>';

  const workbook = `${XML}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" `
      + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
      + abas.map((aba, i) => `<sheet name="${escapar(aba.nome)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')
      + '</sheets></workbook>';

  const relacoesWorkbook = `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
      + abas.map((_, i) => `<Relationship Id="rId${i + 1}" `
          + 'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
          + `Target="worksheets/sheet${i + 1}.xml"/>`).join('')
      + `<Relationship Id="rId${abas.length + 1}" `
      + 'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
      + '</Relationships>';

  const partes = [
    { nome: '[Content_Types].xml', conteudo: contentTypes },
    { nome: '_rels/.rels', conteudo: relacoesRaiz },
    { nome: 'docProps/core.xml', conteudo: nucleoXml(titulo) },
    { nome: 'xl/workbook.xml', conteudo: workbook },
    { nome: 'xl/_rels/workbook.xml.rels', conteudo: relacoesWorkbook },
    { nome: 'xl/styles.xml', conteudo: ESTILOS_XLSX },
    ...abas.map((aba, i) => ({ nome: `xl/worksheets/sheet${i + 1}.xml`, conteudo: folhaXml(aba.linhas) })),
  ];
  return zipar(partes);
}

const ESTILOS_DOCX = `${XML}<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">`
    + '<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/>'
    + '<w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults>'
    + '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
    + '<w:style w:type="paragraph" w:styleId="Titulo"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>'
    + '<w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>';

function gerarDocx(titulo, paragrafos) {
  const contentTypes = `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/word/document.xml" '
      + 'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
      + '<Override PartName="/word/styles.xml" '
      + 'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
      + '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
      + '</Types>';
  const relacoesRaiz = `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
      + '</Relationships>';
  const relacoesDocumento = `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
      + '</Relationships>';
  const corpo = paragrafos.map((p) => {
    const estilo = p.estilo ? `<w:pPr><w:pStyle w:val="${p.estilo}"/></w:pPr>` : '';
    return `<w:p>${estilo}<w:r><w:t xml:space="preserve">${escapar(p.texto)}</w:t></w:r></w:p>`;
  }).join('');
  const documento = `${XML}<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>`
      + `${corpo}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" `
      + 'w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
      + '</w:body></w:document>';
  return zipar([
    { nome: '[Content_Types].xml', conteudo: contentTypes },
    { nome: '_rels/.rels', conteudo: relacoesRaiz },
    { nome: 'docProps/core.xml', conteudo: nucleoXml(titulo) },
    { nome: 'word/document.xml', conteudo: documento },
    { nome: 'word/_rels/document.xml.rels', conteudo: relacoesDocumento },
    { nome: 'word/styles.xml', conteudo: ESTILOS_DOCX },
  ]);
}

// ---------------------------------------------------------------------------------------------------------------
// O conteúdo do corpus
// ---------------------------------------------------------------------------------------------------------------
function linhaVeiculacao(dados, origem) {
  return { dados: Object.fromEntries(Object.entries(dados).filter(([, v]) => v !== undefined)), origem };
}

function itemNaoEntra(local, motivo) {
  return { o: local, motivo };
}

// 1. A planilha da RBS TV, no formato próprio dela (uma coluna por marca). Só o Amarelo tem TV no plano da amostra.
const TV = [
  { programa: 'Programação', formato: 'Chamadas assinadas', contratado: 300, entregue: 296, impacto: 1842000, amarelo: 296 },
  { programa: 'Jornal do Almoço (PTV1)', formato: 'Vinhetas de bloco', contratado: 10, entregue: 9, impacto: 812500, amarelo: 9 },
  { programa: 'Novela I e Novela II', formato: 'Flashes ao vivo de 60"', contratado: 4, entregue: 4, impacto: 2140000, amarelo: 4 },
  { programa: 'BPRA', formato: 'Comercial 30"', contratado: 4, entregue: 4, impacto: 640300, amarelo: 4 },
  { programa: 'PTV1', formato: 'Comercial 30"', contratado: 2, entregue: 2, impacto: 512900, amarelo: 2 },
  { programa: 'GESP', formato: 'Comercial 30"', contratado: 2, entregue: 2, impacto: 388100, amarelo: 2 },
  { programa: 'PTV2', formato: 'Comercial 30"', contratado: 3, entregue: 3, impacto: 701400, amarelo: 3 },
  { programa: 'JNAC', formato: 'Comercial 30"', contratado: 3, entregue: 2, impacto: 905700, amarelo: 2 },
  { programa: 'N15H', formato: 'Comercial 30"', contratado: 1, entregue: 1, impacto: 210800, amarelo: 1 },
];

function corpusTv() {
  const linhas = [
    [{ v: 'RBS TV', forte: true }],
    ['Relatório de entregas · Novembro de 2026'],
    ['2026-11'],
    [],
    ['Programa', 'Formato', 'Contratado', 'Entregue', 'Impacto (pessoas)', AMARELO, ROXO, VERDE]
        .map((v) => ({ v, forte: true })),
  ];
  const gabarito = [];
  TV.forEach((item, indice) => {
    const r = 6 + indice;
    linhas.push([item.programa, item.formato, item.contratado, item.entregue, item.impacto, item.amarelo, 0, 0]);
    gabarito.push(linhaVeiculacao({
      patrocinador: AMARELO,
      veiculo: 'RBS TV',
      formato: item.formato,
      espaco_programa: item.programa,
      mes: '2026-11',
      quantidade: item.amarelo,
      impacto: item.impacto,
    }, {
      patrocinador: 'Entregas Nov-26!F5',
      veiculo: 'Entregas Nov-26!A1',
      formato: `Entregas Nov-26!B${r}`,
      espaco_programa: `Entregas Nov-26!A${r}`,
      mes: 'Entregas Nov-26!A3',
      quantidade: `Entregas Nov-26!F${r}`,
      impacto: `Entregas Nov-26!E${r}`,
    }));
  });
  linhas.push([{ v: 'TOTAL', forte: true }, '', 329, 323, 8153700, 323, 0, 0]);
  return {
    arquivo: 'tv-novembro.xlsx',
    conteudo: gerarXlsx('RBS TV · Entregas Novembro 2026', [{ nome: 'Entregas Nov-26', linhas }]),
    item: {
      arquivo: 'tv-novembro.xlsx',
      destino: 'VEICULACAO',
      temDadoPessoal: false,
      linhas: gabarito,
      naoEntra: [
        itemNaoEntra('Entregas Nov-26!A1:H5', 'título, mês e cabeçalho: não são entregas'),
        itemNaoEntra('Entregas Nov-26!A15:H15', 'linha de total: não é uma entrega'),
      ],
    },
  };
}

// 2. A planilha padrão da RBS (mês, veículo, formato, impacto, uma coluna por marca).
const PADRAO = [
  { veiculo: 'Rádio 92', formato: 'Chamadas assinadas 5"', impacto: 412000, amarelo: 96, roxo: 0, verde: 0 },
  { veiculo: 'Rádio 92', formato: 'Comercial 30"', impacto: 388500, amarelo: 48, roxo: 0, verde: 0 },
  { veiculo: 'Rádio 102.3', formato: 'Chamadas assinadas 5"', impacto: 507200, amarelo: 92, roxo: 0, verde: 0 },
  { veiculo: 'Rádio 102.3', formato: 'Mídia de apoio (comerciais 30")', impacto: 431800, amarelo: 0, roxo: 74, verde: 0 },
  { veiculo: 'RBS TV', formato: 'Chamadas assinadas', impacto: 1842000, amarelo: 31, roxo: 0, verde: 0 },
  { veiculo: 'Zero Hora', formato: 'Página inteira assinada', impacto: 620000, amarelo: 2, roxo: 0, verde: 0 },
  { veiculo: 'Zero Hora', formato: 'Página dupla assinada', impacto: 1180000, amarelo: 1, roxo: 0, verde: 0 },
  { veiculo: 'Diário Gaúcho', formato: 'Página inteira assinada', impacto: 355000, amarelo: 2, roxo: 0, verde: 0 },
  { veiculo: 'Pioneiro', formato: 'Página dupla assinada', impacto: 214000, amarelo: 1, roxo: 0, verde: 0 },
  { veiculo: 'atl.la', formato: 'Matéria digital', impacto: 88000, amarelo: 0, roxo: 1, verde: 0 },
  { veiculo: 'atl.la', formato: 'Mídia display multiformatos', impacto: 740000, amarelo: 0, roxo: 96000, verde: 0 },
  { veiculo: 'atl.la', formato: "Story próprio de até 1' (ativação do cotista)", impacto: 135000, amarelo: 0, roxo: 0, verde: 1 },
  { veiculo: 'No evento', formato: 'VT telão 30"', impacto: 1560000, amarelo: 0, roxo: 0, verde: 2 },
  { veiculo: 'GaúchaZH', formato: 'Blindagem Exclusive 1 manhã', impacto: 290000, amarelo: 0, roxo: 1, verde: 0 },
];

const COLUNA_DA_MARCA = { [AMARELO]: 'E', [ROXO]: 'F', [VERDE]: 'G' };

function corpusPadrao() {
  const linhas = [['Mês', 'Veículo', 'Formato', 'Impacto', AMARELO, ROXO, VERDE].map((v) => ({ v, forte: true }))];
  const gabarito = [];
  PADRAO.forEach((item, indice) => {
    const r = 2 + indice;
    linhas.push(['2026-10', item.veiculo, item.formato, item.impacto, item.amarelo, item.roxo, item.verde]);
    for (const marca of [AMARELO, ROXO, VERDE]) {
      const chave = marca === AMARELO ? 'amarelo' : marca === ROXO ? 'roxo' : 'verde';
      if (item[chave] > 0) {
        const coluna = COLUNA_DA_MARCA[marca];
        gabarito.push(linhaVeiculacao({
          patrocinador: marca,
          veiculo: item.veiculo,
          formato: item.formato,
          mes: '2026-10',
          quantidade: item[chave],
          impacto: item.impacto,
        }, {
          patrocinador: `Outubro!${coluna}1`,
          veiculo: `Outubro!B${r}`,
          formato: `Outubro!C${r}`,
          mes: `Outubro!A${r}`,
          quantidade: `Outubro!${coluna}${r}`,
          impacto: `Outubro!D${r}`,
        }));
      }
    }
  });
  return {
    arquivo: 'padrao-rbs-outubro.xlsx',
    conteudo: gerarXlsx('Planilha padrão RBS · Outubro 2026', [{ nome: 'Outubro', linhas }]),
    item: {
      arquivo: 'padrao-rbs-outubro.xlsx',
      destino: 'VEICULACAO',
      temDadoPessoal: false,
      linhas: gabarito,
      naoEntra: [
        itemNaoEntra('Outubro!A1:G1', 'cabeçalho: não é entrega'),
        itemNaoEntra('Outubro!E2:G15 (células com 0)', 'quantidade zero não é entrega'),
      ],
    },
  };
}

// 3. O Word da Rádio 92: dia por dia, com os dois formatos que o plano do Amarelo tem para a emissora.
const DIAS_RADIO = [
  { dia: 'Segunda', data: '05/10', iso: '2026-10-05', chamadas: 3, comerciais: 2 },
  { dia: 'Terça', data: '06/10', iso: '2026-10-06', chamadas: 2, comerciais: 1 },
  { dia: 'Quarta', data: '07/10', iso: '2026-10-07', chamadas: 4, comerciais: 2 },
  { dia: 'Quinta', data: '08/10', iso: '2026-10-08', chamadas: 2, comerciais: 2 },
  { dia: 'Sexta', data: '09/10', iso: '2026-10-09', chamadas: 3, comerciais: 1 },
  { dia: 'Segunda', data: '12/10', iso: '2026-10-12', chamadas: 1, comerciais: 0 },
  { dia: 'Terça', data: '13/10', iso: '2026-10-13', chamadas: 3, comerciais: 2 },
  { dia: 'Quarta', data: '14/10', iso: '2026-10-14', chamadas: 2, comerciais: 1 },
  { dia: 'Quinta', data: '15/10', iso: '2026-10-15', chamadas: 4, comerciais: 2 },
  { dia: 'Sexta', data: '16/10', iso: '2026-10-16', chamadas: 2, comerciais: 1 },
];

function fraseDoDia(dia) {
  const partes = [];
  if (dia.chamadas > 0) {
    partes.push(`${dia.chamadas} chamada${dia.chamadas > 1 ? 's' : ''} assinada${dia.chamadas > 1 ? 's' : ''} `
        + `de 5" para ${AMARELO}`);
  }
  if (dia.comerciais > 0) {
    partes.push(`${dia.comerciais} comercial${dia.comerciais > 1 ? 'is' : ''} de 30" para ${AMARELO}`);
  }
  return `${dia.dia}, ${dia.data} · Programação · ${partes.join(' · ')}.`;
}

function corpusRadio() {
  const paragrafos = [
    { texto: 'Rádio 92 · Relatório de veiculação', estilo: 'Titulo' },
    { texto: 'Outubro de 2026' },
    ...DIAS_RADIO.map((dia) => ({ texto: fraseDoDia(dia) })),
    { texto: 'Observações: a emissora informou que o horário político alterou a grade nos dias 07 e 14; não houve '
        + 'mudança no total do mês. A programação musical seguiu o padrão.' },
  ];
  const gabarito = [];
  DIAS_RADIO.forEach((dia, indice) => {
    const paragrafo = 3 + indice;
    const origem = {
      patrocinador: `parágrafo ${paragrafo}`,
      veiculo: `parágrafo ${paragrafo}`,
      formato: `parágrafo ${paragrafo}`,
      exibido_em: `parágrafo ${paragrafo}`,
      quantidade: `parágrafo ${paragrafo}`,
    };
    if (dia.chamadas > 0) {
      gabarito.push(linhaVeiculacao({
        patrocinador: AMARELO,
        veiculo: 'Rádio 92',
        formato: 'Chamadas assinadas 5"',
        exibido_em: `${dia.iso}T00:00:00-03:00`,
        quantidade: dia.chamadas,
      }, origem));
    }
    if (dia.comerciais > 0) {
      gabarito.push(linhaVeiculacao({
        patrocinador: AMARELO,
        veiculo: 'Rádio 92',
        formato: 'Comercial 30"',
        exibido_em: `${dia.iso}T00:00:00-03:00`,
        quantidade: dia.comerciais,
      }, origem));
    }
  });
  return {
    arquivo: 'radio92-outubro.docx',
    conteudo: gerarDocx('Rádio 92 · Outubro 2026', paragrafos),
    item: {
      arquivo: 'radio92-outubro.docx',
      destino: 'VEICULACAO',
      temDadoPessoal: false,
      linhas: gabarito,
      naoEntra: [
        itemNaoEntra('parágrafo 1', 'título do relatório: não é entrega'),
        itemNaoEntra('parágrafo 2', 'mês do relatório: não é entrega'),
        itemNaoEntra('parágrafo 13', 'observações sem números de entrega'),
      ],
    },
  };
}

// 4 e 5. Prints (HTML em fonte/, capturados pelo Chromium do Playwright).
const ZH_PRINT = {
  arquivo: 'zh-pagina-dupla.png',
  fonte: 'zh-pagina-dupla.html',
  viewport: { width: 1600, height: 1120 },
  item: {
    arquivo: 'zh-pagina-dupla.png',
    destino: 'COMPROVANTE',
    temDadoPessoal: false,
    linhas: [{
      dados: {
        patrocinador: AMARELO,
        veiculo: 'Zero Hora',
        formato: 'Página dupla assinada',
        data: '2026-10-18',
      },
      origem: {
        patrocinador: 'imagem',
        veiculo: 'imagem',
        formato: 'imagem',
        data: 'imagem',
      },
    }],
    naoEntra: [
      itemNaoEntra('Manchetes e expediente do jornal', 'contexto do print; o comprovante é a página dupla do Cliente Amarelo'),
    ],
  },
};

const POST_PRINT = {
  arquivo: 'post-instagram.png',
  fonte: 'post-instagram.html',
  viewport: { width: 390, height: 844 },
  item: {
    arquivo: 'post-instagram.png',
    destino: 'COMPROVANTE',
    temDadoPessoal: false,
    linhas: [{
      dados: {
        patrocinador: ROXO,
        veiculo: 'atl.la',
        formato: 'Post feed serviço e valorização do cotista (durante)',
        data: '2026-10-21',
      },
      origem: {
        patrocinador: 'imagem',
        veiculo: 'imagem',
        formato: 'imagem',
        data: 'imagem',
      },
    }],
    naoEntra: [
      itemNaoEntra('Curtidas e alcance', 'evidência do print; o comprovante é o post do Cliente Roxo'),
    ],
  },
};

// 6. A planilha da empresa de pesquisa: perfil nas duas abas e, na aba da Master, as 3 perguntas e o texto livre.
const PERFIL_AMARELO = [
  ['GENERO', 'Feminino', 58],
  ['GENERO', 'Masculino', 40],
  ['GENERO', 'Outro', 2],
  ['FAIXA_ETARIA', '16 a 24 anos', 31],
  ['FAIXA_ETARIA', '25 a 34 anos', 36],
  ['FAIXA_ETARIA', '35 a 44 anos', 21],
  ['FAIXA_ETARIA', '45 a 59 anos', 10],
  ['FAIXA_ETARIA', '60 anos ou mais', 2],
  ['RENDA', 'Até 2 salários mínimos', 14],
  ['RENDA', '2 a 5 salários mínimos', 33],
  ['RENDA', '5 a 10 salários mínimos', 34],
  ['RENDA', 'Mais de 10 salários mínimos', 19],
  ['DOMICILIO', 'Rio Grande do Sul', 91],
  ['DOMICILIO', 'Outros estados', 9],
];

const PERFIL_GERAL = [
  ['GENERO', 'Feminino', 62],
  ['GENERO', 'Masculino', 36],
  ['GENERO', 'Outro', 2],
  ['FAIXA_ETARIA', '16 a 24 anos', 28],
  ['FAIXA_ETARIA', '25 a 34 anos', 34],
  ['FAIXA_ETARIA', '35 a 44 anos', 22],
  ['FAIXA_ETARIA', '45 a 59 anos', 13],
  ['FAIXA_ETARIA', '60 anos ou mais', 3],
  ['RENDA', 'Até 2 salários mínimos', 18],
  ['RENDA', '2 a 5 salários mínimos', 37],
  ['RENDA', '5 a 10 salários mínimos', 29],
  ['RENDA', 'Mais de 10 salários mínimos', 16],
  ['DOMICILIO', 'Rio Grande do Sul', 96],
  ['DOMICILIO', 'Outros estados', 4],
];

const TEXTO_DAS_PERGUNTAS = {
  1: 'Você visitou o espaço da marca na Arena ou no Camarote?',
  2: 'De 0 a 10, quanto você recomendaria a marca a um amigo?',
  3: 'Depois da ativação, sua percepção sobre a marca mudou?',
  4: 'O que você mais gostou na ativação?',
};

const RESPOSTAS = [
  { pergunta: 1, alternativa: 'Sim', valor: 64, unidade: 'PERCENTUAL' },
  { pergunta: 1, alternativa: 'Não', valor: 36, unidade: 'PERCENTUAL' },
  { pergunta: 2, alternativa: 'Nota média', valor: 8.4, unidade: 'NOTA' },
  { pergunta: 2, alternativa: 'Notas 9 e 10', valor: 61, unidade: 'PERCENTUAL' },
  { pergunta: 2, alternativa: 'Notas 7 e 8', valor: 28, unidade: 'PERCENTUAL' },
  { pergunta: 2, alternativa: 'Notas de 0 a 6', valor: 11, unidade: 'PERCENTUAL' },
  { pergunta: 3, alternativa: 'Melhorou', valor: 58, unidade: 'PERCENTUAL' },
  { pergunta: 3, alternativa: 'Não mudou', valor: 39, unidade: 'PERCENTUAL' },
  { pergunta: 3, alternativa: 'Piorou', valor: 3, unidade: 'PERCENTUAL' },
];

const RESPOSTAS_LIVRES = [
  'A ativação no Camarote estava muito bem organizada.',
  'Gostei da experiência com realidade aumentada.',
  'O brinde é útil e a equipe foi atenciosa.',
  'A fila para entrar na Arena estava grande.',
  'A música e o espaço da marca combinaram bem.',
];

function cabecalho(valores) {
  return valores.map((v) => ({ v, forte: true }));
}

function corpusPesquisa() {
  const baseAmarelo = 412;
  const baseGeral = 1038;

  const linhasAmarelo = [
    [{ v: AMARELO, forte: true }, 'Pesquisa de ativação'],
    ['Base de respondentes', baseAmarelo],
    ['Perfil do público'],
    cabecalho(['Dimensão', 'Categoria', 'Percentual', 'Base']),
  ];
  const gabaritoAmarelo = [];
  PERFIL_AMARELO.forEach(([dimensao, categoria, valor], indice) => {
    const r = 5 + indice;
    linhasAmarelo.push([dimensao, categoria, valor, baseAmarelo]);
    gabaritoAmarelo.push({
      dados: { tipo: 'PERFIL', patrocinador: AMARELO, dimensao, categoria, valor, base_respondentes: baseAmarelo },
      origem: {
        patrocinador: 'Cliente Amarelo!A1',
        dimensao: `Cliente Amarelo!A${r}`,
        categoria: `Cliente Amarelo!B${r}`,
        valor: `Cliente Amarelo!C${r}`,
        base_respondentes: `Cliente Amarelo!D${r}`,
      },
    });
  });
  linhasAmarelo.push([]);
  linhasAmarelo.push(['Respostas por pergunta']);
  linhasAmarelo.push(cabecalho(['Pergunta', 'Texto da pergunta', 'Alternativa', 'Valor', 'Unidade', 'Base']));
  RESPOSTAS.forEach((resposta, indice) => {
    const r = 22 + indice;
    linhasAmarelo.push([
      resposta.pergunta,
      TEXTO_DAS_PERGUNTAS[resposta.pergunta],
      resposta.alternativa,
      resposta.valor,
      resposta.unidade,
      baseAmarelo,
    ]);
    gabaritoAmarelo.push({
      dados: {
        tipo: 'RESPOSTA',
        patrocinador: AMARELO,
        pergunta: resposta.pergunta,
        alternativa: resposta.alternativa,
        valor: resposta.valor,
        unidade: resposta.unidade,
        base_respondentes: baseAmarelo,
      },
      origem: {
        patrocinador: 'Cliente Amarelo!A1',
        pergunta: `Cliente Amarelo!A${r}`,
        alternativa: `Cliente Amarelo!C${r}`,
        valor: `Cliente Amarelo!D${r}`,
        unidade: `Cliente Amarelo!E${r}`,
        base_respondentes: `Cliente Amarelo!F${r}`,
      },
    });
  });
  linhasAmarelo.push([]);
  linhasAmarelo.push(['Respostas de texto livre']);
  linhasAmarelo.push(cabecalho(['Pergunta', 'Resposta']));
  RESPOSTAS_LIVRES.forEach((texto, indice) => {
    const r = 34 + indice;
    linhasAmarelo.push([4, texto]);
    gabaritoAmarelo.push({
      dados: { tipo: 'RESPOSTA', patrocinador: AMARELO, pergunta: 4, texto, base_respondentes: baseAmarelo },
      origem: {
        patrocinador: 'Cliente Amarelo!A1',
        pergunta: `Cliente Amarelo!A${r}`,
        texto: `Cliente Amarelo!B${r}`,
        base_respondentes: 'Cliente Amarelo!B2',
      },
    });
  });

  const linhasGeral = [
    [{ v: 'Geral', forte: true }, 'Pesquisa com o planetário 2026'],
    ['Base de respondentes', baseGeral],
    ['Perfil do público'],
    cabecalho(['Dimensão', 'Categoria', 'Percentual', 'Base']),
  ];
  const gabaritoGeral = [];
  PERFIL_GERAL.forEach(([dimensao, categoria, valor], indice) => {
    const r = 5 + indice;
    linhasGeral.push([dimensao, categoria, valor, baseGeral]);
    gabaritoGeral.push({
      dados: { tipo: 'PERFIL', patrocinador: null, dimensao, categoria, valor, base_respondentes: baseGeral },
      origem: {
        patrocinador: 'Geral!A1',
        dimensao: `Geral!A${r}`,
        categoria: `Geral!B${r}`,
        valor: `Geral!C${r}`,
        base_respondentes: `Geral!D${r}`,
      },
    });
  });

  return {
    arquivo: 'pesquisa-ativacao.xlsx',
    conteudo: gerarXlsx('Pesquisa de ativação 2027', [
      { nome: 'Cliente Amarelo', linhas: linhasAmarelo },
      { nome: 'Geral', linhas: linhasGeral },
    ]),
    item: {
      arquivo: 'pesquisa-ativacao.xlsx',
      destino: 'PESQUISA',
      temDadoPessoal: false,
      linhas: [...gabaritoAmarelo, ...gabaritoGeral],
      naoEntra: [
        itemNaoEntra('Cliente Amarelo!A2:B2', 'base de respondentes: ela vai em cada linha, não é uma linha'),
        itemNaoEntra('Geral!A2:B2', 'base de respondentes: ela vai em cada linha, não é uma linha'),
        itemNaoEntra('Geral', 'a pesquisa geral tem perfil; as 3 perguntas e o texto livre são da ativação da Master'),
      ],
    },
  };
}

// 7. Os números gerais da edição (PDF).
const NUMEROS = [
  {
    indicador: 'Público (planetários)',
    valor: 80000,
    qualificador: 'MAIS_DE',
    unidade: 'pessoas',
    fonte: 'PV_PA_26_GERAL.pdf',
    rotulo: '+80 mil planetários',
  },
  {
    indicador: 'Marcas parceiras',
    valor: 39,
    qualificador: 'EXATO',
    unidade: 'marcas',
    fonte: 'PV_PA_26_GERAL.pdf',
    rotulo: '39 marcas parceiras',
  },
  {
    indicador: 'Impactos nas redes do evento',
    valor: 19000000,
    qualificador: 'MAIS_DE',
    unidade: 'impactos',
    fonte: 'PV_PA_26_GERAL.pdf',
    rotulo: '+19 milhões de impactos nas redes',
  },
  {
    indicador: 'Pessoas nas transmissões',
    valor: 1600000,
    qualificador: 'EXATO',
    unidade: 'pessoas',
    fonte: 'Relatório de transmissões 2026',
    rotulo: '1,6 milhão nas transmissões',
  },
];

function corpusNumeros() {
  return {
    arquivo: 'numeros-da-edicao.pdf',
    fonte: 'numeros-da-edicao.html',
    item: {
      arquivo: 'numeros-da-edicao.pdf',
      destino: 'NUMEROS_EDICAO',
      temDadoPessoal: false,
      linhas: NUMEROS.map((numero) => ({
        dados: {
          indicador: numero.indicador,
          valor: numero.valor,
          qualificador: numero.qualificador,
          unidade: numero.unidade,
          fonte: numero.fonte,
        },
        origem: {
          indicador: 'página 1',
          valor: 'página 1',
          qualificador: 'página 1',
          unidade: 'página 1',
          fonte: 'página 1',
        },
      })),
      naoEntra: [
        itemNaoEntra('Título e expediente', 'não são números da edição'),
        itemNaoEntra('Fontes citadas no rodapé', 'a fonte vai no campo fonte de cada linha'),
      ],
    },
  };
}

// 8. A contagem da agência (campos do catálogo "ativacao.").
const RESUMO_ATIVACAO = [
  ['ativacao.visitantes_total', 8600],
  ['ativacao.cadastros_consentidos', 3180],
  ['ativacao.brindes_disponiveis', 5000],
  ['ativacao.brindes_distribuidos', 4360],
];

function corpusAgencia() {
  const linhas = [
    [{ v: 'Agencia', forte: true }, 'Contagem da agência · Cliente Amarelo · Planeta Atlântida 2027'],
    ['Resumo da ativação'],
    cabecalho(['Campo', 'Valor']),
  ];
  const gabarito = [];
  RESUMO_ATIVACAO.forEach(([chave, valor], indice) => {
    const r = 4 + indice;
    linhas.push([chave, valor]);
    gabarito.push({
      dados: { campo_chave: chave, valor },
      origem: { campo_chave: `Agencia!A${r}`, valor: `Agencia!B${r}` },
    });
  });
  linhas.push([]);
  linhas.push(['Contagem por dia']);
  linhas.push(cabecalho(['Dia', 'Visitantes', 'Cadastros', 'Brindes entregues']));
  linhas.push(['Sexta-feira, 22/01/2027', 4100, 1500, 2100]);
  linhas.push(['Sábado, 23/01/2027', 4500, 1680, 2260]);
  linhas.push([{ v: 'Total', forte: true }, 8600, 3180, 4360]);
  return {
    arquivo: 'agencia-amarelo.xlsx',
    conteudo: gerarXlsx('Contagem da agência · Cliente Amarelo', [{ nome: 'Agencia', linhas }]),
    item: {
      arquivo: 'agencia-amarelo.xlsx',
      destino: 'DADOS_ATIVACAO',
      temDadoPessoal: false,
      linhas: gabarito,
      naoEntra: [
        itemNaoEntra('Agencia!A11:D13', 'quebra por dia e total: o resumo é que traz os campos do catálogo'),
        itemNaoEntra('Agencia!A1:B3', 'título e cabeçalho: não são campos'),
      ],
    },
  };
}

// 9. A conversa exportada do WhatsApp (texto puro). O telefone e o e-mail são fictícios e o detector precisa pegá-los.
const CONVERSA = [
  '13/10/2026 14:03 - Fulana (RBS): Oi, tudo bem? Consegue me confirmar o que rodou ontem na 102.3?',
  '13/10/2026 14:04 - Atendimento Agência X: Oi, Fulana! Tudo bem. Vou conferir com a produção.',
  '13/10/2026 14:12 - Atendimento Agência X: Confirmado: saíram 4 comerciais de 30" do Cliente Roxo na Rádio 102.3 ontem, dia 12/10.',
  '13/10/2026 14:13 - Fulana (RBS): Perfeito, obrigada. E os cadastros da ativação?',
  '13/10/2026 14:20 - Atendimento Agência X: Estamos fechando a contagem. Se precisar falar com a produção, o telefone é (51) 98888-1234.',
  '13/10/2026 14:21 - Atendimento Agência X: Também pode escrever para producao@agencia-x.example.',
  '13/10/2026 14:25 - Fulana (RBS): Anotado. O e-mail é o melhor caminho?',
  '13/10/2026 14:26 - Atendimento Agência X: Sim, a caixa é lida todo dia. O telefone é só para urgência no fim de semana.',
  '13/10/2026 14:30 - Fulana (RBS): Combinado. Vou lançar a entrega na planilha padrão.',
  '13/10/2026 14:31 - Atendimento Agência X: Ótimo. Qualquer coisa estamos à disposição.',
  '13/10/2026 14:35 - Fulana (RBS): Obrigada!',
  '13/10/2026 14:36 - Atendimento Agência X: De nada, até mais.',
];

function corpusConversa() {
  return {
    arquivo: 'conversa-whatsapp.txt',
    conteudo: `${CONVERSA.join('\n')}\n`,
    item: {
      arquivo: 'conversa-whatsapp.txt',
      destino: 'SO_CONSULTA',
      temDadoPessoal: true,
      linhas: [],
      naoEntra: [
        itemNaoEntra('linha 3', 'informação de entrega: o destino é só consulta, o conteúdo fica no documento'),
        itemNaoEntra('linha 5', 'telefone fictício: dado pessoal, não vira linha'),
        itemNaoEntra('linha 6', 'e-mail fictício: dado pessoal, não vira linha'),
      ],
    },
  };
}

// ---------------------------------------------------------------------------------------------------------------
// Playwright: prints e PDF. Usa o playwright do próprio pacote; numa worktree, busca no checkout principal.
// ---------------------------------------------------------------------------------------------------------------
function carregarPlaywright() {
  const exigir = createRequire(import.meta.url);
  try {
    return exigir('playwright');
  } catch (erro) {
    const pontoGit = path.join(raiz, '.git');
    if (existsSync(pontoGit)) {
      const conteudo = readFileSync(pontoGit, 'utf8');
      const achado = conteudo.match(/^gitdir:\s*(.+)$/m);
      if (achado) {
        const principal = path.resolve(raiz, achado[1].trim(), '../../..');
        const pacote = path.join(principal, 'ferramentas/documentos-ficticios/package.json');
        if (existsSync(pacote)) {
          return createRequire(pacote)('playwright');
        }
      }
    }
    throw new Error(`playwright não encontrado (instale em ferramentas/documentos-ficticios): ${erro.message}`);
  }
}

async function capturar(navegador, pagina, definicao) {
  const paginaAberta = await navegador.newPage({
    viewport: definicao.viewport,
    deviceScaleFactor: 1,
  });
  await paginaAberta.goto(pathToFileURL(path.join(fonte, definicao.fonte)).href, { waitUntil: 'load' });
  await paginaAberta.evaluate(() => document.fonts.ready);
  await paginaAberta.screenshot({ path: path.join(saida, pagina), fullPage: false });
  await paginaAberta.close();
}

async function imprimirPdf(navegador, definicao) {
  const pagina = await navegador.newPage();
  await pagina.goto(pathToFileURL(path.join(fonte, definicao.fonte)).href, { waitUntil: 'load' });
  await pagina.evaluate(() => document.fonts.ready);
  await pagina.pdf({
    path: path.join(saida, definicao.arquivo),
    format: 'A4',
    printBackground: true,
    margin: { top: '18mm', bottom: '18mm', left: '20mm', right: '20mm' },
  });
  await pagina.close();
}

// ---------------------------------------------------------------------------------------------------------------
// Execução
// ---------------------------------------------------------------------------------------------------------------
async function gerar() {
  await mkdir(saida, { recursive: true });

  const partes = [
    corpusTv(),
    corpusPadrao(),
    corpusRadio(),
    ZH_PRINT,
    POST_PRINT,
    corpusPesquisa(),
    corpusNumeros(),
    corpusAgencia(),
    corpusConversa(),
  ];

  for (const parte of partes) {
    if (parte.conteudo !== undefined) {
      await writeFile(path.join(saida, parte.arquivo), parte.conteudo);
    }
  }

  const { chromium } = carregarPlaywright();
  const navegador = await chromium.launch();
  try {
    for (const parte of partes) {
      if (parte.fonte && parte.arquivo.endsWith('.png')) {
        await capturar(navegador, parte.arquivo, parte);
      } else if (parte.fonte && parte.arquivo.endsWith('.pdf')) {
        await imprimirPdf(navegador, parte);
      }
    }
  } finally {
    await navegador.close();
  }

  const gabarito = {
    arquivos: partes.map((parte) => parte.item),
  };
  await writeFile(path.join(saida, 'gabarito.json'), `${JSON.stringify(gabarito, null, 2)}\n`);

  for (const parte of partes) {
    console.log(`${parte.arquivo}  ${parte.item.linhas.length} linha(s)`);
  }
  console.log(`gabarito.json  ${gabarito.arquivos.length} arquivo(s)`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await gerar();
}

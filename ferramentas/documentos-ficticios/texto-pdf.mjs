// Extrai o texto de cada página de um PDF com o pdf.js (pdfjs-dist), sem PDFBox e sem programa externo.
// Devolve um array: o item i é o texto da página i + 1, com as linhas separadas por \n.
// Na linha de comando, imprime o texto de cada página: node texto-pdf.mjs arquivo.pdf
import { readFile } from 'node:fs/promises';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';

export async function textoPorPagina(arquivo) {
  const dados = new Uint8Array(await readFile(arquivo));
  const pdf = await pdfjs.getDocument({ data: dados, useSystemFonts: false, verbosity: 0 }).promise;
  const paginas = [];
  try {
    for (let n = 1; n <= pdf.numPages; n++) {
      const pagina = await pdf.getPage(n);
      const conteudo = await pagina.getTextContent();
      let texto = '';
      for (const item of conteudo.items) {
        texto += item.str;
        if (item.hasEOL) texto += '\n';
      }
      paginas.push(texto);
      pagina.cleanup();
    }
  } finally {
    await pdf.destroy();
  }
  return paginas;
}

// Espaços e quebras de linha viram um espaço só, e o texto é normalizado em NFC. O texto em si não é alterado.
export function normalizar(texto) {
  return texto.normalize('NFC').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
}

if (process.argv[1] && process.argv[1].endsWith('texto-pdf.mjs') && process.argv[2]) {
  const paginas = await textoPorPagina(process.argv[2]);
  paginas.forEach((t, i) => console.log(`===== página ${i + 1} =====\n${t}`));
}

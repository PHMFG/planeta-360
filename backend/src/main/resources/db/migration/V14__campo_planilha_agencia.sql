-- O campo de arquivo da aba Aplicações: a planilha de contagem da agência (marco M6 do plano do Atlas completo). A V1 a V13 já rodaram em
-- outros bancos e não se editam: tudo o que muda mora aqui. Só acrescenta uma linha ao catálogo de campos; nenhuma tabela, visão, função
-- ou gatilho muda (o tipo ARQUIVO e a coluna dado_informado.arquivo_id, com a chave composta que prende o arquivo à parceria, são da V7).
--
-- [DADOS] A planilha entra pela rota 66 (multipart, só .xlsx, .xls e .csv) e vira um valor vigente como os outros: quem informou, quando e
-- a origem, com o anterior guardado. Fica fora do cartão de perguntas do Atlas, fora dos dados do relatório e fora da conta das lacunas
-- obrigatórias: o relatório fica pronto com ou sem ela.
INSERT INTO campo_catalogo (chave, aba_id, ordem, rotulo, tipo, unidade, minimo, maximo, ajuda, limite_campo) VALUES
 ('ativacao.planilha_agencia', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 5, 'Planilha da agência',
  'ARQUIVO', NULL, NULL, NULL,
  'A planilha de contagem enviada pela agência, em xlsx, xls ou csv.', NULL);

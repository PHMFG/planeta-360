-- =====================================================================
-- Simulação das cargas de veiculação (relatórios de mídia pós-evento)
-- Carga 1: relatório consolidado. Carga 2: o mesmo arquivo reenviado.
-- =====================================================================
INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
VALUES ('RELATORIO_VEICULACAO','veiculacao_consolidada_PA27.xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        184220,'rbs/cargas/veiculacao_consolidada_PA27.xlsx',(SELECT id FROM usuario WHERE email='b2b@rbs.example'),'2027-02-20 10:00-03'),
       ('RELATORIO_VEICULACAO','veiculacao_consolidada_PA27 (reenvio).xlsx','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        184220,'rbs/cargas/veiculacao_consolidada_PA27_reenvio.xlsx',(SELECT id FROM usuario WHERE email='b2b@rbs.example'),'2027-02-21 09:30-03');

INSERT INTO carga (tipo, arquivo_id, executada_por, executada_em)
SELECT 'MIDIA', a.id, a.enviado_por, a.enviado_em FROM arquivo a WHERE a.categoria = 'RELATORIO_VEICULACAO' ORDER BY a.id;

-- Quanto foi exibido em cada linha do plano (simulação com algumas entregas abaixo do previsto)
CREATE TEMP TABLE alvo AS
SELECT ap.id, ap.parceria_id, p.nome_canonico, ap.veiculo, ap.formato, ap.espaco_programa, ap.unidade,
       CASE
         WHEN ap.veiculo = 'Rede Atlântida RS' AND ap.formato = 'Chamadas assinadas 5"' THEN 296
         WHEN ap.formato = 'Citações de marca 5" em textos ao vivo' THEN 8
         WHEN ap.veiculo = 'Pioneiro' AND ap.formato = 'Página dupla assinada' THEN 1
         WHEN ap.formato = 'Comercial 30"' AND ap.espaco_programa = 'JNAC' THEN 2
         WHEN ap.veiculo = 'Rádio 102.3' AND ap.formato = 'Mídia de apoio (comerciais 30")' THEN 76
         WHEN ap.formato = 'Display multiformato' AND ap.veiculo = 'GaúchaZH' THEN 187400
         WHEN ap.espaco_programa = 'Tik Tok @redeatlântida' THEN 12
         ELSE ap.quantidade_prevista
       END AS exibir
FROM aproveitamento_previsto ap
JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
WHERE ap.categoria = 'MIDIA';

-- Inserções: uma linha por exibição; CPM: 10 totais diários
INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
SELECT c1, row_number() OVER (ORDER BY a.id, g), a.nome_canonico, a.veiculo, a.formato, a.espaco_programa,
       timestamptz '2026-11-02 06:00-03' + g * interval '97 minutes' + a.id * interval '1 minute',
       CASE WHEN a.unidade = 'CPM' THEN a.exibir / 10 ELSE 1 END
FROM alvo a
CROSS JOIN LATERAL generate_series(1, CASE WHEN a.unidade = 'CPM' THEN 10 ELSE a.exibir::int END) AS g
CROSS JOIN (SELECT min(id) AS c1 FROM carga) c;

-- Problemas reais de arquivo, reproduzidos na amostra:
-- (a) a emissora usa o perfil correto "@rede_atlantida"; o plano Subcota G traz "@prede_atlantida"
UPDATE stg_veiculacao SET espaco_programa = 'IG @rede_atlantida'
WHERE espaco_programa = 'IG @prede_atlantida';
-- (b) grafia alternativa do patrocinador (resolvida pela tabela de aliases)
UPDATE stg_veiculacao SET patrocinador = 'Amarelo'
WHERE patrocinador = 'Cliente Amarelo' AND veiculo = 'Rádio 92';
-- (c) linha sem data, (d) marca sem parceria na edição, (e) linhas repetidas dentro do próprio arquivo
INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
SELECT (SELECT min(id) FROM carga), m + 1, 'Cliente Verde', 'atl.la', 'Story próprio de até 1'' (ativação do cotista)', 'IG do evento', NULL, 1
FROM (SELECT max(linha) m FROM stg_veiculacao) x
UNION ALL
SELECT (SELECT min(id) FROM carga), m + 2, 'Cliente Azul', 'Rede Atlântida RS', 'Chamadas assinadas 5"', 'Indeterminado', timestamptz '2026-12-01 10:00-03', 1
FROM (SELECT max(linha) m FROM stg_veiculacao) x;

INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
SELECT carga_id, linha + 100000, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade
FROM stg_veiculacao WHERE patrocinador = 'Cliente Verde' AND formato = 'VT telão 30"';

SELECT 'carga 1' AS etapa, * FROM fn_carregar_veiculacoes((SELECT min(id) FROM carga), (SELECT id FROM evento WHERE edicao = 2027));

-- Carga 2: o mesmo relatório reenviado
INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
SELECT (SELECT max(id) FROM carga), linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade
FROM stg_veiculacao WHERE carga_id = (SELECT min(id) FROM carga);

SELECT 'carga 2' AS etapa, * FROM fn_carregar_veiculacoes((SELECT max(id) FROM carga), (SELECT id FROM evento WHERE edicao = 2027));

-- Relatórios V1 das três parcerias 2027
SELECT p.nome_canonico, fn_gerar_relatorio(pa.id, (SELECT id FROM usuario WHERE email='b2b@rbs.example')) AS relatorio_id
FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id
WHERE e.edicao = 2027 ORDER BY pa.id;

-- Data simulada da geração (mesmo cenário pós-evento das cargas)
UPDATE relatorio SET gerado_em = '2027-02-21 11:00-03';
UPDATE historico SET ocorrido_em = '2027-02-21 11:00-03' WHERE acao = 'GERACAO_RELATORIO';

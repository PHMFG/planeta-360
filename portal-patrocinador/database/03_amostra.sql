-- =====================================================================
-- Amostra organizada (dados SIMULADOS sobre os planos comerciais reais 2027)
-- Planos: Cliente Amarelo (Master), Cliente Roxo (Subcota G), Cliente Verde (Subcota)
-- Dados reais usados: catálogos dos planos 2027 e números da edição 2026 (PV_PA_26_GERAL)
-- Dados simulados: usuários, entregas, veiculações, comprovações, destinações e pesquisa 2027
-- =====================================================================

-- Domínios fixos -------------------------------------------------------
INSERT INTO cota_tipo VALUES (1,'MASTER','Master',3),(2,'SUBCOTA_G','Subcota G',2),(3,'SUBCOTA','Subcota',1);

INSERT INTO aba VALUES
 (1,'PERFIL','Perfil do cliente',1,'ESSENCIAL'),
 (2,'APLICACOES','Aplicações de marca no evento',2,'DESEJAVEL'),
 (3,'MIDIA','Mídia e conteúdo',3,'ESSENCIAL'),
 (4,'CORTESIAS','Cortesias',4,'DESEJAVEL'),
 (5,'CREDENCIAMENTO','Credenciamento',5,'DESEJAVEL'),
 (6,'PESQUISA','Pesquisa da ativação',6,'ESSENCIAL'),
 (7,'CONTRATO','Contrato',7,'DESEJAVEL');

-- Eventos e números reais da edição 2026 ------------------------------
INSERT INTO evento (nome, edicao, data_inicio, data_fim, local) VALUES
 ('Planeta Atlântida', 2026, '2026-01-30', '2026-01-31', 'Saba, Atlântida (RS)'),
 ('Planeta Atlântida', 2027, NULL, NULL, 'Saba, Atlântida (RS)');   -- datas 2027 a confirmar

INSERT INTO evento_resultado (evento_id, indicador, valor, qualificador, unidade, fonte)
SELECT e.id, v.indicador, v.valor, v.q, v.u, 'PV_PA_26_GERAL.pdf'
FROM evento e, (VALUES
 ('Público (planetários)', 80000, 'MAIS_DE', 'pessoas'),
 ('Marcas parceiras', 39, 'EXATO', 'marcas'),
 ('Horas de música', 80, 'MAIS_DE', 'horas'),
 ('Atrações nos dois dias', 42, 'MAIS_DE', 'atrações'),
 ('Impactos nas redes do evento', 19000000, 'MAIS_DE', 'impactos'),
 ('Pessoas que assistiram ao vivo', 3500000, 'MAIS_DE', 'pessoas')
) AS v(indicador, valor, q, u)
WHERE e.edicao = 2026;

INSERT INTO pesquisa (evento_id, nome, escopo, metodologia, base_respondentes, nivel_confianca)
SELECT id, 'Pesquisa com o planetário 2026', 'GERAL', 'Pesquisa quantitativa online', 1038, 99
FROM evento WHERE edicao = 2026;

INSERT INTO pesquisa_resultado (pesquisa_id, indicador, alternativa, valor, unidade, base_respondentes)
SELECT p.id, v.ind, v.alt, v.val, 'PERCENTUAL', 1038
FROM pesquisa p, (VALUES
 ('Presença das marcas no festival', 'Importante ou muito importante', 86),
 ('Diversão nas ativações dos patrocinadores', 'Afirmaram que se divertiram', 71),
 ('Gênero', 'Feminino', 62),
 ('Domicílio', 'Rio Grande do Sul', 96),
 ('Recorrência', 'Já haviam ido ao Planeta', 56)
) AS v(ind, alt, val)
WHERE p.nome = 'Pesquisa com o planetário 2026';

-- Patrocinadores e parcerias (clientes fictícios dos planos 2027) -----
INSERT INTO patrocinador (nome_canonico, segmento) VALUES
 ('Cliente Amarelo', NULL), ('Cliente Roxo', NULL), ('Cliente Verde', NULL);
INSERT INTO patrocinador_alias (patrocinador_id, alias)
SELECT id, 'Amarelo' FROM patrocinador WHERE nome_canonico = 'Cliente Amarelo'
UNION ALL SELECT id, 'Cliente Amarelo S.A.' FROM patrocinador WHERE nome_canonico = 'Cliente Amarelo';

INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, nome_produto, valor_investimento, base_valor, situacao)
SELECT e.id, p.id, v.cota, v.produto, v.valor, v.base, v.sit
FROM (VALUES
 (2027, 'Cliente Amarelo', 1, 'Cota Patrocínio Master', 1000000, 'BRUTO_TABELA', 'ATIVA'),
 (2027, 'Cliente Roxo',    2, 'Cota Apresenta (transmissão oficial)', 500000, 'LIQUIDO', 'ATIVA'),
 (2027, 'Cliente Verde',   3, 'Cota Ativação Arena', 100000, 'LIQUIDO', 'ATIVA'),
 (2026, 'Cliente Amarelo', 1, 'Cota Patrocínio Master', NULL, NULL, 'ENCERRADA')   -- mesma marca, edição anterior
) AS v(ed, pat, cota, produto, valor, base, sit)
JOIN evento e ON e.edicao = v.ed JOIN patrocinador p ON p.nome_canonico = v.pat;

-- Usuários e acessos ----------------------------------------------------
INSERT INTO usuario (nome, email, senha_hash, perfil) VALUES
 ('Equipe B2B RBS',        'b2b@rbs.example',              '$2a$10$hash.simulado.b2b',      'ADMIN_RBS'),
 ('Equipe de Pesquisa RBS','pesquisa@rbs.example',         '$2a$10$hash.simulado.pesq',     'ADMIN_RBS'),
 ('Marketing Amarelo',     'marketing@amarelo.example',    '$2a$10$hash.simulado.am1',      'ADMIN_PATROCINADOR'),
 ('Agência de ativação',   'atendimento@agencia-x.example','$2a$10$hash.simulado.am2',      'ADMIN_PATROCINADOR'),
 ('Marketing Roxo',        'marketing@roxo.example',       '$2a$10$hash.simulado.rx',       'ADMIN_PATROCINADOR'),
 ('Marketing Verde',       'marketing@verde.example',      '$2a$10$hash.simulado.vd',       'ADMIN_PATROCINADOR');

CREATE TEMP VIEW v_parc AS
SELECT pa.id, p.nome_canonico AS cliente FROM parceria pa
JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id WHERE e.edicao = 2027;

INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
SELECT vp.id, v.email, v.vinc, (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
FROM (VALUES ('Cliente Amarelo','marketing@amarelo.example','MARCA'),
             ('Cliente Amarelo','atendimento@agencia-x.example','AGENCIA'),
             ('Cliente Roxo','marketing@roxo.example','MARCA'),
             ('Cliente Verde','marketing@verde.example','MARCA')) AS v(cli, email, vinc)
JOIN v_parc vp ON vp.cliente = v.cli;

INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
SELECT ea.parceria_id, u.id, ea.vinculo FROM email_autorizado ea JOIN usuario u ON lower(u.email) = lower(ea.email);

-- Abas: o menu mostra as 7; Pesquisa só é liberada para a cota Master
INSERT INTO parceria_aba (parceria_id, aba_id, visivel, liberada)
SELECT vp.id, a.id, TRUE, NOT (a.codigo = 'PESQUISA' AND vp.cliente <> 'Cliente Amarelo')
FROM v_parc vp CROSS JOIN aba a;

-- Entregas (cenário simulado pós-evento) --------------------------------
INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo)
SELECT vp.id, (SELECT id FROM aba WHERE codigo = v.aba), v.titulo, v.status, v.prazo::date
FROM (VALUES
 ('Cliente Amarelo','PERFIL','Documento para compra de ingressos com valor de patrocinador','APROVADO',NULL),
 ('Cliente Amarelo','APLICACOES','Materiais de marca para a campanha de comunicação','APROVADO','2026-10-01'),
 ('Cliente Amarelo','APLICACOES','Logo final para aplicações no evento','APROVADO','2026-10-20'),
 ('Cliente Amarelo','APLICACOES','Projeto de ativação da marca (Arena e Camarote)','APROVADO','2026-11-30'),
 ('Cliente Amarelo','MIDIA','Briefing completo para ações de conteúdo','APROVADO',NULL),
 ('Cliente Amarelo','MIDIA','Peça de 30" para mídia própria','EM_ANALISE',NULL),
 ('Cliente Amarelo','CORTESIAS','Destinação das cortesias','APROVADO',NULL),
 ('Cliente Amarelo','CREDENCIAMENTO','Cadastro dos profissionais no Open Staff','APROVADO',NULL),
 ('Cliente Amarelo','PESQUISA','Três perguntas personalizadas','APROVADO',NULL),
 ('Cliente Amarelo','CONTRATO','Dados do signatário','APROVADO',NULL),
 ('Cliente Amarelo','CONTRATO','Contrato final assinado','APROVADO',NULL),
 ('Cliente Roxo','PERFIL','Documento para compra de ingressos com valor de patrocinador','APROVADO',NULL),
 ('Cliente Roxo','APLICACOES','Logo para vinhetas e assinatura da transmissão','APROVADO',NULL),
 ('Cliente Roxo','MIDIA','Textos das citações de 5"','APROVADO',NULL),
 ('Cliente Roxo','MIDIA','VT para os telões dos palcos','AJUSTE_SOLICITADO',NULL),
 ('Cliente Roxo','CORTESIAS','Destinação das cortesias','APROVADO',NULL),
 ('Cliente Roxo','CREDENCIAMENTO','Cadastro dos profissionais no Open Staff','APROVADO',NULL),
 ('Cliente Roxo','PESQUISA','Três perguntas personalizadas','NAO_APLICAVEL',NULL),
 ('Cliente Roxo','CONTRATO','Dados do signatário','APROVADO',NULL),
 ('Cliente Verde','PERFIL','Documento para compra de ingressos com valor de patrocinador','APROVADO',NULL),
 ('Cliente Verde','APLICACOES','Projeto de ativação na Arena','APROVADO','2026-12-16'),
 ('Cliente Verde','APLICACOES','Fotos de comprovação da ativação','APROVADO',NULL),
 ('Cliente Verde','MIDIA','Logo para conteúdos assinados no Instagram','APROVADO',NULL),
 ('Cliente Verde','MIDIA','VT de 30" para o telão','APROVADO',NULL),
 ('Cliente Verde','CORTESIAS','Destinação das cortesias','ENVIADO',NULL),
 ('Cliente Verde','CREDENCIAMENTO','Cadastro dos profissionais no Open Staff','APROVADO',NULL),
 ('Cliente Verde','PESQUISA','Três perguntas personalizadas','NAO_APLICAVEL',NULL),
 ('Cliente Verde','CONTRATO','Dados do signatário','APROVADO',NULL)
) AS v(cli, aba, titulo, status, prazo)
JOIN v_parc vp ON vp.cliente = v.cli;

-- Arquivo com versões e histórico (exemplo do documento do grupo) ------
INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por, enviado_em)
SELECT e.parceria_id, e.aba_id, e.id, 'MATERIAL_MARCA', 'logo_final.pdf', 'application/pdf', v.tam,
       'parcerias/' || e.parceria_id || '/aplicacoes/logo_final_v' || v.ver || '.pdf', v.ver,
       (SELECT id FROM usuario WHERE email = 'marketing@amarelo.example'), v.quando::timestamptz
FROM entrega e, (VALUES (1, 482113, '2026-10-15 14:20-03'), (2, 501877, '2026-10-17 18:03-03')) AS v(ver, tam, quando)
WHERE e.titulo = 'Logo final para aplicações no evento';

INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, usuario_id, ocorrido_em, ip)
SELECT e.parceria_id, 'entrega', e.id, v.acao, v.ant, v.novo, u.id, v.quando::timestamptz, v.ip::inet
FROM entrega e,
 (VALUES ('ENVIO_ARQUIVO', NULL, 'logo_final.pdf (versão 1)', 'marketing@amarelo.example', '2026-10-15 14:20-03', '200.0.2.10'),
         ('ALTERACAO_STATUS', 'EM_ANALISE', 'AJUSTE_SOLICITADO', 'b2b@rbs.example', '2026-10-16 09:42-03', '10.0.0.5'),
         ('NOVA_VERSAO', 'versão 1', 'logo_final.pdf (versão 2)', 'marketing@amarelo.example', '2026-10-17 18:03-03', '200.0.2.10'),
         ('ALTERACAO_STATUS', 'AJUSTE_SOLICITADO', 'APROVADO', 'b2b@rbs.example', '2026-10-18 10:15-03', '10.0.0.5')
 ) AS v(acao, ant, novo, email, quando, ip)
JOIN usuario u ON u.email = v.email
WHERE e.titulo = 'Logo final para aplicações no evento';

-- Catálogo previsto: Cliente Roxo (Subcota G), tabela completa do plano --
INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina)
SELECT vp.id, 'MIDIA', v.bloco, v.veic, v.fmt, v.esp, v.jan, v.tipo, v.qtd, v.un, v.pag
FROM v_parc vp, (VALUES
 ('Programa de temporada','Rede Atlântida RS','Citação 5"','Programa Planeta na ATL','novembro a janeiro','ASSINADA',12,'INSERCAO',3),
 ('Programa de temporada','Rede Atlântida RS','Merchandising 60"','Programa Planeta na ATL','novembro a janeiro','PROPRIA',1,'INSERCAO',3),
 ('Programa de temporada','Rede Atlântida RS','Comercial 30" determinado','Programa Planeta na ATL','novembro a janeiro','PROPRIA',12,'INSERCAO',3),
 ('Programa de temporada','Rede Atlântida RS','Comercial 30" apoio','Indeterminado','novembro a janeiro','PROPRIA',42,'INSERCAO',3),
 ('Transmissão oficial','Rede Atlântida RS','Citação 5"','Transmissão na Rádio Atlântida','Durante a transmissão','ASSINADA',8,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Citação 5"','Transmissão YouTube','Durante a transmissão','ASSINADA',8,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Vinheta de abertura e encerramento em Live (logo)','Transmissão YouTube','Durante a transmissão','ASSINADA',4,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Logo em rodízio na live','Transmissão YouTube','Durante a transmissão','ASSINADA',2,'INSERCAO',4),
 ('Transmissão oficial','Rede Atlântida RS','Merchandising 60"','Transmissão na Rádio Atlântida','Durante a transmissão','MERCHANDISING',2,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Merchandising 60"','Transmissão YouTube','Durante a transmissão','MERCHANDISING',2,'INSERCAO',4),
 ('Transmissão oficial','atl.la','QR Code durante o merchan (Ação CTA)','Transmissão YouTube','Durante a transmissão','PROPRIA',2,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Squeeze (formato em L) (até 20s)','Transmissão YouTube','Durante a transmissão','PROPRIA',2,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Vídeo até 3 min','Youtube @atlantidaforadoar','Pós festival','ASSINADA',16,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Vídeo até 1 min','Instagram @redeatlântida','Pós festival','ASSINADA',16,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Vídeo até 1 min','Tik Tok @redeatlântida','Pós festival','ASSINADA',16,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Stories','Instagram @redeatlântida','Durante o festival','BRANDED',1,'INSERCAO',4),
 ('Transmissão oficial','atl.la','Product Placement Estúdio ATL (OPCIONAL)','Estúdio ATL','Durante o festival','PROPRIA',1,'INSERCAO',4),
 ('Transmissão oficial','No evento','VT nos telões','Palcos Planeta e Atlântida 4x por dia','janeiro','PROPRIA',1,'INSERCAO',4),
 ('Transmissão oficial','No evento','Logo na fachada do estúdio','Logo na fachada do estúdio','janeiro','ASSINADA',1,'INSERCAO',4),
 ('Mídia da cota','Rede Atlântida RS','Assinatura de 5" em chamada para transmissão oficial','Indeterminado 06h às 22h','PRÉ (2 a 3 semanas)','ASSINADA',60,'INSERCAO',5),
 ('Mídia da cota','Rede Atlântida RS','Mídia de apoio (comerciais 30")','Indeterminado 07h às 19h','PRÉ (2 a 3 semanas)','PROPRIA',40,'INSERCAO',5),
 ('Mídia da cota','Rádio 102.3','Mídia de apoio (comerciais 30")','Indeterminado 07h às 19h','PRÉ (2 a 3 semanas)','PROPRIA',80,'INSERCAO',5),
 ('Mídia da cota','Rede Atlântida RS','Merchandising 60"','Despertador','PRÉ (2 a 3 semanas)','MERCHANDISING',1,'INSERCAO',5),
 ('Mídia da cota','Atlântida POA','Texto ao vivo','Tá Vazando POA','PRÉ (2 a 3 semanas)','PROPRIA',1,'INSERCAO',5),
 ('Mídia da cota','Rede Atlântida RSC','Merchandising 60"','Pretinho Básico','PRÉ (2 a 3 semanas)','MERCHANDISING',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Mídia display multiformatos','Site Atlântida','PRÉ (2 a 3 semanas)','PROPRIA',100000,'CPM',5),
 ('Mídia da cota','atl.la','Blindagem','Site Atlântida','PRÉ (2 a 3 semanas)','PROPRIA',2,'INSERCAO',5),
 ('Mídia da cota','GaúchaZH','Blindagem Exclusive 1 manhã','GZH','PRÉ (2 a 3 semanas)','PROPRIA',1,'INSERCAO',5),
 ('Mídia da cota','GaúchaZH','Blindagem Exclusive 2 tarde','GZH','PRÉ (2 a 3 semanas)','PROPRIA',1,'INSERCAO',5),
 ('Mídia da cota','GaúchaZH','Display multiformato','GZH','PRÉ (2 a 3 semanas)','PROPRIA',200000,'CPM',5),
 ('Mídia da cota','GaúchaZH','Display impacto - parallax','GZH','PRÉ (2 a 3 semanas)','PROPRIA',80000,'CPM',5),
 ('Mídia da cota','GaúchaZH','Display impacto - parallax in text','GZH','PRÉ (2 a 3 semanas)','PROPRIA',80000,'CPM',5),
 ('Mídia da cota','GaúchaZH','Matéria digital + impressa - dias úteis','GZH + ZH','PRÉ (2 a 3 semanas)','BRANDED',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Matéria digital','Site Atlântida','PRÉ (2 a 3 semanas)','BRANDED',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Stories de até 1'' - serviço e valorização do cotista','IG @rede_atlantida','PRÉ (2 a 3 semanas)','ASSINADA',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Post feed serviço e valorização do cotista (durante)','IG @planetaatlantida','PRÉ (2 a 3 semanas)','ASSINADA',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Post feed serviço e valorização do cotista (durante)','IG @rede_atlantida','PRÉ (2 a 3 semanas)','ASSINADA',1,'INSERCAO',5),
 ('Mídia da cota','atl.la','Vídeo reels até 1'' - pós festival','IG @prede_atlantida','PRÉ (2 a 3 semanas)','ASSINADA',2,'INSERCAO',5)
) AS v(bloco, veic, fmt, esp, jan, tipo, qtd, un, pag)
WHERE vp.cliente = 'Cliente Roxo';

-- Catálogo previsto: Cliente Amarelo (Master), páginas 4 e 6 do plano ----
INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina)
SELECT vp.id, v.cat, v.bloco, v.veic, v.fmt, v.esp, NULL, v.tipo, v.qtd, v.un, v.pag
FROM v_parc vp, (VALUES
 ('MIDIA','Mídia e conteúdo','RBS TV','Chamadas assinadas','Programação',NULL::text,'ASSINADA',300,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Vinhetas de bloco','Jornal do Almoço (PTV1)',NULL,'ASSINADA',10,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Flashes ao vivo de 60"','Novela I e Novela II',NULL,'ASSINADA',4,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rede Atlântida RS','Chamadas assinadas 5"','Indeterminado',NULL,'ASSINADA',300,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rede Atlântida RS','Citações de marca 5" em textos ao vivo',NULL,NULL,'ASSINADA',10,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rede Atlântida RS','Citações de marca 5"','Programa especial do Planeta',NULL,'ASSINADA',10,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rádio 92','Chamadas assinadas 5"','Indeterminado',NULL,'ASSINADA',100,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rádio 102.3','Chamadas assinadas 5"','Indeterminado',NULL,'ASSINADA',100,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rádio Gaúcha','Chamadas assinadas 5"','Indeterminado',NULL,'ASSINADA',100,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Zero Hora','Página inteira assinada',NULL,NULL,'ASSINADA',6,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Diário Gaúcho','Página inteira assinada',NULL,NULL,'ASSINADA',6,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Pioneiro','Página inteira assinada',NULL,NULL,'ASSINADA',6,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Zero Hora','Página dupla assinada',NULL,NULL,'ASSINADA',2,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Diário Gaúcho','Página dupla assinada',NULL,NULL,'ASSINADA',2,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Pioneiro','Página dupla assinada',NULL,NULL,'ASSINADA',2,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','BPRA',NULL,'PROPRIA',4,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','PTV1',NULL,'PROPRIA',2,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','GESP',NULL,'PROPRIA',2,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','PTV2',NULL,'PROPRIA',3,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','JNAC',NULL,'PROPRIA',3,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','RBS TV','Comercial 30"','N15H',NULL,'PROPRIA',1,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rede Atlântida RS','Comercial 30"','Indeterminado',NULL,'PROPRIA',80,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rede Atlântida RS','Merchandising 60"','Programa especial do Planeta',NULL,'PROPRIA',1,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Rádio 92','Comercial 30"','Indeterminado',NULL,'PROPRIA',50,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Zero Hora','Página inteira de mídia',NULL,NULL,'PROPRIA',3,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Diário Gaúcho','Página inteira de mídia',NULL,NULL,'PROPRIA',3,'INSERCAO',6),
 ('MIDIA','Mídia e conteúdo','Pioneiro','Página inteira de mídia',NULL,NULL,'PROPRIA',3,'INSERCAO',6),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento na Arena',NULL,NULL,NULL,1,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento na principal via de entrada da Arena',NULL,NULL,NULL,6,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento de sinalização na entrada da Arena',NULL,NULL,NULL,1,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento na entrada do Camarote',NULL,NULL,NULL,1,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elementos dentro do Camarote',NULL,NULL,NULL,3,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento no acesso ao Shuttle no centro de Atlântida',NULL,NULL,NULL,4,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento dentro do Planeta Premium',NULL,NULL,NULL,1,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento dentro do Planeta Lounge',NULL,NULL,NULL,1,'APLICACAO',4),
 ('APLICACAO_MARCA','Aplicações de marca','No evento','Elemento nas camisetas da produção',NULL,NULL,NULL,1,'APLICACAO',4)
) AS v(cat, bloco, veic, fmt, esp, jan, tipo, qtd, un, pag)
WHERE vp.cliente = 'Cliente Amarelo';

-- Catálogo previsto: Cliente Verde (Subcota, "Cota Ativação Arena"), página 2 --
INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade, fonte_pagina)
SELECT vp.id, v.cat, v.bloco, v.veic, v.fmt, v.esp, v.jan, v.tipo, v.qtd, v.un, 2
FROM v_parc vp, (VALUES
 ('MIDIA','Mídia','No evento','VT telão 30"','Telões (1 por dia)','Durante o festival','PROPRIA',2,'INSERCAO'),
 ('MIDIA','Mídia','atl.la','Post feed carrossel (serviço, assinado por todos os cotistas)','IG do evento','Pré-evento','ASSINADA',1,'INSERCAO'),
 ('MIDIA','Mídia','atl.la','Vídeo reels (valorização das ativações, assinado por todos os cotistas)','IG do evento','Pós-evento','ASSINADA',1,'INSERCAO'),
 ('MIDIA','Mídia','atl.la','Story próprio de até 1'' (ativação do cotista)','IG do evento','Durante o evento','PROPRIA',1,'INSERCAO'),
 ('APLICACAO_MARCA','Ativação','No evento','Metragem para ativação no setor','Arena',NULL,NULL,1,'APLICACAO')
) AS v(cat, bloco, veic, fmt, esp, jan, tipo, qtd, un)
WHERE vp.cliente = 'Cliente Verde';

-- Cortesias e credenciais (planos 2027) --------------------------------
INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade, com_shuttle, acesso)
SELECT vp.id, v.setor, d.dia, v.qtd, v.shuttle, v.acesso
FROM v_parc vp
JOIN (VALUES ('Cliente Amarelo','ARENA',25,FALSE,'Portão 6'),
             ('Cliente Amarelo','CAMAROTE',40,TRUE,'Portão 6 ou 4; shuttle via portão 4'),
             ('Cliente Amarelo','LOUNGE',10,TRUE,'Portão 6 ou 4; shuttle via portão 4'),
             ('Cliente Roxo','ARENA',4,FALSE,'P6'),
             ('Cliente Roxo','CAMAROTE',2,TRUE,'P6 ou P4; shuttle via P4'),
             ('Cliente Verde','ARENA',4,FALSE,'P6'),
             ('Cliente Verde','CAMAROTE',2,TRUE,'P6 ou P4; shuttle via P4')) AS v(cli, setor, qtd, shuttle, acesso)
  ON v.cli = vp.cliente
CROSS JOIN (VALUES ('SEXTA'), ('SABADO')) AS d(dia);

INSERT INTO credencial_cota (parceria_id, setor, beneficiario, quantidade_por_dia)
SELECT vp.id, v.setor, v.benef, v.qtd
FROM v_parc vp JOIN (VALUES
 ('Cliente Amarelo','ARENA','MARCA',20), ('Cliente Amarelo','ARENA','AGENCIA',60),
 ('Cliente Amarelo','CAMAROTE','MARCA',10), ('Cliente Amarelo','CAMAROTE','AGENCIA',20),
 ('Cliente Amarelo','SALA_IMPRENSA','MARCA',2), ('Cliente Amarelo','SALA_CONTEUDO','EQUIPE_CONTEUDO',3),
 ('Cliente Roxo','ARENA','GERAL',5),
 ('Cliente Verde','ARENA','GERAL',10)) AS v(cli, setor, benef, qtd) ON v.cli = vp.cliente;

-- Destinações simuladas
INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
SELECT cc.id, 'Convidados da marca (lote)', v.qtd, (SELECT u.id FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
                                                     WHERE pu.parceria_id = cc.parceria_id AND pu.vinculo = 'MARCA')
FROM cortesia_cota cc JOIN v_parc vp ON vp.id = cc.parceria_id
JOIN (VALUES ('Cliente Amarelo','ARENA','SEXTA',25), ('Cliente Amarelo','ARENA','SABADO',22),
             ('Cliente Amarelo','CAMAROTE','SEXTA',40), ('Cliente Amarelo','CAMAROTE','SABADO',38),
             ('Cliente Amarelo','LOUNGE','SEXTA',10), ('Cliente Amarelo','LOUNGE','SABADO',8),
             ('Cliente Roxo','ARENA','SEXTA',4), ('Cliente Roxo','ARENA','SABADO',4),
             ('Cliente Roxo','CAMAROTE','SEXTA',2), ('Cliente Roxo','CAMAROTE','SABADO',2),
             ('Cliente Verde','ARENA','SEXTA',4), ('Cliente Verde','CAMAROTE','SEXTA',2),
             ('Cliente Verde','ARENA','SABADO',2)) AS v(cli, setor, dia, qtd)
  ON v.cli = vp.cliente AND v.setor = cc.setor AND v.dia = cc.dia;

-- Contrato
INSERT INTO contrato (parceria_id, signatario_nome, signatario_email, signatario_cargo, informado_por)
SELECT pu.parceria_id, 'Signatário ' || p.nome_canonico, u.email, 'Diretor(a) de Marketing', u.id
FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
JOIN parceria pa ON pa.id = pu.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
WHERE pu.vinculo = 'MARCA';

-- Pesquisa da ativação (somente Master) - SIMULADA ---------------------
INSERT INTO pesquisa (evento_id, nome, escopo, metodologia, base_respondentes)
SELECT id, 'Pesquisa nos espaços de ativação 2027 (simulada)', 'ATIVACAO', 'Entrevistas nos espaços da marca (Arena e Camarote)', 412
FROM evento WHERE edicao = 2027;

INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, enviada_por, enviada_em)
SELECT vp.id, (SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'), v.ordem, v.texto, 'APROVADA',
       (SELECT id FROM usuario WHERE email = 'marketing@amarelo.example'), '2026-11-20 16:00-03'
FROM v_parc vp, (VALUES
 (1, 'Você visitou o espaço da marca na Arena ou no Camarote?'),
 (2, 'De 0 a 10, quanto você recomendaria a marca a um amigo?'),
 (3, 'Depois da ativação, sua percepção sobre a marca mudou?')) AS v(ordem, texto)
WHERE vp.cliente = 'Cliente Amarelo';

INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes)
SELECT q.pesquisa_id, q.parceria_id, q.id, 'Pergunta ' || q.ordem, v.alt, v.val, v.un, 412
FROM pesquisa_pergunta q JOIN (VALUES
 (1, 'Sim', 64, 'PERCENTUAL'), (1, 'Não', 36, 'PERCENTUAL'),
 (2, 'Nota média', 8.4, 'NOTA'),
 (3, 'Melhorou', 58, 'PERCENTUAL'), (3, 'Não mudou', 39, 'PERCENTUAL'), (3, 'Piorou', 3, 'PERCENTUAL')
) AS v(ordem, alt, val, un) ON v.ordem = q.ordem;

-- Comprovações das aplicações (fotos simuladas) -------------------------
-- Regra da simulação: tudo comprovado, exceto Planeta Lounge (0 fotos) e Shuttle (3 de 4)
INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por)
SELECT ap.parceria_id, 2, 'COMPROVACAO', 'foto_' || ap.id || '_' || g || '.jpg', 'image/jpeg', 2400000 + g,
       'parcerias/' || ap.parceria_id || '/comprovacoes/foto_' || ap.id || '_' || g || '.jpg',
       (SELECT id FROM usuario WHERE email = 'b2b@rbs.example')
FROM aproveitamento_previsto ap
CROSS JOIN LATERAL generate_series(1, CASE WHEN ap.formato LIKE '%Lounge%' THEN 0
                                           WHEN ap.formato LIKE '%Shuttle%' THEN 3
                                           WHEN ap.formato LIKE 'Metragem%' THEN 2
                                           ELSE ap.quantidade_prevista::int END) AS g
WHERE ap.categoria = 'APLICACAO_MARCA';

INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, descricao, registrado_por)
SELECT ap.id, a.id, 'Foto do evento', a.enviado_por
FROM aproveitamento_previsto ap
JOIN arquivo a ON a.caminho LIKE 'parcerias/' || ap.parceria_id || '/comprovacoes/foto\_' || ap.id || '\_%';

-- Catálogo de indicadores ------------------------------------------------
INSERT INTO indicador (codigo, nome, secao, regra, unidade, fonte_tabelas, cotas, requer_previsto) VALUES
 ('ENTREGAS_CONCLUIDAS_PCT','Entregas concluídas','Cumprimento das entregas','Entregas com status Aprovado ÷ (total − Não aplicável) × 100','%','entrega',NULL,NULL),
 ('ENTREGAS_PENDENTES','Entregas pendentes','Cumprimento das entregas','Contagem de entregas fora de Aprovado e Não aplicável','entregas','entrega',NULL,NULL),
 ('MIDIA_PREVISTO','Mídia prevista','Mídia: previsto x exibido','Soma de quantidade_prevista por unidade (nunca soma inserções com CPM)','por unidade','aproveitamento_previsto',NULL,'MIDIA'),
 ('MIDIA_EXIBIDO','Mídia exibida','Mídia: previsto x exibido','Soma de veiculacao.quantidade por unidade; cada exibição conta uma vez (chave aproveitamento + data/hora)','por unidade','veiculacao',NULL,'MIDIA'),
 ('MIDIA_ENTREGA_PCT','Entrega de mídia','Mídia: previsto x exibido','Exibido ÷ previsto × 100, por unidade','%','aproveitamento_previsto, veiculacao',NULL,'MIDIA'),
 ('MIDIA_VEICULO_PCT','Entrega por veículo','Mídia: previsto x exibido','Exibido ÷ previsto × 100, por veículo e unidade','%','aproveitamento_previsto, veiculacao',NULL,'MIDIA'),
 ('APLICACOES_PREVISTAS','Aplicações de marca previstas','Ativação no evento','Soma das aplicações do plano comercial','aplicações','aproveitamento_previsto',NULL,'APLICACAO_MARCA'),
 ('APLICACOES_COMPROVADAS','Aplicações comprovadas','Ativação no evento','Por linha do plano, mínimo entre previsto e fotos enviadas; depois soma','aplicações','aproveitamento_previsto, comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('APLICACOES_COMPROVADAS_PCT','Aplicações comprovadas (%)','Ativação no evento','Aplicações comprovadas ÷ previstas × 100','%','aproveitamento_previsto, comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('COMPROVACOES_ENVIADAS','Fotos de comprovação','Ativação no evento','Contagem de arquivos de comprovação','arquivos','comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('PESQUISA_BASE','Base da pesquisa da ativação','Pesquisa da ativação','Maior base de respondentes registrada nos resultados','respondentes','pesquisa_resultado','{MASTER}',NULL),
 ('PESQUISA_RESULTADO','Resultado por pergunta','Pesquisa da ativação','Valor por alternativa, sempre com a base de respondentes','% ou nota','pesquisa_resultado, pesquisa_pergunta','{MASTER}',NULL),
 ('CORTESIAS_DISPONIVEIS','Cortesias disponíveis','Cortesias e credenciamento','Soma por setor e dia','ingressos','cortesia_cota',NULL,NULL),
 ('CORTESIAS_DESTINADAS_PCT','Cortesias destinadas','Cortesias e credenciamento','Destinadas ÷ disponíveis × 100','%','cortesia_cota, cortesia_destinacao',NULL,NULL),
 ('CREDENCIAIS_DIA','Credenciais de serviço por dia','Cortesias e credenciamento','Quantidade do plano por setor e beneficiário','credenciais','credencial_cota',NULL,NULL),
 ('FESTIVAL_NUMERO','O festival em números','O festival em números','Valor carregado pela RBS para a edição','variável','evento_resultado',NULL,NULL),
 ('FESTIVAL_PESQUISA','Pesquisa geral do festival','O festival em números','Resultado da pesquisa geral, com base de respondentes','%','pesquisa, pesquisa_resultado',NULL,NULL);

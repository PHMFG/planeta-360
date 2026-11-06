-- Amostra de desenvolvimento e teste: Amarelo (Master), Roxo (Subcota G) e Verde (Subcota) com usuários, planos, entregas, comprovações,
-- pesquisa, cortesias, as duas cargas de veiculação, uma carga de pesquisa e os 3 relatórios de 2027. Atende o item 1 da Entrega 2.

-- Fica em db/dev, uma pasta que só os perfis dev e test leem: produção nunca carrega estes dados. Os clientes são fictícios
-- (planos comerciais reais de 2027 com nomes trocados por cores); os números de 2026 vêm do PV_PA_26_GERAL; o resto é simulado.
-- É repetível (R__), e o Flyway a roda de novo sempre que o arquivo muda. Por isso:
--  * [DUPLICIDADE] Trava: se a amostra já está no banco, nada é feito. Para recarregá-la, recrie o banco de dev.
--  * [HISTORICO] Nenhum UPDATE ou DELETE em historico ou relatorio (o histórico é imutável e o relatório só avança de status): as
--    datas simuladas entram no próprio INSERT, e fn_gerar_relatorio recebe a data em p_em.
--  * [DATAS] Todo horário da amostra é contado para trás a partir do dia em que o banco é criado (pg_temp.quando), então nunca
--    fica no futuro e a ordem dos acontecimentos se mantém: parceria e acessos, entregas e aprovações, exibições de mídia,
--    cargas e, por último, os relatórios em rascunho. Nada usa uma data fixa.
--  * Cada bloco DO começa pela trava, e as tabelas temporárias _amostra_* são apagadas no fim.
-- Senha de todos os usuários: senha123 (hash BCrypt literal, sem pgcrypto; os hashes estão aqui só para dev e test).

DROP TABLE IF EXISTS pg_temp._amostra_ctl;
CREATE TEMP TABLE _amostra_ctl AS
SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(email) = 'b2b@rbs.example')
    OR EXISTS (SELECT 1 FROM patrocinador WHERE nome_canonico IN ('Cliente Amarelo', 'Cliente Roxo', 'Cliente Verde')) AS pular,
       -- Meia-noite de hoje em Brasília: o ponto de partida de todos os horários da amostra.
       (date_trunc('day', now() AT TIME ZONE 'America/Sao_Paulo') AT TIME ZONE 'America/Sao_Paulo') AS hoje;

-- pg_temp.quando(30, '14:20') = há 30 dias, às 14:20 de Brasília. Use sempre 1 dia ou mais: assim o horário é sempre passado.
CREATE OR REPLACE FUNCTION pg_temp.quando(p_dias INT, p_hora TIME DEFAULT '12:00') RETURNS TIMESTAMPTZ
LANGUAGE sql STABLE AS $$ SELECT (SELECT hoje FROM _amostra_ctl) - p_dias * interval '1 day' + p_hora $$;

-- ---------------------------------------------------------------------
-- 1. Usuários, números reais da edição 2026 e a pesquisa geral do festival
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN
        RAISE NOTICE 'Amostra já carregada: o script não alterou nada.';
        RETURN;
    END IF;

    -- [PERMISSAO] Dois Admin RBS (time B2B e time de pesquisa) e quatro Admin Patrocinador (marca Amarelo, agência do Amarelo, Roxo, Verde)
    INSERT INTO usuario (nome, email, senha_hash, perfil, criado_em) VALUES
     ('Equipe B2B RBS',         'b2b@rbs.example',               '$2a$10$0C4T.v3cVusBsIM8uDxib.R1lXGkiB0BgjEwQxAz6pQLY9Mh7mpx.', 'ADMIN_RBS',           pg_temp.quando(240, '09:00')),
     ('Equipe de Pesquisa RBS', 'pesquisa@rbs.example',          '$2a$10$6jlmhibUkxLaVG3JxRPK2OJ1mZVEqBf0RKOCyKOSp/LDeJXKkCF.K', 'ADMIN_RBS',           pg_temp.quando(240, '09:05')),
     ('Marketing Amarelo',      'marketing@amarelo.example',     '$2a$10$2AKvpS64jrj7bGghRHr7reHpsL26cNFZQRuGF1SzOfqJIAaQgDRH.', 'ADMIN_PATROCINADOR', pg_temp.quando(147, '09:00')),
     ('Agência de ativação',    'atendimento@agencia-x.example', '$2a$10$b8pl2hU9wKG4TXSy1clv7uX3h82KH/pu/mA5ciNECN6HCuTvqI9SK', 'ADMIN_PATROCINADOR', pg_temp.quando(147, '09:10')),
     ('Marketing Roxo',         'marketing@roxo.example',        '$2a$10$hWwkcoCPwGnkBQHjSUamZexA750NCiPngz6TjLfJFGCwCxiQ41uWW', 'ADMIN_PATROCINADOR', pg_temp.quando(147, '09:20')),
     ('Marketing Verde',        'marketing@verde.example',       '$2a$10$k3/UaxBHvXnCA1w24CHsh.BBi69UvS2tf7/D/yenrnBwFCRGMn4LS', 'ADMIN_PATROCINADOR', pg_temp.quando(147, '09:30'));

    -- Arquivo geral da RBS (parceria nula): é a fonte dos números de 2026 e da pesquisa geral (regra rn_pesquisa_fonte_geral)
    INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT 'PESQUISA', 'PV_PA_26_GERAL.pdf', 'application/pdf', 1893420, 'rbs/geral/PV_PA_26_GERAL.pdf', u.id, pg_temp.quando(230, '15:00')
    FROM usuario u WHERE lower(u.email) = 'pesquisa@rbs.example';

    -- Números reais do Planeta Atlântida 2026 (piso: "mais de" não vira número exato)
    INSERT INTO evento_resultado (evento_id, indicador, valor, qualificador, unidade, fonte)
    SELECT e.id, v.indicador, v.valor, v.q, v.u, 'PV_PA_26_GERAL.pdf'
    FROM evento e, (VALUES
     ('Público (planetários)',            80000,    'MAIS_DE', 'pessoas'),
     ('Marcas parceiras',                 39,       'EXATO',   'marcas'),
     ('Horas de música',                  80,       'MAIS_DE', 'horas'),
     ('Atrações nos dois dias',           42,       'MAIS_DE', 'atrações'),
     ('Impactos nas redes do evento',     19000000, 'MAIS_DE', 'impactos'),
     ('Pessoas que assistiram ao vivo',   3500000,  'MAIS_DE', 'pessoas')
    ) AS v(indicador, valor, q, u)
    WHERE e.nome = 'Planeta Atlântida' AND e.edicao = 2026;

    -- Pesquisa geral do festival (escopo GERAL): sem patrocinador e sem pergunta, sempre com a base de respondentes
    INSERT INTO pesquisa (evento_id, nome, escopo, metodologia, base_respondentes, nivel_confianca, fonte_arquivo_id)
    SELECT e.id, 'Pesquisa com o planetário 2026', 'GERAL', 'Pesquisa quantitativa online', 1038, 99,
           (SELECT a.id FROM arquivo a WHERE a.caminho = 'rbs/geral/PV_PA_26_GERAL.pdf')
    FROM evento e WHERE e.nome = 'Planeta Atlântida' AND e.edicao = 2026;

    INSERT INTO pesquisa_resultado (pesquisa_id, indicador, alternativa, valor, unidade, base_respondentes)
    SELECT p.id, v.ind, v.alt, v.val, 'PERCENTUAL', 1038
    FROM pesquisa p, (VALUES
     ('Presença das marcas no festival',          'Importante ou muito importante', 86),
     ('Diversão nas ativações dos patrocinadores', 'Afirmaram que se divertiram',    71),
     ('Gênero',                                    'Feminino',                       62),
     ('Domicílio',                                 'Rio Grande do Sul',              96),
     ('Recorrência',                               'Já haviam ido ao Planeta',       56)
    ) AS v(ind, alt, val)
    WHERE p.nome = 'Pesquisa com o planetário 2026';
END $$;

-- ---------------------------------------------------------------------
-- 2. Patrocinadores, parcerias, acessos e planos comerciais (PDF)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO patrocinador (nome_canonico, segmento, criado_em) VALUES
     ('Cliente Amarelo', NULL, pg_temp.quando(400, '10:00')), ('Cliente Roxo', NULL, pg_temp.quando(160, '10:00')),
     ('Cliente Verde', NULL, pg_temp.quando(160, '10:10'));

    -- Grafias alternativas: a emissora escreve "Amarelo" no relatório de veiculação e a carga resolve pelo alias
    INSERT INTO patrocinador_alias (patrocinador_id, alias)
    SELECT id, a FROM patrocinador, (VALUES ('Amarelo'), ('Cliente Amarelo S.A.')) AS v(a)
    WHERE nome_canonico = 'Cliente Amarelo';

    -- [ISOLAMENTO] Uma parceria por marca por edição. O Amarelo também tem a de 2026 (encerrada), que fica sem usuários e sem
    -- cargas: serve para provar que o que é de uma edição não aparece na outra. O gatilho rn_parceria_abas_padrao cria as 7 abas
    -- de cada uma conforme cota_tipo_aba (V3): Pesquisa só para a Master.
    INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, nome_produto, valor_investimento, base_valor, situacao, criado_em)
    SELECT e.id, p.id, v.cota, v.produto, v.valor, v.base, v.sit, v.criada
    FROM (VALUES
     (2027, 'Cliente Amarelo', 1, 'Cota Patrocínio Master',                   1000000, 'BRUTO_TABELA', 'ATIVA',     pg_temp.quando(150, '10:00')),
     (2027, 'Cliente Roxo',    2, 'Cota Apresenta (transmissão oficial)',      500000, 'LIQUIDO',      'ATIVA',     pg_temp.quando(150, '10:20')),
     (2027, 'Cliente Verde',   3, 'Cota Ativação Arena',                       100000, 'LIQUIDO',      'ATIVA',     pg_temp.quando(150, '10:40')),
     (2026, 'Cliente Amarelo', 1, 'Cota Patrocínio Master',                      NULL, NULL,           'ENCERRADA', pg_temp.quando(400, '10:30'))
    ) AS v(ed, pat, cota, produto, valor, base, sit, criada)
    JOIN evento e ON e.nome = 'Planeta Atlântida' AND e.edicao = v.ed
    JOIN patrocinador p ON p.nome_canonico = v.pat;

    -- Credenciamento e cortesias da edição de 2027: o Open Staff aponta para example.com, domínio reservado que abre uma página de
    -- verdade na demonstração; o texto da RBS diz "Credenciamento (dezembro)"; o prazo das cortesias é o do D03 do kit.
    UPDATE evento
       SET credenciamento_url = 'https://example.com/', credenciamento_inicio = DATE '2026-12-01',
           credenciamento_fim = DATE '2026-12-20', cortesias_prazo = DATE '2027-01-15'
     WHERE nome = 'Planeta Atlântida' AND edicao = 2027;

    -- Atalho para o resto do script: as três parcerias de 2027 pelo nome do cliente
    DROP TABLE IF EXISTS pg_temp._amostra_parc;
    CREATE TEMP TABLE _amostra_parc AS
    SELECT pa.id, p.nome_canonico AS cliente
    FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id
    WHERE e.edicao = 2027;

    -- [PERMISSAO] A RBS autoriza o e-mail; só então o usuário pode ser vinculado (gatilho rn_vinculo_exige_autorizacao).
    -- O vínculo é MARCA ou AGENCIA; a agência do Amarelo tem acesso à mesma parceria.
    INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por, autorizado_em)
    SELECT pp.id, v.email, v.vinc, (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), pg_temp.quando(148, '10:00')
    FROM (VALUES ('Cliente Amarelo', 'marketing@amarelo.example',     'MARCA'),
                 ('Cliente Amarelo', 'atendimento@agencia-x.example', 'AGENCIA'),
                 ('Cliente Roxo',    'marketing@roxo.example',        'MARCA'),
                 ('Cliente Verde',   'marketing@verde.example',       'MARCA')) AS v(cli, email, vinc)
    JOIN _amostra_parc pp ON pp.cliente = v.cli;

    INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo, criado_em)
    SELECT ea.parceria_id, u.id, ea.vinculo, ea.autorizado_em + interval '1 day'
    FROM email_autorizado ea JOIN usuario u ON lower(u.email) = lower(ea.email);

    -- [UPLOAD] Plano comercial de cada parceria (PDF), guardado na aba Perfil do cliente (todo material de parceria fica numa aba).
    -- As linhas do plano abaixo citam este arquivo como fonte; o banco confere que ele é da mesma parceria da linha
    -- (chave composta fk_aprov_fonte_parceria).
    INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT pp.id, (SELECT a.id FROM aba a WHERE a.codigo = 'PERFIL'), 'PLANO_COMERCIAL','plano_comercial_' || lower(replace(pp.cliente, ' ', '_')) || '_PA27.pdf', 'application/pdf',
           v.tam, 'parcerias/' || pp.id || '/plano/plano_comercial_PA27.pdf',
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), pg_temp.quando(149, '11:00')
    FROM (VALUES ('Cliente Amarelo', 2310441), ('Cliente Roxo', 1984127), ('Cliente Verde', 1102338)) AS v(cli, tam)
    JOIN _amostra_parc pp ON pp.cliente = v.cli;
END $$;

-- ---------------------------------------------------------------------
-- 3. Plano comercial previsto: Cliente Roxo (Subcota G), tabela completa do plano
-- ---------------------------------------------------------------------
-- O plano tem um erro de digitação real no perfil do Instagram ("@prede_atlantida"); a emissora usa "@rede_atlantida" e por isso
-- as 2 linhas de reels do relatório de veiculação serão rejeitadas na carga (comportamento esperado, não defeito).
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                                         quantidade_prevista, unidade, fonte_arquivo_id, fonte_pagina)
    SELECT pp.id, 'MIDIA', v.bloco, v.veic, v.fmt, v.esp, v.jan, v.tipo, v.qtd, v.un,
           (SELECT a.id FROM arquivo a WHERE a.parceria_id = pp.id AND a.categoria = 'PLANO_COMERCIAL'), v.pag
    FROM _amostra_parc pp, (VALUES
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
    WHERE pp.cliente = 'Cliente Roxo';
END $$;

-- ---------------------------------------------------------------------
-- 4. Plano comercial previsto: Cliente Amarelo (Master, páginas 4 e 6) e Cliente Verde (Subcota, página 2)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                                         quantidade_prevista, unidade, fonte_arquivo_id, fonte_pagina)
    SELECT pp.id, v.cat, v.bloco, v.veic, v.fmt, v.esp, v.jan, v.tipo, v.qtd, v.un,
           (SELECT a.id FROM arquivo a WHERE a.parceria_id = pp.id AND a.categoria = 'PLANO_COMERCIAL'), v.pag
    FROM _amostra_parc pp, (VALUES
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
    WHERE pp.cliente = 'Cliente Amarelo';

    INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                                         quantidade_prevista, unidade, fonte_arquivo_id, fonte_pagina)
    SELECT pp.id, v.cat, v.bloco, v.veic, v.fmt, v.esp, v.jan, v.tipo, v.qtd, v.un,
           (SELECT a.id FROM arquivo a WHERE a.parceria_id = pp.id AND a.categoria = 'PLANO_COMERCIAL'), 2
    FROM _amostra_parc pp, (VALUES
     ('MIDIA','Mídia','No evento','VT telão 30"','Telões (1 por dia)','Durante o festival','PROPRIA',2,'INSERCAO'),
     ('MIDIA','Mídia','atl.la','Post feed carrossel (serviço, assinado por todos os cotistas)','IG do evento','Pré-evento','ASSINADA',1,'INSERCAO'),
     ('MIDIA','Mídia','atl.la','Vídeo reels (valorização das ativações, assinado por todos os cotistas)','IG do evento','Pós-evento','ASSINADA',1,'INSERCAO'),
     ('MIDIA','Mídia','atl.la','Story próprio de até 1'' (ativação do cotista)','IG do evento','Durante o evento','PROPRIA',1,'INSERCAO'),
     ('APLICACAO_MARCA','Ativação','No evento','Metragem para ativação no setor','Arena',NULL,NULL,1,'APLICACAO')
    ) AS v(cat, bloco, veic, fmt, esp, jan, tipo, qtd, un)
    WHERE pp.cliente = 'Cliente Verde';
END $$;

-- ---------------------------------------------------------------------
-- 5. Entregas, os arquivos enviados, o ciclo do logo e o histórico de cada envio
-- ---------------------------------------------------------------------
-- Toda entrega Enviada, Em análise ou Aprovada tem o arquivo que a marca mandou, com autor, data e, às vezes, uma observação.
-- O histórico conta a mesma história das versões: o envio, o status que muda com ele e a decisão da RBS. Três entregas
-- seguem em aberto de propósito: o VT do Roxo (Ajuste solicitado, com o recado da RBS), a peça de 30" do Amarelo (Em
-- análise) e a destinação das cortesias da Verde (Enviado). Os dois últimos esperam a RBS na fila de materiais.
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo, criado_em, atualizado_em)
    SELECT pp.id, (SELECT a.id FROM aba a WHERE a.codigo = v.aba), v.titulo, v.status, v.prazo::date,
           pg_temp.quando(145, '10:00'), pg_temp.quando(145, '10:00')
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
    JOIN _amostra_parc pp ON pp.cliente = v.cli;

    -- [PAPEL] As entregas que já são o "espaço" de um envio da aba ganham o papel, para o primeiro envio novo não criar uma segunda:
    -- o documento de ingressos (aba Perfil) e a logo (aba Aplicações). A amostra não tem entrega de MIV, e o papel não se inventa.
    -- A logo do Verde fica na aba Mídia (conteúdos assinados no Instagram), então não é a logo da aba Aplicações.
    UPDATE entrega e SET papel = 'DOCUMENTO_INGRESSOS'
    FROM aba a
    WHERE a.id = e.aba_id AND a.codigo = 'PERFIL'
      AND e.titulo = 'Documento para compra de ingressos com valor de patrocinador'
      AND e.parceria_id IN (SELECT id FROM _amostra_parc);
    UPDATE entrega e SET papel = 'LOGO'
    FROM aba a
    WHERE a.id = e.aba_id AND a.codigo = 'APLICACOES'
      AND e.titulo IN ('Logo final para aplicações no evento', 'Logo para vinhetas e assinatura da transmissão')
      AND e.parceria_id IN (SELECT id FROM _amostra_parc);

    -- [UPLOAD] Arquivo com versões (exemplo do documento do grupo): a mesma entrega, parceria e aba nas duas versões.
    -- Cada versão guarda a observação que a marca escreveu ao enviar (aparece na lista de versões da entrega).
    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por, enviado_em, observacao)
    SELECT e.parceria_id, e.aba_id, e.id, 'MATERIAL_MARCA', 'logo_final.pdf', 'application/pdf', v.tam,
           'parcerias/' || e.parceria_id || '/aplicacoes/logo_final_v' || v.ver || '.pdf', v.ver,
           (SELECT id FROM usuario WHERE lower(email) = 'marketing@amarelo.example'), v.quando, v.obs
    FROM entrega e, (VALUES (1, 482113, pg_temp.quando(94, '14:20'), 'Primeira versão do logo, em PDF.'),
                            (2, 501877, pg_temp.quando(92, '18:03'), 'Logo em vetor, como pedido.')) AS v(ver, tam, quando, obs)
    WHERE e.titulo = 'Logo final para aplicações no evento';

    -- [HISTORICO] Ciclo do logo: envio, análise, ajuste pedido (com o recado da RBS), nova versão, aprovado. As datas entram no
    -- próprio INSERT. O pedido de ajuste sempre leva a observação (a API exige).
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    SELECT e.parceria_id, 'entrega', e.id, v.acao, v.ant, v.novo, v.detalhe, u.id, v.quando, v.ip::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
    FROM entrega e,
     (VALUES ('ENVIO_ARQUIVO',    NULL,                'logo_final.pdf (versão 1)', 'Primeira versão do logo, em PDF.',                'marketing@amarelo.example', pg_temp.quando(94, '14:20'), '200.0.2.10'),
             ('ALTERACAO_STATUS', 'AGUARDANDO_ENVIO',  'ENVIADO',                   'envio automático: logo_final.pdf (versão 1)',     'marketing@amarelo.example', pg_temp.quando(94, '14:21'), '200.0.2.10'),
             ('ALTERACAO_STATUS', 'ENVIADO',           'EM_ANALISE',                NULL,                                              'b2b@rbs.example',           pg_temp.quando(94, '16:30'), '10.0.0.5'),
             ('ALTERACAO_STATUS', 'EM_ANALISE',        'AJUSTE_SOLICITADO',         'Enviar o logo em vetor, com fundo transparente.', 'b2b@rbs.example',           pg_temp.quando(93, '09:42'), '10.0.0.5'),
             ('NOVA_VERSAO',      'versão 1',          'logo_final.pdf (versão 2)', 'Logo em vetor, como pedido.',                     'marketing@amarelo.example', pg_temp.quando(92, '18:03'), '200.0.2.10'),
             ('ALTERACAO_STATUS', 'AJUSTE_SOLICITADO', 'ENVIADO',                   'envio automático: logo_final.pdf (versão 2)',     'marketing@amarelo.example', pg_temp.quando(92, '18:04'), '200.0.2.10'),
             ('ALTERACAO_STATUS', 'ENVIADO',           'APROVADO',                  NULL,                                              'b2b@rbs.example',           pg_temp.quando(91, '10:15'), '10.0.0.5')
     ) AS v(acao, ant, novo, detalhe, email, quando, ip)
    JOIN usuario u ON lower(u.email) = v.email
    WHERE e.titulo = 'Logo final para aplicações no evento';

    UPDATE entrega SET atualizado_em = pg_temp.quando(91, '10:15') WHERE titulo = 'Logo final para aplicações no evento';

    -- [UPLOAD] [HISTORICO] Os demais arquivos que as marcas enviaram. Uma linha por entrega: o arquivo, quando e com que recado
    -- a marca enviou, e a decisão da RBS (decisao nula = ainda espera a RBS, status Enviado). O recado da RBS só vai no pedido
    -- de ajuste. O IP é o de cada marca.
    DROP TABLE IF EXISTS pg_temp._amostra_envio;
    CREATE TEMP TABLE _amostra_envio (
        cli TEXT, titulo TEXT, nome TEXT, categoria TEXT, tamanho INT, dias INT, hora TIME, obs_marca TEXT,
        decisao TEXT, dias_decisao INT, hora_decisao TIME, obs_rbs TEXT, ip TEXT);
    INSERT INTO _amostra_envio VALUES
     -- Amarelo
     ('Cliente Amarelo', 'Documento para compra de ingressos com valor de patrocinador', 'documento_ingressos_valor_patrocinador.pdf', 'DOCUMENTO_INGRESSOS',  184320, 130, '10:15', NULL, 'APROVADO', 129, '15:40', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Materiais de marca para a campanha de comunicação', 'kit_marca_campanha.zip', 'MATERIAL_MARCA', 3145728, 120, '11:00', 'Manual de marca e logos em alta resolução.', 'APROVADO', 118, '09:30', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Projeto de ativação da marca (Arena e Camarote)', 'projeto_ativacao_arena_camarote.pdf', 'MATERIAL_MARCA', 5242880, 75, '16:20', 'Projeto com as medidas da Arena e do Camarote.', 'APROVADO', 72, '11:10', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Briefing completo para ações de conteúdo', 'briefing_conteudo.docx', 'MATERIAL_MIDIA', 96256, 110, '09:50', NULL, 'APROVADO', 108, '14:00', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Peça de 30" para mídia própria', 'peca_30s_midia_propria.mp4', 'MATERIAL_MIDIA', 18874368, 3, '15:30', 'Versão final, com a locução aprovada pelo jurídico.', 'EM_ANALISE', 2, '10:00', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Destinação das cortesias', 'destinacao_cortesias.xlsx', 'OUTRO', 48230, 60, '17:05', 'Lista fechada das cortesias do Camarote e do Lounge.', 'APROVADO', 59, '09:00', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Cadastro dos profissionais no Open Staff', 'profissionais_open_staff.csv', 'OUTRO', 3120, 55, '13:25', NULL, 'APROVADO', 54, '10:30', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Três perguntas personalizadas', 'perguntas_personalizadas.docx', 'OUTRO', 41200, 46, '16:00', 'As três perguntas para a pesquisa nos espaços da marca.', 'APROVADO', 45, '10:00', NULL, '200.0.2.10'),
     ('Cliente Amarelo', 'Dados do signatário', 'ficha_do_signatario.pdf', 'OUTRO', 128000, 40, '10:20', NULL, 'APROVADO', 39, '16:45', NULL, '200.0.2.10'),
     -- Roxo
     ('Cliente Roxo', 'Documento para compra de ingressos com valor de patrocinador', 'documento_ingressos_valor_patrocinador.pdf', 'DOCUMENTO_INGRESSOS', 176640, 125, '14:30', NULL, 'APROVADO', 124, '10:05', NULL, '200.0.2.20'),
     ('Cliente Roxo', 'Logo para vinhetas e assinatura da transmissão', 'logo_vinhetas_transmissao.png', 'MATERIAL_MARCA', 212480, 100, '14:10', 'Logo com fundo transparente.', 'APROVADO', 99, '10:00', NULL, '200.0.2.20'),
     ('Cliente Roxo', 'Textos das citações de 5"', 'textos_citacoes_5s.docx', 'MATERIAL_MIDIA', 38912, 85, '11:30', NULL, 'APROVADO', 84, '09:10', NULL, '200.0.2.20'),
     ('Cliente Roxo', 'VT para os telões dos palcos', 'vt_telao_palcos.mp4', 'MATERIAL_MIDIA', 27262976, 6, '16:20', 'Primeira versão do VT, com a trilha original da campanha.', 'AJUSTE_SOLICITADO', 5, '11:15',
      'O VT tem 34 segundos e o limite é 30. A trilha também precisa de autorização de uso. Envie uma nova versão de até 30 segundos, com trilha liberada.', '200.0.2.20'),
     ('Cliente Roxo', 'Destinação das cortesias', 'destinacao_cortesias.xlsx', 'OUTRO', 41984, 50, '17:00', NULL, 'APROVADO', 49, '09:40', NULL, '200.0.2.20'),
     ('Cliente Roxo', 'Cadastro dos profissionais no Open Staff', 'profissionais_open_staff.csv', 'OUTRO', 2870, 48, '13:00', NULL, 'APROVADO', 47, '11:20', NULL, '200.0.2.20'),
     ('Cliente Roxo', 'Dados do signatário', 'ficha_do_signatario.pdf', 'OUTRO', 121344, 38, '10:40', NULL, 'APROVADO', 37, '15:10', NULL, '200.0.2.20'),
     -- Verde
     ('Cliente Verde', 'Documento para compra de ingressos com valor de patrocinador', 'documento_ingressos_valor_patrocinador.pdf', 'DOCUMENTO_INGRESSOS', 168960, 128, '09:45', NULL, 'APROVADO', 127, '14:20', NULL, '200.0.2.30'),
     ('Cliente Verde', 'Projeto de ativação na Arena', 'projeto_ativacao_arena.pdf', 'MATERIAL_MARCA', 3670016, 80, '15:50', 'Projeto da ativação, com a planta da Arena.', 'APROVADO', 78, '10:30', NULL, '200.0.2.30'),
     ('Cliente Verde', 'Fotos de comprovação da ativação', 'fotos_ativacao_arena.zip', 'OUTRO', 6291456, 28, '15:00', 'Fotos da ativação durante o evento.', 'APROVADO', 27, '10:00', NULL, '200.0.2.30'),
     ('Cliente Verde', 'Logo para conteúdos assinados no Instagram', 'logo_instagram_assinado.png', 'MATERIAL_MARCA', 187392, 70, '12:15', NULL, 'APROVADO', 69, '09:20', NULL, '200.0.2.30'),
     ('Cliente Verde', 'VT de 30" para o telão', 'vt_30s_telao.mp4', 'MATERIAL_MIDIA', 16252928, 65, '16:40', NULL, 'APROVADO', 63, '11:05', NULL, '200.0.2.30'),
     ('Cliente Verde', 'Destinação das cortesias', 'destinacao_cortesias.xlsx', 'OUTRO', 39936, 2, '09:40', 'Destinamos 6 das 12 cortesias; as demais saem na semana do evento.', NULL, NULL, NULL, NULL, '200.0.2.30'),
     ('Cliente Verde', 'Cadastro dos profissionais no Open Staff', 'profissionais_open_staff.csv', 'OUTRO', 2540, 52, '14:10', NULL, 'APROVADO', 51, '10:50', NULL, '200.0.2.30'),
     ('Cliente Verde', 'Dados do signatário', 'ficha_do_signatario.pdf', 'OUTRO', 119808, 36, '11:00', NULL, 'APROVADO', 35, '16:00', NULL, '200.0.2.30');

    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por, enviado_em, observacao)
    SELECT e.parceria_id, e.aba_id, e.id, v.categoria, v.nome,
           CASE lower(substring(v.nome FROM '[.]([^.]+)$'))
                WHEN 'pdf' THEN 'application/pdf' WHEN 'png' THEN 'image/png' WHEN 'mp4' THEN 'video/mp4'
                WHEN 'csv' THEN 'text/csv' WHEN 'zip' THEN 'application/zip'
                WHEN 'docx' THEN 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
                WHEN 'xlsx' THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' END,
           v.tamanho, 'parcerias/' || e.parceria_id || '/entregas/' || e.id || '_' || v.nome, 1,
           (SELECT pu.usuario_id FROM parceria_usuario pu WHERE pu.parceria_id = e.parceria_id AND pu.vinculo = 'MARCA'),
           pg_temp.quando(v.dias, v.hora), v.obs_marca
    FROM _amostra_envio v
    JOIN _amostra_parc pp ON pp.cliente = v.cli
    JOIN entrega e ON e.parceria_id = pp.id AND e.titulo = v.titulo;

    -- O envio e o status que muda com ele (a API grava as duas linhas; aqui a segunda vem um minuto depois, para a ordem ser certa)
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    SELECT a.parceria_id, 'entrega', a.entrega_id, x.acao, x.ant, x.novo, x.detalhe, a.enviado_por, a.enviado_em + x.depois, v.ip::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
    FROM _amostra_envio v
    JOIN _amostra_parc pp ON pp.cliente = v.cli
    JOIN entrega e ON e.parceria_id = pp.id AND e.titulo = v.titulo
    JOIN arquivo a ON a.entrega_id = e.id AND a.versao = 1
    CROSS JOIN LATERAL (VALUES
        ('ENVIO_ARQUIVO',    NULL,               a.nome_original || ' (versão 1)', a.observacao,                                        interval '0 minutes'),
        ('ALTERACAO_STATUS', 'AGUARDANDO_ENVIO', 'ENVIADO',                        'envio automático: ' || a.nome_original || ' (versão 1)', interval '1 minute')
    ) AS x(acao, ant, novo, detalhe, depois);

    -- A decisão da RBS: aprovou, está analisando ou pediu ajuste (o pedido de ajuste leva o recado)
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    SELECT e.parceria_id, 'entrega', e.id, 'ALTERACAO_STATUS', 'ENVIADO', v.decisao, v.obs_rbs,
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), pg_temp.quando(v.dias_decisao, v.hora_decisao), '10.0.0.5'::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
    FROM _amostra_envio v
    JOIN _amostra_parc pp ON pp.cliente = v.cli
    JOIN entrega e ON e.parceria_id = pp.id AND e.titulo = v.titulo
    WHERE v.decisao IS NOT NULL;

    -- A entrega foi atualizada pela última vez no último acontecimento dela (a decisão da RBS, ou o envio quando ela ainda espera)
    UPDATE entrega e SET atualizado_em = coalesce(pg_temp.quando(v.dias_decisao, v.hora_decisao), pg_temp.quando(v.dias, v.hora) + interval '1 minute')
    FROM _amostra_envio v JOIN _amostra_parc pp ON pp.cliente = v.cli
    WHERE e.parceria_id = pp.id AND e.titulo = v.titulo;

    -- [HISTORICO] [ITEM-5] Acesso, download e autorização (ações novas). A tentativa de login com e-mail errado não tem usuário:
    -- usuario_id e entidade_id ficam nulos e o e-mail tentado vai no detalhe.
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em, ip, user_agent)
    SELECT ea.parceria_id, 'email_autorizado', ea.id, 'AUTORIZACAO', ea.email || ' (' || lower(ea.vinculo) || ')', ea.autorizado_por,
           ea.autorizado_em, '10.0.0.5'::inet, 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM email_autorizado ea;

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    VALUES (NULL, 'usuario', NULL, 'LOGIN_FALHOU', 'e-mail tentado: amarelo.marketing@amarelo.example', NULL,
            pg_temp.quando(94, '14:08'), '200.0.2.10', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15');

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, usuario_id, ocorrido_em, ip, user_agent)
    SELECT pu.parceria_id, 'usuario', u.id, 'LOGIN', u.id, pg_temp.quando(94, '14:10'), '200.0.2.10'::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM usuario u JOIN parceria_usuario pu ON pu.usuario_id = u.id
    WHERE lower(u.email) = 'marketing@amarelo.example';

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em, ip, user_agent)
    SELECT a.parceria_id, 'arquivo', a.id, 'DOWNLOAD', a.nome_original || ' (versão ' || a.versao || ')', u.id,
           pg_temp.quando(91, '10:05'), '10.0.0.5'::inet, 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM arquivo a, usuario u
    WHERE a.nome_original = 'logo_final.pdf' AND a.versao = 2 AND lower(u.email) = 'b2b@rbs.example';
END $$;

-- ---------------------------------------------------------------------
-- 6. Cortesias, credenciais e contrato (planos 2027)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    -- Os números e o portão de cada cota são os do plano comercial dela (o Manual diz que o plano prevalece), os mesmos do kit de
    -- documentos do Atlas, e batem com os PDFs da RBS: Master (D03, p. 11) 25 na Arena, 40 no Camarote e 10 no Lounge; Roxo (D04,
    -- p. 8 do plano) 4 e 2; Verde (D05, p. 2) 4 e 2, tudo por dia. A Arena entra pelo Portão 6; o Camarote e o Lounge, pelo Portão 4,
    -- onde também sai o shuttle (D02).
    INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade, com_shuttle, acesso)
    SELECT pp.id, v.setor, d.dia, v.qtd, v.shuttle, v.acesso
    FROM _amostra_parc pp
    JOIN (VALUES ('Cliente Amarelo', 'ARENA',    25, FALSE, 'Portão 6'),
                 ('Cliente Amarelo', 'CAMAROTE', 40, TRUE,  'Portão 4; shuttle via portão 4'),
                 ('Cliente Amarelo', 'LOUNGE',   10, TRUE,  'Portão 4; shuttle via portão 4'),
                 ('Cliente Roxo',    'ARENA',     4, FALSE, 'P6'),
                 ('Cliente Roxo',    'CAMAROTE',  2, TRUE,  'P4; shuttle via P4'),
                 ('Cliente Verde',   'ARENA',     4, FALSE, 'P6'),
                 ('Cliente Verde',   'CAMAROTE',  2, TRUE,  'P4; shuttle via P4')) AS v(cli, setor, qtd, shuttle, acesso)
      ON v.cli = pp.cliente
    CROSS JOIN (VALUES ('SEXTA'), ('SABADO')) AS d(dia);

    INSERT INTO credencial_cota (parceria_id, setor, beneficiario, quantidade_por_dia)
    SELECT pp.id, v.setor, v.benef, v.qtd
    FROM _amostra_parc pp JOIN (VALUES
     ('Cliente Amarelo', 'ARENA',         'MARCA',           20), ('Cliente Amarelo', 'ARENA',    'AGENCIA', 60),
     ('Cliente Amarelo', 'CAMAROTE',      'MARCA',           10), ('Cliente Amarelo', 'CAMAROTE', 'AGENCIA', 20),
     ('Cliente Amarelo', 'SALA_IMPRENSA', 'MARCA',            2), ('Cliente Amarelo', 'SALA_CONTEUDO', 'EQUIPE_CONTEUDO', 3),
     ('Cliente Roxo',    'ARENA',         'GERAL',            5),
     ('Cliente Verde',   'ARENA',         'GERAL',           10)) AS v(cli, setor, benef, qtd) ON v.cli = pp.cliente;

    -- [ITEM-6] As destinações respeitam a cota de cada setor e dia (o gatilho rn_cortesia_limite confere): a Amarelo usa 25 de 25
    -- na Arena de sexta, a Verde usa 3 de 4 na de sexta e 2 de 4 na de sábado, e o Roxo usa 4 de 4 na Arena e 2 de 2 no Camarote.
    INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por, registrado_em)
    SELECT cc.id, 'Convidados da marca (lote)', v.qtd,
           (SELECT u.id FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
             WHERE pu.parceria_id = cc.parceria_id AND pu.vinculo = 'MARCA'),
           -- A lista da destinação é registrada um dia antes de a marca enviar o arquivo da entrega (Amarelo 60, Roxo 50, Verde 2 dias atrás)
           pg_temp.quando(CASE pp.cliente WHEN 'Cliente Amarelo' THEN 61 WHEN 'Cliente Roxo' THEN 51 ELSE 3 END, '16:00')
    FROM cortesia_cota cc JOIN _amostra_parc pp ON pp.id = cc.parceria_id
    JOIN (VALUES ('Cliente Amarelo', 'ARENA',    'SEXTA',  25), ('Cliente Amarelo', 'ARENA',    'SABADO', 22),
                 ('Cliente Amarelo', 'CAMAROTE', 'SEXTA',  40), ('Cliente Amarelo', 'CAMAROTE', 'SABADO', 38),
                 ('Cliente Amarelo', 'LOUNGE',   'SEXTA',  10), ('Cliente Amarelo', 'LOUNGE',   'SABADO',  8),
                 ('Cliente Roxo',    'ARENA',    'SEXTA',   4), ('Cliente Roxo',    'ARENA',    'SABADO',  4),
                 ('Cliente Roxo',    'CAMAROTE', 'SEXTA',   2), ('Cliente Roxo',    'CAMAROTE', 'SABADO',  2),
                 ('Cliente Verde',   'ARENA',    'SEXTA',   3), ('Cliente Verde',   'CAMAROTE', 'SEXTA',   1),
                 ('Cliente Verde',   'ARENA',    'SABADO',  2)) AS v(cli, setor, dia, qtd)
      ON v.cli = pp.cliente AND v.setor = cc.setor AND v.dia = cc.dia;

    -- Contrato assinado do Amarelo: arquivo da categoria CONTRATO na entrega "Contrato final assinado" (mesma parceria e aba)
    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em, observacao)
    SELECT e.parceria_id, e.aba_id, e.id, 'CONTRATO', 'contrato_assinado_cliente_amarelo_PA27.pdf', 'application/pdf', 734210,
           'parcerias/' || e.parceria_id || '/contrato/contrato_assinado_PA27.pdf',
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), pg_temp.quando(24, '16:30'),
           'Contrato assinado pelas duas partes.'
    FROM entrega e JOIN _amostra_parc pp ON pp.id = e.parceria_id
    WHERE pp.cliente = 'Cliente Amarelo' AND e.titulo = 'Contrato final assinado';

    -- [HISTORICO] O contrato assinado é um retorno da RBS: ela envia o arquivo e dá a entrega por aprovada.
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    SELECT a.parceria_id, 'entrega', a.entrega_id, v.acao, v.ant, v.novo, v.detalhe, a.enviado_por, a.enviado_em + v.depois, '10.0.0.5'::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
    FROM arquivo a
    CROSS JOIN LATERAL (VALUES
        ('ENVIO_ARQUIVO',    NULL,               a.nome_original || ' (versão 1)', 'Contrato assinado pelas duas partes.', interval '0 minutes'),
        ('ALTERACAO_STATUS', 'AGUARDANDO_ENVIO', 'APROVADO',                      NULL,                                   interval '5 minutes')
    ) AS v(acao, ant, novo, detalhe, depois)
    WHERE a.categoria = 'CONTRATO';

    UPDATE entrega SET atualizado_em = pg_temp.quando(24, '16:35')
    WHERE id = (SELECT entrega_id FROM arquivo WHERE categoria = 'CONTRATO');

    -- Um contrato (e um signatário) por parceria: a pessoa de marketing da marca informa os dados
    INSERT INTO contrato (parceria_id, signatario_nome, signatario_email, signatario_cargo, informado_por, arquivo_id, assinado_em)
    SELECT pu.parceria_id, 'Signatário ' || p.nome_canonico, u.email, 'Diretor(a) de Marketing', u.id,
           (SELECT a.id FROM arquivo a WHERE a.parceria_id = pu.parceria_id AND a.categoria = 'CONTRATO'),
           CASE WHEN p.nome_canonico = 'Cliente Amarelo'
                THEN (SELECT (hoje AT TIME ZONE 'America/Sao_Paulo')::date - 24 FROM _amostra_ctl) END
    FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
    JOIN parceria pa ON pa.id = pu.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
    WHERE pu.vinculo = 'MARCA';
END $$;

-- ---------------------------------------------------------------------
-- 7. Pesquisa da ativação 2027 (só a cota Master, simulada): pesquisa e as 3 perguntas aprovadas; os resultados chegam na carga (parte 9)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO pesquisa (evento_id, nome, escopo, metodologia, base_respondentes)
    SELECT id, 'Pesquisa nos espaços de ativação 2027', 'ATIVACAO', 'Entrevistas nos espaços da marca (Arena e Camarote)', 412
    FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;

    -- A RBS aprova as perguntas por uma rota da API, e só pergunta APROVADA recebe resultado; na amostra as 3 já chegam APROVADAS.
    INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, enviada_por, enviada_em)
    SELECT pp.id, (SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'), v.ordem, v.texto, 'APROVADA',
           (SELECT id FROM usuario WHERE lower(email) = 'marketing@amarelo.example'), pg_temp.quando(46, '16:00')
    FROM _amostra_parc pp, (VALUES
     (1, 'Você visitou o espaço da marca na Arena ou no Camarote?'),
     (2, 'De 0 a 10, quanto você recomendaria a marca a um amigo?'),
     (3, 'Depois da ativação, sua percepção sobre a marca mudou?')) AS v(ordem, texto)
    WHERE pp.cliente = 'Cliente Amarelo';
END $$;

-- ---------------------------------------------------------------------
-- 8. Fotos de comprovação das aplicações de marca (simuladas)
-- ---------------------------------------------------------------------
-- Regra da simulação: tudo comprovado, exceto "Planeta Lounge" (0 fotos) e "Shuttle" (3 de 4). A foto é enviada pela RBS (quem sobe as
-- fotos é decisão a confirmar com a RBS). A parceria da comprovação é preenchida pelo gatilho rn_comprovacao_valida.
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    DROP TABLE IF EXISTS pg_temp._amostra_foto;
    CREATE TEMP TABLE _amostra_foto AS
    SELECT ap.id AS aproveitamento_id, ap.parceria_id, g,
           'parcerias/' || ap.parceria_id || '/comprovacoes/foto_' || ap.id || '_' || g || '.jpg' AS caminho
    FROM aproveitamento_previsto ap
    CROSS JOIN LATERAL generate_series(1, CASE WHEN ap.formato LIKE '%Lounge%'   THEN 0
                                               WHEN ap.formato LIKE '%Shuttle%'  THEN 3
                                               WHEN ap.formato LIKE 'Metragem%'  THEN 2
                                               ELSE ap.quantidade_prevista::int END) AS g
    WHERE ap.categoria = 'APLICACAO_MARCA';

    INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT f.parceria_id, (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 'COMPROVACAO',
           'foto_' || f.aproveitamento_id || '_' || f.g || '.jpg', 'image/jpeg', 2400000 + f.g, f.caminho,
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), pg_temp.quando(30, '12:00')
    FROM _amostra_foto f;

    INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, descricao, registrado_por, registrado_em)
    SELECT f.aproveitamento_id, a.id, 'Foto do evento', a.enviado_por, a.enviado_em
    FROM _amostra_foto f JOIN arquivo a ON a.caminho = f.caminho;
END $$;

-- ---------------------------------------------------------------------
-- 9. Cargas de veiculação (relatórios de mídia pós-evento): preparação das linhas
-- ---------------------------------------------------------------------
-- Carga 1 é o relatório consolidado e a carga 2 é o mesmo arquivo reenviado. Aqui só entram os arquivos, as cargas e as
-- linhas de stg_veiculacao; as funções rodam no bloco seguinte.
DO $$
DECLARE
    v_c1 BIGINT;
    v_c2 BIGINT;
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT 'RELATORIO_VEICULACAO', v.nome, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 184220, v.caminho,
           u.id, v.quando
    FROM usuario u, (VALUES
     ('veiculacao_consolidada_PA27.xlsx',           'rbs/cargas/veiculacao_consolidada_PA27.xlsx',         pg_temp.quando(12, '10:00')),
     ('veiculacao_consolidada_PA27 (reenvio).xlsx', 'rbs/cargas/veiculacao_consolidada_PA27_reenvio.xlsx', pg_temp.quando(11, '09:30'))
    ) AS v(nome, caminho, quando)
    WHERE lower(u.email) = 'b2b@rbs.example';

    INSERT INTO carga (tipo, arquivo_id, executada_por, executada_em)
    SELECT 'MIDIA', a.id, a.enviado_por, a.enviado_em FROM arquivo a WHERE a.categoria = 'RELATORIO_VEICULACAO' ORDER BY a.id;

    -- Guarda os números das cargas (as funções recebem o id, que não é fixo)
    DROP TABLE IF EXISTS pg_temp._amostra_carga;
    CREATE TEMP TABLE _amostra_carga (nome TEXT PRIMARY KEY, id BIGINT NOT NULL);
    INSERT INTO _amostra_carga
    SELECT CASE WHEN a.caminho LIKE '%reenvio%' THEN 'veiculacao_2' ELSE 'veiculacao_1' END, c.id
    FROM carga c JOIN arquivo a ON a.id = c.arquivo_id WHERE c.tipo = 'MIDIA';
    SELECT id INTO v_c1 FROM _amostra_carga WHERE nome = 'veiculacao_1';
    SELECT id INTO v_c2 FROM _amostra_carga WHERE nome = 'veiculacao_2';

    -- Quanto foi exibido em cada linha do plano (simulação com algumas entregas abaixo do previsto)
    DROP TABLE IF EXISTS pg_temp._amostra_alvo;
    CREATE TEMP TABLE _amostra_alvo AS
    SELECT ap.id, ap.parceria_id, p.nome_canonico, ap.veiculo, ap.formato, ap.espaco_programa, ap.unidade,
           CASE
             WHEN ap.veiculo = 'Rede Atlântida RS' AND ap.formato = 'Chamadas assinadas 5"'                    THEN 296
             WHEN ap.formato = 'Citações de marca 5" em textos ao vivo'                                        THEN 8
             WHEN ap.veiculo = 'Pioneiro' AND ap.formato = 'Página dupla assinada'                              THEN 1
             WHEN ap.formato = 'Comercial 30"' AND ap.espaco_programa = 'JNAC'                                  THEN 2
             WHEN ap.veiculo = 'Rádio 102.3' AND ap.formato = 'Mídia de apoio (comerciais 30")'                 THEN 76
             WHEN ap.formato = 'Display multiformato' AND ap.veiculo = 'GaúchaZH'                               THEN 187400
             WHEN ap.espaco_programa = 'Tik Tok @redeatlântida'                                                 THEN 12
             ELSE ap.quantidade_prevista
           END AS exibir
    FROM aproveitamento_previsto ap
    JOIN parceria pa ON pa.id = ap.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
    WHERE ap.categoria = 'MIDIA';

    -- [MESES] As exibições do Amarelo se espalham por três faixas de 31 dias (a planilha mensal da comprovação mostra três meses);
    -- as dos outros dois seguem juntas, como antes. Tudo passado: a última faixa termina 28 dias atrás, no máximo.
    -- [LINKS] As três primeiras exibições de cada linha de Instagram ou TikTok do Roxo trazem o endereço do post (fictício, https).
    -- Inserções: uma linha por exibição; CPM: 10 totais diários. Dois problemas reais de arquivo já entram aqui:
    --  (a) a emissora usa o perfil correto "@rede_atlantida" e o plano do Roxo traz "@prede_atlantida": essas linhas
    --      não casam com o plano e serão rejeitadas;
    --  (b) grafia alternativa da marca: a Rádio 92 escreve "Amarelo", e a tabela patrocinador_alias resolve.
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link)
    SELECT v_c1, row_number() OVER (ORDER BY a.id, g),
           CASE WHEN a.nome_canonico = 'Cliente Amarelo' AND a.veiculo = 'Rádio 92' THEN 'Amarelo' ELSE a.nome_canonico END,
           a.veiculo, a.formato,
           CASE WHEN a.espaco_programa = 'IG @prede_atlantida' THEN 'IG @rede_atlantida' ELSE a.espaco_programa END,
           CASE WHEN a.nome_canonico = 'Cliente Amarelo' THEN pg_temp.quando(110, '06:00') + (g % 3) * interval '31 days'
                ELSE pg_temp.quando(60, '06:00') END
             + g * interval '97 minutes' + a.id * interval '1 minute',
           CASE WHEN a.unidade = 'CPM' THEN a.exibir / 10 ELSE 1 END,
           CASE WHEN a.nome_canonico = 'Cliente Roxo' AND g <= 3 AND a.espaco_programa ILIKE '%tik%tok%'
                THEN 'https://www.tiktok.com/@planetaatlantida/video/' || lpad((a.id * 10 + g)::text, 10, '0')
                WHEN a.nome_canonico = 'Cliente Roxo' AND g <= 3 AND (a.espaco_programa ILIKE 'IG %' OR a.espaco_programa ILIKE 'Instagram%')
                THEN 'https://www.instagram.com/p/AMOSTRA' || lpad((a.id * 10 + g)::text, 6, '0') || '/'
           END
    FROM _amostra_alvo a
    CROSS JOIN LATERAL generate_series(1, CASE WHEN a.unidade = 'CPM' THEN 10 ELSE a.exibir::int END) AS g;

    -- (c) linha sem data (Verde), (d) marca sem parceria na edição (Cliente Azul)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
    SELECT v_c1, m + 1, 'Cliente Verde', 'atl.la', 'Story próprio de até 1'' (ativação do cotista)', 'IG do evento', NULL, 1
    FROM (SELECT max(linha) AS m FROM stg_veiculacao WHERE carga_id = v_c1) x
    UNION ALL
    SELECT v_c1, m + 2, 'Cliente Azul', 'Rede Atlântida RS', 'Chamadas assinadas 5"', 'Indeterminado', pg_temp.quando(55, '10:00'), 1
    FROM (SELECT max(linha) AS m FROM stg_veiculacao WHERE carga_id = v_c1) x;

    -- (e) linhas repetidas dentro do próprio arquivo (as 2 exibições do VT de telão da Verde, com número de linha novo)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link)
    SELECT carga_id, linha + 100000, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link
    FROM stg_veiculacao WHERE carga_id = v_c1 AND patrocinador = 'Cliente Verde' AND formato = 'VT telão 30"';

    -- Carga 2: o mesmo arquivo reenviado (as mesmas 1.489 linhas, com os mesmos problemas)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link)
    SELECT v_c2, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade, link
    FROM stg_veiculacao WHERE carga_id = v_c1;
END $$;

-- ---------------------------------------------------------------------
-- 10. Processamento das cargas de veiculação, carga de pesquisa e os 3 relatórios de 2027
-- ---------------------------------------------------------------------
-- [ITEM-4] As duas cargas de veiculação e a de pesquisa rodam na mesma transação: antes da correção 4, a segunda chamada falharia.
-- Esperado: carga 1 = 1.489 lidas, 1.483 inseridas, 2 duplicadas, 4 rejeitadas; carga 2 (reenvio) = 1.489, 0, 1.485, 4.
DO $$
DECLARE
    v_ev BIGINT; v_b2b BIGINT; v_pesq BIGINT; v_cp BIGINT; v_c1 BIGINT; v_c2 BIGINT;
    r RECORD;
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    SELECT id INTO v_ev  FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;
    SELECT id INTO v_b2b FROM usuario WHERE lower(email) = 'b2b@rbs.example';
    SELECT id INTO v_c1  FROM _amostra_carga WHERE nome = 'veiculacao_1';
    SELECT id INTO v_c2  FROM _amostra_carga WHERE nome = 'veiculacao_2';

    SELECT * INTO r FROM fn_carregar_veiculacoes(v_c1, v_ev);
    RAISE NOTICE 'Carga de veiculação 1: lidas %, inseridas %, duplicadas %, rejeitadas %', r.lidas, r.inseridas, r.duplicadas, r.rejeitadas;
    SELECT * INTO r FROM fn_carregar_veiculacoes(v_c2, v_ev);
    RAISE NOTICE 'Carga de veiculação 2: lidas %, inseridas %, duplicadas %, rejeitadas %', r.lidas, r.inseridas, r.duplicadas, r.rejeitadas;

    -- [CARGA] Resultados agregados da pesquisa da ativação (formato de docs/exemplos/pesquisa.csv): enviados pela equipe de pesquisa da RBS
    -- ("quem carrega os resultados" é decisão a confirmar com a RBS). Duas linhas de propósito: uma repetida, escrita com o alias
    -- "Amarelo" (duplicada), e uma com percentual de 140 (rejeitada).
    INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT 'PESQUISA', 'pesquisa.csv', 'text/csv', 568,'rbs/cargas/pesquisa_ativacao_2027.csv', u.id, pg_temp.quando(11, '10:30')
    FROM usuario u WHERE lower(u.email) = 'pesquisa@rbs.example';

    INSERT INTO carga (tipo, arquivo_id, executada_por, executada_em)
    SELECT 'PESQUISA', a.id, a.enviado_por, a.enviado_em FROM arquivo a WHERE a.caminho = 'rbs/cargas/pesquisa_ativacao_2027.csv'
    RETURNING id INTO v_cp;

    INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes)
    SELECT v_cp, row_number() OVER (), v.pat, v.perg, NULL, v.alt, v.val, v.un, 412
    FROM (VALUES
     ('Cliente Amarelo', 1, 'Sim',             64,  'PERCENTUAL'),
     ('Cliente Amarelo', 1, 'Não',             36,  'PERCENTUAL'),
     ('Cliente Amarelo', 2, 'Nota média',      8.4, 'NOTA'),
     ('Cliente Amarelo', 2, 'Notas 9 e 10',    61,  'PERCENTUAL'),
     ('Cliente Amarelo', 2, 'Notas 7 e 8',     28,  'PERCENTUAL'),
     ('Cliente Amarelo', 2, 'Notas de 0 a 6',  11,  'PERCENTUAL'),
     ('Cliente Amarelo', 3, 'Melhorou',        58,  'PERCENTUAL'),
     ('Cliente Amarelo', 3, 'Não mudou',       39,  'PERCENTUAL'),
     ('Cliente Amarelo', 3, 'Piorou',          3,   'PERCENTUAL'),
     ('Amarelo',         1, 'Sim',             64,  'PERCENTUAL'),   -- repetida (alias do mesmo patrocinador)
     ('Cliente Amarelo', 3, 'Outro',           140, 'PERCENTUAL')    -- percentual fora de 0 a 100
    ) AS v(pat, perg, alt, val, un);

    SELECT id INTO v_pesq FROM pesquisa WHERE escopo = 'ATIVACAO';
    SELECT * INTO r FROM fn_carregar_pesquisa(v_cp, v_pesq);
    RAISE NOTICE 'Carga de pesquisa: lidas %, inseridas %, duplicadas %, rejeitadas %', r.lidas, r.inseridas, r.duplicadas, r.rejeitadas;

    -- [RELATORIO] [ITEM-7] Versão 1 (rascunho) do relatório das três parcerias de 2027. A data simulada vai em p_em, que fn_gerar_relatorio
    -- grava em relatorio.gerado_em e no histórico: assim a amostra não precisa de UPDATE em tabela imutável.
    PERFORM fn_gerar_relatorio(pa.id, v_b2b, pg_temp.quando(10, '11:00'))
    FROM parceria pa WHERE pa.evento_id = v_ev ORDER BY pa.id;
END $$;

DROP TABLE IF EXISTS pg_temp._amostra_foto, pg_temp._amostra_alvo, pg_temp._amostra_carga, pg_temp._amostra_envio, pg_temp._amostra_parc;
DROP FUNCTION IF EXISTS pg_temp.quando(INT, TIME);
DROP TABLE IF EXISTS pg_temp._amostra_ctl;

-- ---------------------------------------------------------------------
-- 17. Impacto e uma linha mensal na mídia do Amarelo (M1 e M2)
-- ---------------------------------------------------------------------
-- [M1] [M2] A consulta da mídia (rotas 105 a 108) e a comprovação mensal mostram o impacto e o total de um mês. Este bloco
-- roda mesmo quando o resto da amostra já está no banco (o pular acima), então é idempotente: o UPDATE só toca o impacto
-- nulo e o INSERT usa ON CONFLICT. O mês é derivado da primeira exibição da amostra (seis meses antes), e não de hoje, para
-- ser o mesmo em toda repetição; a linha mensal fica num mês sem exibição daquela linha do plano.
DO $$
DECLARE
    v_amarelo BIGINT;
    v_ap BIGINT;
    v_mes DATE;
BEGIN
    SELECT pa.id INTO v_amarelo
    FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id
    JOIN evento e ON e.id = pa.evento_id
    WHERE p.nome_canonico = 'Cliente Amarelo' AND e.edicao = 2027;
    IF v_amarelo IS NULL THEN RETURN; END IF;

    -- [M1] Impacto em algumas exibições de TV e rádio do Amarelo. O WHERE impacto IS NULL deixa a repetição segura.
    UPDATE veiculacao v
    SET impacto = round(v.quantidade * CASE ap.veiculo WHEN 'RBS TV' THEN 1500 ELSE 800 END)
    FROM aproveitamento_previsto ap
    WHERE ap.id = v.aproveitamento_id AND ap.parceria_id = v_amarelo
      AND ap.categoria = 'MIDIA' AND ap.veiculo IN ('RBS TV', 'Rádio 92')
      AND v.impacto IS NULL;

    -- [M2] Uma linha mensal para a primeira linha do plano do Amarelo, num mês bem antes da primeira exibição.
    SELECT ap.id INTO v_ap
    FROM aproveitamento_previsto ap
    WHERE ap.parceria_id = v_amarelo AND ap.categoria = 'MIDIA'
    ORDER BY ap.id LIMIT 1;

    SELECT (date_trunc('month', min(v.exibido_em) AT TIME ZONE 'America/Sao_Paulo') - INTERVAL '6 months')::date
    INTO v_mes
    FROM veiculacao v JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id
    WHERE ap.parceria_id = v_amarelo AND ap.categoria = 'MIDIA' AND v.periodo = 'EXIBICAO';

    IF v_ap IS NOT NULL AND v_mes IS NOT NULL THEN
        -- A linha mensal entra ligada à primeira carga da amostra (a de veiculação), para não ficar sem origem; o
        -- periodo MES a distingue das exibições carregadas.
        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, impacto, periodo, mes_referencia, carga_id)
        VALUES (v_ap, v_mes::timestamp AT TIME ZONE 'America/Sao_Paulo', 10, 5000, 'MES', v_mes,
                (SELECT min(id) FROM carga WHERE tipo = 'MIDIA'))
        ON CONFLICT ON CONSTRAINT uq_veiculacao DO NOTHING;
    END IF;
END $$;

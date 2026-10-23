-- Amostra de desenvolvimento e teste: Amarelo (Master), Roxo (Subcota G) e Verde (Subcota) com usuários, planos, entregas, comprovações,
-- pesquisa, cortesias, as duas cargas de veiculação, uma carga de pesquisa e os 3 relatórios de 2027. Atende o item 1 da Entrega 2.

-- Fica em db/dev, uma pasta que só os perfis dev e test leem: produção nunca carrega estes dados. Os clientes são fictícios
-- (planos comerciais reais de 2027 com nomes trocados por cores); os números de 2026 vêm do PV_PA_26_GERAL; o resto é simulado.
-- É repetível (R__), e o Flyway a roda de novo sempre que o arquivo muda. Por isso:
--  * [DUPLICIDADE] Trava: se a amostra já está no banco, nada é feito. Para recarregá-la, recrie o banco de dev.
--  * [HISTORICO] Nenhum UPDATE ou DELETE em historico ou relatorio (o histórico é imutável e o relatório só avança de status): as
--    datas simuladas entram no próprio INSERT, e fn_gerar_relatorio recebe a data em p_em.
--  * Cada bloco DO começa pela trava, e as tabelas temporárias _amostra_* são apagadas no fim.
-- Senha de todos os usuários: senha123 (hash BCrypt literal, sem pgcrypto; os hashes estão aqui só para dev e test).

DROP TABLE IF EXISTS pg_temp._amostra_ctl;
CREATE TEMP TABLE _amostra_ctl AS
SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(email) = 'b2b@rbs.example')
    OR EXISTS (SELECT 1 FROM patrocinador WHERE nome_canonico IN ('Cliente Amarelo', 'Cliente Roxo', 'Cliente Verde')) AS pular;

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
    INSERT INTO usuario (nome, email, senha_hash, perfil) VALUES
     ('Equipe B2B RBS',         'b2b@rbs.example',               '$2a$10$0C4T.v3cVusBsIM8uDxib.R1lXGkiB0BgjEwQxAz6pQLY9Mh7mpx.', 'ADMIN_RBS'),
     ('Equipe de Pesquisa RBS', 'pesquisa@rbs.example',          '$2a$10$6jlmhibUkxLaVG3JxRPK2OJ1mZVEqBf0RKOCyKOSp/LDeJXKkCF.K', 'ADMIN_RBS'),
     ('Marketing Amarelo',      'marketing@amarelo.example',     '$2a$10$2AKvpS64jrj7bGghRHr7reHpsL26cNFZQRuGF1SzOfqJIAaQgDRH.', 'ADMIN_PATROCINADOR'),
     ('Agência de ativação',    'atendimento@agencia-x.example', '$2a$10$b8pl2hU9wKG4TXSy1clv7uX3h82KH/pu/mA5ciNECN6HCuTvqI9SK', 'ADMIN_PATROCINADOR'),
     ('Marketing Roxo',         'marketing@roxo.example',        '$2a$10$hWwkcoCPwGnkBQHjSUamZexA750NCiPngz6TjLfJFGCwCxiQ41uWW', 'ADMIN_PATROCINADOR'),
     ('Marketing Verde',        'marketing@verde.example',       '$2a$10$k3/UaxBHvXnCA1w24CHsh.BBi69UvS2tf7/D/yenrnBwFCRGMn4LS', 'ADMIN_PATROCINADOR');

    -- Arquivo geral da RBS (parceria nula): é a fonte dos números de 2026 e da pesquisa geral (regra rn_pesquisa_fonte_geral)
    INSERT INTO arquivo (categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT 'PESQUISA', 'PV_PA_26_GERAL.pdf', 'application/pdf', 1893420, 'rbs/geral/PV_PA_26_GERAL.pdf', u.id, timestamptz '2026-03-10 15:00-03'
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

    INSERT INTO patrocinador (nome_canonico, segmento) VALUES
     ('Cliente Amarelo', NULL), ('Cliente Roxo', NULL), ('Cliente Verde', NULL);

    -- Grafias alternativas: a emissora escreve "Amarelo" no relatório de veiculação e a carga resolve pelo alias
    INSERT INTO patrocinador_alias (patrocinador_id, alias)
    SELECT id, a FROM patrocinador, (VALUES ('Amarelo'), ('Cliente Amarelo S.A.')) AS v(a)
    WHERE nome_canonico = 'Cliente Amarelo';

    -- [ISOLAMENTO] Uma parceria por marca por edição. O Amarelo também tem a de 2026 (encerrada), que fica sem usuários e sem
    -- cargas: serve para provar que o que é de uma edição não aparece na outra. O gatilho rn_parceria_abas_padrao cria as 7 abas
    -- de cada uma conforme cota_tipo_aba (V3): Pesquisa só para a Master.
    INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, nome_produto, valor_investimento, base_valor, situacao)
    SELECT e.id, p.id, v.cota, v.produto, v.valor, v.base, v.sit
    FROM (VALUES
     (2027, 'Cliente Amarelo', 1, 'Cota Patrocínio Master',                   1000000, 'BRUTO_TABELA', 'ATIVA'),
     (2027, 'Cliente Roxo',    2, 'Cota Apresenta (transmissão oficial)',      500000, 'LIQUIDO',      'ATIVA'),
     (2027, 'Cliente Verde',   3, 'Cota Ativação Arena',                       100000, 'LIQUIDO',      'ATIVA'),
     (2026, 'Cliente Amarelo', 1, 'Cota Patrocínio Master',                      NULL, NULL,           'ENCERRADA')
    ) AS v(ed, pat, cota, produto, valor, base, sit)
    JOIN evento e ON e.nome = 'Planeta Atlântida' AND e.edicao = v.ed
    JOIN patrocinador p ON p.nome_canonico = v.pat;

    -- Atalho para o resto do script: as três parcerias de 2027 pelo nome do cliente
    DROP TABLE IF EXISTS pg_temp._amostra_parc;
    CREATE TEMP TABLE _amostra_parc AS
    SELECT pa.id, p.nome_canonico AS cliente
    FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id
    WHERE e.edicao = 2027;

    -- [PERMISSAO] A RBS autoriza o e-mail; só então o usuário pode ser vinculado (gatilho rn_vinculo_exige_autorizacao).
    -- O vínculo é MARCA ou AGENCIA; a agência do Amarelo tem acesso à mesma parceria.
    INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por, autorizado_em)
    SELECT pp.id, v.email, v.vinc, (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), timestamptz '2026-09-28 10:00-03'
    FROM (VALUES ('Cliente Amarelo', 'marketing@amarelo.example',     'MARCA'),
                 ('Cliente Amarelo', 'atendimento@agencia-x.example', 'AGENCIA'),
                 ('Cliente Roxo',    'marketing@roxo.example',        'MARCA'),
                 ('Cliente Verde',   'marketing@verde.example',       'MARCA')) AS v(cli, email, vinc)
    JOIN _amostra_parc pp ON pp.cliente = v.cli;

    INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
    SELECT ea.parceria_id, u.id, ea.vinculo
    FROM email_autorizado ea JOIN usuario u ON lower(u.email) = lower(ea.email);

    -- [UPLOAD] Plano comercial de cada parceria (PDF), guardado na aba Perfil do cliente (todo material de parceria fica numa aba).
    -- As linhas do plano abaixo citam este arquivo como fonte; o banco confere que ele é da mesma parceria da linha
    -- (chave composta fk_aprov_fonte_parceria).
    INSERT INTO arquivo (parceria_id, aba_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT pp.id, (SELECT a.id FROM aba a WHERE a.codigo = 'PERFIL'), 'PLANO_COMERCIAL','plano_comercial_' || lower(replace(pp.cliente, ' ', '_')) || '_PA27.pdf', 'application/pdf',
           v.tam, 'parcerias/' || pp.id || '/plano/plano_comercial_PA27.pdf',
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), timestamptz '2026-09-20 11:00-03'
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
-- 5. Entregas (cenário pós-evento), o arquivo logo_final.pdf com 2 versões e o histórico
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO entrega (parceria_id, aba_id, titulo, status, prazo)
    SELECT pp.id, (SELECT a.id FROM aba a WHERE a.codigo = v.aba), v.titulo, v.status, v.prazo::date
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

    -- [UPLOAD] Arquivo com versões (exemplo do documento do grupo): a mesma entrega, parceria e aba nas duas versões
    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por, enviado_em)
    SELECT e.parceria_id, e.aba_id, e.id, 'MATERIAL_MARCA', 'logo_final.pdf', 'application/pdf', v.tam,
           'parcerias/' || e.parceria_id || '/aplicacoes/logo_final_v' || v.ver || '.pdf', v.ver,
           (SELECT id FROM usuario WHERE lower(email) = 'marketing@amarelo.example'), v.quando::timestamptz
    FROM entrega e, (VALUES (1, 482113, '2026-10-15 14:20-03'), (2, 501877, '2026-10-17 18:03-03')) AS v(ver, tam, quando)
    WHERE e.titulo = 'Logo final para aplicações no evento';

    -- [HISTORICO] Ciclo do logo: envio, ajuste pedido, nova versão, aprovado. As datas entram no próprio INSERT.
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_anterior, valor_novo, usuario_id, ocorrido_em, ip, user_agent)
    SELECT e.parceria_id, 'entrega', e.id, v.acao, v.ant, v.novo, u.id, v.quando::timestamptz, v.ip::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'
    FROM entrega e,
     (VALUES ('ENVIO_ARQUIVO',    NULL,                'logo_final.pdf (versão 1)', 'marketing@amarelo.example', '2026-10-15 14:20-03', '200.0.2.10'),
             ('ALTERACAO_STATUS', 'EM_ANALISE',        'AJUSTE_SOLICITADO',         'b2b@rbs.example',           '2026-10-16 09:42-03', '10.0.0.5'),
             ('NOVA_VERSAO',      'versão 1',          'logo_final.pdf (versão 2)', 'marketing@amarelo.example', '2026-10-17 18:03-03', '200.0.2.10'),
             ('ALTERACAO_STATUS', 'AJUSTE_SOLICITADO', 'APROVADO',                  'b2b@rbs.example',           '2026-10-18 10:15-03', '10.0.0.5')
     ) AS v(acao, ant, novo, email, quando, ip)
    JOIN usuario u ON lower(u.email) = v.email
    WHERE e.titulo = 'Logo final para aplicações no evento';

    -- [HISTORICO] [ITEM-5] Acesso, download e autorização (ações novas). A tentativa de login com e-mail errado não tem usuário:
    -- usuario_id e entidade_id ficam nulos e o e-mail tentado vai no detalhe.
    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em, ip, user_agent)
    SELECT ea.parceria_id, 'email_autorizado', ea.id, 'AUTORIZACAO', ea.email || ' (' || lower(ea.vinculo) || ')', ea.autorizado_por,
           ea.autorizado_em, '10.0.0.5'::inet, 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM email_autorizado ea;

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, detalhe, usuario_id, ocorrido_em, ip, user_agent)
    VALUES (NULL, 'usuario', NULL, 'LOGIN_FALHOU', 'e-mail tentado: amarelo.marketing@amarelo.example', NULL,
            timestamptz '2026-10-15 14:08-03', '200.0.2.10', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15');

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, usuario_id, ocorrido_em, ip, user_agent)
    SELECT pu.parceria_id, 'usuario', u.id, 'LOGIN', u.id, timestamptz '2026-10-15 14:10-03', '200.0.2.10'::inet,
           'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM usuario u JOIN parceria_usuario pu ON pu.usuario_id = u.id
    WHERE lower(u.email) = 'marketing@amarelo.example';

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em, ip, user_agent)
    SELECT a.parceria_id, 'arquivo', a.id, 'DOWNLOAD', a.nome_original || ' (versão ' || a.versao || ')', u.id,
           timestamptz '2026-10-18 10:05-03', '10.0.0.5'::inet, 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Safari/605.1.15'
    FROM arquivo a, usuario u
    WHERE a.nome_original = 'logo_final.pdf' AND a.versao = 2 AND lower(u.email) = 'b2b@rbs.example';
END $$;

-- ---------------------------------------------------------------------
-- 6. Cortesias, credenciais e contrato (planos 2027)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF (SELECT pular FROM _amostra_ctl) THEN RETURN; END IF;

    INSERT INTO cortesia_cota (parceria_id, setor, dia, quantidade, com_shuttle, acesso)
    SELECT pp.id, v.setor, d.dia, v.qtd, v.shuttle, v.acesso
    FROM _amostra_parc pp
    JOIN (VALUES ('Cliente Amarelo', 'ARENA',    25, FALSE, 'Portão 6'),
                 ('Cliente Amarelo', 'CAMAROTE', 40, TRUE,  'Portão 6 ou 4; shuttle via portão 4'),
                 ('Cliente Amarelo', 'LOUNGE',   10, TRUE,  'Portão 6 ou 4; shuttle via portão 4'),
                 ('Cliente Roxo',    'ARENA',     4, FALSE, 'P6'),
                 ('Cliente Roxo',    'CAMAROTE',  2, TRUE,  'P6 ou P4; shuttle via P4'),
                 ('Cliente Verde',   'ARENA',     4, FALSE, 'P6'),
                 ('Cliente Verde',   'CAMAROTE',  2, TRUE,  'P6 ou P4; shuttle via P4')) AS v(cli, setor, qtd, shuttle, acesso)
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
    -- na Arena de sexta, e a Verde só 2 de 4 na Arena de sábado.
    INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
    SELECT cc.id, 'Convidados da marca (lote)', v.qtd,
           (SELECT u.id FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
             WHERE pu.parceria_id = cc.parceria_id AND pu.vinculo = 'MARCA')
    FROM cortesia_cota cc JOIN _amostra_parc pp ON pp.id = cc.parceria_id
    JOIN (VALUES ('Cliente Amarelo', 'ARENA',    'SEXTA',  25), ('Cliente Amarelo', 'ARENA',    'SABADO', 22),
                 ('Cliente Amarelo', 'CAMAROTE', 'SEXTA',  40), ('Cliente Amarelo', 'CAMAROTE', 'SABADO', 38),
                 ('Cliente Amarelo', 'LOUNGE',   'SEXTA',  10), ('Cliente Amarelo', 'LOUNGE',   'SABADO',  8),
                 ('Cliente Roxo',    'ARENA',    'SEXTA',   4), ('Cliente Roxo',    'ARENA',    'SABADO',  4),
                 ('Cliente Roxo',    'CAMAROTE', 'SEXTA',   2), ('Cliente Roxo',    'CAMAROTE', 'SABADO',  2),
                 ('Cliente Verde',   'ARENA',    'SEXTA',   4), ('Cliente Verde',   'CAMAROTE', 'SEXTA',   2),
                 ('Cliente Verde',   'ARENA',    'SABADO',  2)) AS v(cli, setor, dia, qtd)
      ON v.cli = pp.cliente AND v.setor = cc.setor AND v.dia = cc.dia;

    -- Contrato assinado do Amarelo: arquivo da categoria CONTRATO na entrega "Contrato final assinado" (mesma parceria e aba)
    INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por, enviado_em)
    SELECT e.parceria_id, e.aba_id, e.id, 'CONTRATO', 'contrato_assinado_cliente_amarelo_PA27.pdf', 'application/pdf', 734210,
           'parcerias/' || e.parceria_id || '/contrato/contrato_assinado_PA27.pdf',
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), timestamptz '2026-10-05 16:30-03'
    FROM entrega e JOIN _amostra_parc pp ON pp.id = e.parceria_id
    WHERE pp.cliente = 'Cliente Amarelo' AND e.titulo = 'Contrato final assinado';

    -- Um contrato (e um signatário) por parceria: a pessoa de marketing da marca informa os dados
    INSERT INTO contrato (parceria_id, signatario_nome, signatario_email, signatario_cargo, informado_por, arquivo_id, assinado_em)
    SELECT pu.parceria_id, 'Signatário ' || p.nome_canonico, u.email, 'Diretor(a) de Marketing', u.id,
           (SELECT a.id FROM arquivo a WHERE a.parceria_id = pu.parceria_id AND a.categoria = 'CONTRATO'),
           CASE WHEN p.nome_canonico = 'Cliente Amarelo' THEN date '2026-10-05' END
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
    SELECT id, 'Pesquisa nos espaços de ativação 2027 (simulada)', 'ATIVACAO', 'Entrevistas nos espaços da marca (Arena e Camarote)', 412
    FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;

    -- A RBS aprova as perguntas por uma rota da API, e só pergunta APROVADA recebe resultado; na amostra as 3 já chegam APROVADAS.
    INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto, status, enviada_por, enviada_em)
    SELECT pp.id, (SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'), v.ordem, v.texto, 'APROVADA',
           (SELECT id FROM usuario WHERE lower(email) = 'marketing@amarelo.example'), timestamptz '2026-11-20 16:00-03'
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
           (SELECT id FROM usuario WHERE lower(email) = 'b2b@rbs.example'), timestamptz '2027-02-05 12:00-03'
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
           u.id, v.quando::timestamptz
    FROM usuario u, (VALUES
     ('veiculacao_consolidada_PA27.xlsx',           'rbs/cargas/veiculacao_consolidada_PA27.xlsx',         '2027-02-20 10:00-03'),
     ('veiculacao_consolidada_PA27 (reenvio).xlsx', 'rbs/cargas/veiculacao_consolidada_PA27_reenvio.xlsx', '2027-02-21 09:30-03')
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

    -- Inserções: uma linha por exibição; CPM: 10 totais diários. Dois problemas reais de arquivo já entram aqui:
    --  (a) a emissora usa o perfil correto "@rede_atlantida" e o plano do Roxo traz "@prede_atlantida": essas linhas
    --      não casam com o plano e serão rejeitadas;
    --  (b) grafia alternativa da marca: a Rádio 92 escreve "Amarelo", e a tabela patrocinador_alias resolve.
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
    SELECT v_c1, row_number() OVER (ORDER BY a.id, g),
           CASE WHEN a.nome_canonico = 'Cliente Amarelo' AND a.veiculo = 'Rádio 92' THEN 'Amarelo' ELSE a.nome_canonico END,
           a.veiculo, a.formato,
           CASE WHEN a.espaco_programa = 'IG @prede_atlantida' THEN 'IG @rede_atlantida' ELSE a.espaco_programa END,
           timestamptz '2026-11-02 06:00-03' + g * interval '97 minutes' + a.id * interval '1 minute',
           CASE WHEN a.unidade = 'CPM' THEN a.exibir / 10 ELSE 1 END
    FROM _amostra_alvo a
    CROSS JOIN LATERAL generate_series(1, CASE WHEN a.unidade = 'CPM' THEN 10 ELSE a.exibir::int END) AS g;

    -- (c) linha sem data (Verde), (d) marca sem parceria na edição (Cliente Azul)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
    SELECT v_c1, m + 1, 'Cliente Verde', 'atl.la', 'Story próprio de até 1'' (ativação do cotista)', 'IG do evento', NULL, 1
    FROM (SELECT max(linha) AS m FROM stg_veiculacao WHERE carga_id = v_c1) x
    UNION ALL
    SELECT v_c1, m + 2, 'Cliente Azul', 'Rede Atlântida RS', 'Chamadas assinadas 5"', 'Indeterminado', timestamptz '2026-12-01 10:00-03', 1
    FROM (SELECT max(linha) AS m FROM stg_veiculacao WHERE carga_id = v_c1) x;

    -- (e) linhas repetidas dentro do próprio arquivo (as 2 exibições do VT de telão da Verde, com número de linha novo)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
    SELECT carga_id, linha + 100000, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade
    FROM stg_veiculacao WHERE carga_id = v_c1 AND patrocinador = 'Cliente Verde' AND formato = 'VT telão 30"';

    -- Carga 2: o mesmo arquivo reenviado (as mesmas 1.489 linhas, com os mesmos problemas)
    INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade)
    SELECT v_c2, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade
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
    SELECT 'PESQUISA', 'pesquisa.csv', 'text/csv', 568,'rbs/cargas/pesquisa_ativacao_2027.csv', u.id, timestamptz '2027-02-21 10:30-03'
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
    PERFORM fn_gerar_relatorio(pa.id, v_b2b, timestamptz '2027-02-21 11:00-03')
    FROM parceria pa WHERE pa.evento_id = v_ev ORDER BY pa.id;
END $$;

DROP TABLE IF EXISTS pg_temp._amostra_foto, pg_temp._amostra_alvo, pg_temp._amostra_carga, pg_temp._amostra_parc, pg_temp._amostra_ctl;

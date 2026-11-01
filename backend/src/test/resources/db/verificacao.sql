-- Confere o banco depois de V1 a V15 e da amostra R__amostra.sql: contagens, cargas, relatórios, órfãos e uma tentativa de violar cada regra.
-- Atende o item 1 da Entrega 2 (amostra e verificações), prova as 8 correções do item 8 e, na seção 8, o plano comercial, o
-- catálogo e os dados informados da V7 e, na seção 9, as tabelas do Atlas da V8, o resumo do raciocínio da V9 e o traço do diagnóstico da V10 e, na seção 10, os documentos, os trechos, a busca sem acento e a RLS da V11 e, na seção 11, as fontes citadas, o documento congelado e o plural da busca da V12 e, na seção 12, os prazos da V13 e, na seção 14, o JEV e a conversa sem parceria da V15. Nenhuma linha fica gravada: as tentativas são desfeitas (só as sequências avançam).

-- Como rodar (banco de dev ou de teste com a amostra carregada; sem meta-comandos, serve também para JDBC com o texto inteiro):
--   PGPASSWORD=portal psql -X -P pager=off -v ON_ERROR_STOP=1 -h localhost -U portal -d <banco> -f verificacao.sql
-- A última tabela lista cada conferência; se alguma falhar, o script termina com erro (código de saída diferente de zero).

DROP TABLE IF EXISTS pg_temp._ver;
CREATE TEMP TABLE _ver (
    ordem        SERIAL,
    grupo        TEXT,
    conferencia  TEXT,
    esperado     TEXT,
    obtido       TEXT,
    ok           BOOLEAN
);

-- Número esperado x obtido
CREATE OR REPLACE FUNCTION pg_temp.confere(p_grupo TEXT, p_conferencia TEXT, p_esperado NUMERIC, p_obtido NUMERIC)
RETURNS VOID LANGUAGE sql AS $f$
    INSERT INTO _ver (grupo, conferencia, esperado, obtido, ok)
    VALUES (p_grupo, p_conferencia, p_esperado::text, coalesce(p_obtido::text, 'nulo'), p_obtido IS NOT DISTINCT FROM p_esperado);
$f$;

-- Verdadeiro/falso esperado
CREATE OR REPLACE FUNCTION pg_temp.afirma(p_grupo TEXT, p_conferencia TEXT, p_verdade BOOLEAN)
RETURNS VOID LANGUAGE sql AS $f$
    INSERT INTO _ver (grupo, conferencia, esperado, obtido, ok)
    VALUES (p_grupo, p_conferencia, 'sim', CASE WHEN p_verdade THEN 'sim' ELSE 'não' END, coalesce(p_verdade, FALSE));
$f$;

-- Tentativa de violar uma regra: roda o comando e exige a recusa com SQLSTATE da classe 23 e o nome da regra esperado.
-- Se o banco aceitar, o ERRCODE ZZ001 desfaz o comando e a conferência falha. Se recusar, o comando também não deixa nada para trás.
CREATE OR REPLACE FUNCTION pg_temp.recusa(p_grupo TEXT, p_conferencia TEXT, p_regra TEXT, p_comando TEXT)
RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE v_estado TEXT := 'ZZ001'; v_regra TEXT;
BEGIN
    BEGIN
        EXECUTE p_comando;
        RAISE EXCEPTION 'desfazer a tentativa' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_estado = RETURNED_SQLSTATE, v_regra = CONSTRAINT_NAME;
    END;
    INSERT INTO _ver (grupo, conferencia, esperado, obtido, ok)
    VALUES (p_grupo, p_conferencia, 'recusa 23xxx por ' || p_regra,
            CASE WHEN v_estado = 'ZZ001' THEN 'ACEITO (sem recusa)' ELSE v_estado || ' por ' || coalesce(v_regra, '?') END,
            v_estado LIKE '23%' AND v_regra = p_regra);
END $f$;

-- O contrário: um comando legítimo que o banco deve aceitar (também desfeito no fim, para não alterar a amostra)
CREATE OR REPLACE FUNCTION pg_temp.aceita(p_grupo TEXT, p_conferencia TEXT, p_comando TEXT)
RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE v_estado TEXT := 'ZZ001'; v_msg TEXT;
BEGIN
    BEGIN
        EXECUTE p_comando;
        RAISE EXCEPTION 'desfazer a tentativa' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_estado = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
    END;
    INSERT INTO _ver (grupo, conferencia, esperado, obtido, ok)
    VALUES (p_grupo, p_conferencia, 'aceito', CASE WHEN v_estado = 'ZZ001' THEN 'aceito' ELSE 'RECUSADO ' || v_estado || ': ' || left(v_msg, 80) END, v_estado = 'ZZ001');
END $f$;

-- ---------------------------------------------------------------------
-- 1. Estrutura e dados base (V1 a V8)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- A tabela de controle do Flyway (flyway_schema_history) não é do portal: fica de fora quando o banco foi criado pelo Flyway.
    PERFORM pg_temp.confere('ESTRUTURA', 'tabelas (29 da modelagem + 4 novas na V1 + 3 na V7 + 7 na V8 + 1 na V10 + 2 na V11 + 1 na V12 + 1 na V13)', 48,
       (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
           AND table_name <> 'flyway_schema_history'));
    PERFORM pg_temp.confere('ESTRUTURA', 'visões (7 de consolidação + vw_dado_vigente)', 8,
       (SELECT count(*) FROM information_schema.views WHERE table_schema = 'public'));
    PERFORM pg_temp.confere('ESTRUTURA', 'funções fn_carregar_veiculacoes, fn_carregar_pesquisa, fn_carregar_plano, fn_gerar_relatorio', 4,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public' AND p.proname IN ('fn_carregar_veiculacoes', 'fn_carregar_pesquisa', 'fn_carregar_plano', 'fn_gerar_relatorio')));
    PERFORM pg_temp.confere('ESTRUTURA', 'gatilhos de regra (rn_*)', 29,
       (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'rn\_%'));
    PERFORM pg_temp.confere('BASE', 'tipos de cota', 3, (SELECT count(*) FROM cota_tipo));
    PERFORM pg_temp.confere('BASE', 'abas do menu', 7, (SELECT count(*) FROM aba));
    PERFORM pg_temp.confere('BASE', 'abas por cota (3 cotas x 7, menos Pesquisa em duas)', 19, (SELECT count(*) FROM cota_tipo_aba));
    PERFORM pg_temp.afirma('BASE', 'Pesquisa liberada só para a Master (cota_tipo_aba)',
       (SELECT array_agg(ct.codigo::text) = ARRAY['MASTER'] FROM cota_tipo_aba cta JOIN cota_tipo ct ON ct.id = cta.cota_tipo_id
         JOIN aba a ON a.id = cta.aba_id WHERE a.codigo = 'PESQUISA'));
    PERFORM pg_temp.confere('BASE', 'indicadores do catálogo', 17, (SELECT count(*) FROM indicador));
    PERFORM pg_temp.confere('BASE', 'eventos (Planeta Atlântida 2026 e 2027)', 2, (SELECT count(*) FROM evento WHERE nome = 'Planeta Atlântida'));
END $$;

-- ---------------------------------------------------------------------
-- 2. Amostra (R__amostra.sql)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.confere('AMOSTRA', 'usuários (2 Admin RBS e 4 Admin Patrocinador)', 6, (SELECT count(*) FROM usuario));
    PERFORM pg_temp.confere('AMOSTRA', 'usuários Admin RBS', 2, (SELECT count(*) FROM usuario WHERE perfil = 'ADMIN_RBS'));
    PERFORM pg_temp.afirma('AMOSTRA', 'senhas em BCrypt de 60 caracteres ($2a$10$)',
       (SELECT bool_and(senha_hash ~ '^\$2a\$10\$[./A-Za-z0-9]{53}$') FROM usuario));
    PERFORM pg_temp.confere('AMOSTRA', 'patrocinadores', 3, (SELECT count(*) FROM patrocinador));
    PERFORM pg_temp.confere('AMOSTRA', 'parcerias (3 de 2027 e o Amarelo de 2026)', 4, (SELECT count(*) FROM parceria));
    PERFORM pg_temp.confere('AMOSTRA', 'vínculos usuário x parceria (Amarelo tem marca e agência)', 4, (SELECT count(*) FROM parceria_usuario));
    PERFORM pg_temp.confere('AMOSTRA', 'abas por parceria (4 x 7, criadas pelo gatilho)', 28, (SELECT count(*) FROM parceria_aba));
    PERFORM pg_temp.confere('AMOSTRA', 'abas liberadas: Amarelo 7 + 7 (2026 e 2027), Roxo 6, Verde 6 (Pesquisa só na Master)', 26,
       (SELECT count(*) FROM parceria_aba WHERE liberada));
    PERFORM pg_temp.confere('AMOSTRA', 'linhas do plano comercial: Roxo 38 + Amarelo 36 + Verde 5', 79, (SELECT count(*) FROM aproveitamento_previsto));
    PERFORM pg_temp.confere('AMOSTRA', 'linhas de mídia do plano', 69, (SELECT count(*) FROM aproveitamento_previsto WHERE categoria = 'MIDIA'));
    PERFORM pg_temp.confere('AMOSTRA', 'entregas', 28, (SELECT count(*) FROM entrega));
    PERFORM pg_temp.confere('AMOSTRA', 'versões de logo_final.pdf na mesma entrega', 2,
       (SELECT count(*) FROM arquivo WHERE nome_original = 'logo_final.pdf' AND entrega_id IS NOT NULL));
    PERFORM pg_temp.confere('AMOSTRA', 'fotos de comprovação (Amarelo 17 + Verde 2)', 19, (SELECT count(*) FROM comprovacao_marca));
    PERFORM pg_temp.confere('AMOSTRA', 'cortesias: 7 setores x 2 dias', 14, (SELECT count(*) FROM cortesia_cota));
    PERFORM pg_temp.confere('AMOSTRA', 'credenciais', 8, (SELECT count(*) FROM credencial_cota));
    PERFORM pg_temp.confere('AMOSTRA', 'contratos (um por parceria de 2027)', 3, (SELECT count(*) FROM contrato));
    PERFORM pg_temp.confere('AMOSTRA', 'números do festival 2026', 6, (SELECT count(*) FROM evento_resultado));
END $$;

-- ---------------------------------------------------------------------
-- 3. Cargas: veiculação (carga 1 e reenvio), pesquisa e motivos das rejeições
-- ---------------------------------------------------------------------
DO $$
DECLARE c RECORD;
BEGIN
    -- Carga 1 = a de menor número entre as de mídia; carga 2 = o reenvio do mesmo arquivo
    SELECT * INTO c FROM carga WHERE tipo = 'MIDIA' ORDER BY id LIMIT 1;
    PERFORM pg_temp.confere('CARGA 1', 'linhas lidas', 1489, c.linhas_lidas);
    PERFORM pg_temp.confere('CARGA 1', 'inseridas', 1483, c.linhas_inseridas);
    PERFORM pg_temp.confere('CARGA 1', 'duplicadas (linhas repetidas no próprio arquivo)', 2, c.linhas_duplicadas);
    PERFORM pg_temp.confere('CARGA 1', 'rejeitadas', 4, c.linhas_rejeitadas);
    PERFORM pg_temp.confere('CARGA 1', 'rejeição: reels do Roxo (aproveitamento fora do plano)', 2,
        (SELECT count(*) FROM carga_rejeicao WHERE carga_id = c.id AND motivo = 'Aproveitamento não encontrado no plano comercial'));
    PERFORM pg_temp.confere('CARGA 1', 'rejeição: linha da Verde sem data', 1,
        (SELECT count(*) FROM carga_rejeicao WHERE carga_id = c.id AND campo = 'exibido_em' AND motivo = 'Campo obrigatório vazio ou inválido'));
    PERFORM pg_temp.confere('CARGA 1', 'rejeição: Cliente Azul (marca sem parceria na edição)', 1,
        (SELECT count(*) FROM carga_rejeicao WHERE carga_id = c.id AND motivo = 'Patrocinador sem parceria nesta edição'));
    PERFORM pg_temp.afirma('CARGA 1', 'lidas = inseridas + duplicadas + rejeitadas',
        c.linhas_lidas = c.linhas_inseridas + c.linhas_duplicadas + c.linhas_rejeitadas);
    PERFORM pg_temp.afirma('CARGA 1', 'cargas marcadas como processadas, com o evento', c.processada_em IS NOT NULL AND c.evento_id IS NOT NULL);

    SELECT * INTO c FROM carga WHERE tipo = 'MIDIA' ORDER BY id DESC LIMIT 1;
    PERFORM pg_temp.confere('CARGA 2', 'linhas lidas (mesmo arquivo)', 1489, c.linhas_lidas);
    PERFORM pg_temp.confere('CARGA 2', 'inseridas', 0, c.linhas_inseridas);
    PERFORM pg_temp.confere('CARGA 2', 'duplicadas', 1485, c.linhas_duplicadas);
    PERFORM pg_temp.confere('CARGA 2', 'rejeitadas', 4, c.linhas_rejeitadas);
    PERFORM pg_temp.confere('CARGA 2', 'veiculações na base depois das duas cargas (sem contagem em dobro)', 1483, (SELECT count(*) FROM veiculacao));

    SELECT * INTO c FROM carga WHERE tipo = 'PESQUISA' ORDER BY id LIMIT 1;
    PERFORM pg_temp.confere('PESQUISA', 'carga de pesquisa: lidas', 11, c.linhas_lidas);
    PERFORM pg_temp.confere('PESQUISA', 'inseridas (3 perguntas, 9 alternativas)', 9, c.linhas_inseridas);
    PERFORM pg_temp.confere('PESQUISA', 'duplicadas (alternativa repetida pelo alias do patrocinador)', 1, c.linhas_duplicadas);
    PERFORM pg_temp.confere('PESQUISA', 'rejeitadas (percentual de 140)', 1, c.linhas_rejeitadas);
    PERFORM pg_temp.confere('PESQUISA', 'resultados da ativação ligados à carga', 9, (SELECT count(*) FROM pesquisa_resultado WHERE carga_id = c.id));
    PERFORM pg_temp.confere('PESQUISA', 'resultados da pesquisa geral do festival (2026)', 5,
        (SELECT count(*) FROM pesquisa_resultado r JOIN pesquisa p ON p.id = r.pesquisa_id WHERE p.escopo = 'GERAL' AND r.parceria_id IS NULL));
END $$;

-- ---------------------------------------------------------------------
-- 4. Consolidação: previsto x exibido, aplicações, cortesias e relatórios
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Roxo, inserções: exibido de previsto (%, cortado na 1ª casa)', 97.0,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Roxo' AND u.unidade = 'INSERCAO'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Roxo, CPM: exibido de previsto (%, cortado), sem somar com inserções', 97.2,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Roxo' AND u.unidade = 'CPM'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Amarelo, inserções: exibido de previsto (%, cortado: 1105 de 1113 = 99,28)', 99.2,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Amarelo' AND u.unidade = 'INSERCAO'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Verde, inserções: exibido de previsto (%)', 100,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Verde' AND u.unidade = 'INSERCAO'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Amarelo: aplicações previstas', 19,
        (SELECT a.previstas FROM vw_aplicacoes a JOIN parceria pa ON pa.id = a.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id WHERE p.nome_canonico = 'Cliente Amarelo'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Amarelo: aplicações comprovadas (Lounge sem foto, Shuttle 3 de 4)', 17,
        (SELECT a.comprovadas FROM vw_aplicacoes a JOIN parceria pa ON pa.id = a.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id WHERE p.nome_canonico = 'Cliente Amarelo'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Amarelo: cortesias destinadas de 150 disponíveis', 143,
        (SELECT c.destinadas FROM vw_cortesias c JOIN parceria pa ON pa.id = c.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id WHERE p.nome_canonico = 'Cliente Amarelo'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'faltantes do Roxo: 1 sem exibição (reels) + 3 abaixo do previsto', 4,
        (SELECT count(*) FROM vw_faltantes f JOIN parceria pa ON pa.id = f.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Roxo' AND f.tipo IN ('MIDIA_SEM_EXIBICAO', 'MIDIA_ABAIXO_DO_PREVISTO')));

    -- Três relatórios, um por parceria de 2027, em rascunho, com a data simulada gravada por p_em (relatório e histórico)
    PERFORM pg_temp.confere('RELATÓRIOS', 'relatórios gerados', 3, (SELECT count(*) FROM relatorio));
    PERFORM pg_temp.confere('RELATÓRIOS', 'parcerias de 2027 com relatório (um cada)', 3,
        (SELECT count(DISTINCT r.parceria_id) FROM relatorio r JOIN parceria pa ON pa.id = r.parceria_id JOIN evento e ON e.id = pa.evento_id WHERE e.edicao = 2027));
    PERFORM pg_temp.afirma('RELATÓRIOS', 'todos na versão 1 e em RASCUNHO', (SELECT bool_and(versao = 1 AND status = 'RASCUNHO') FROM relatorio));
    -- [RELATORIO] O relatório traz sempre os 17 indicadores do catálogo (V5): 51 códigos nos 3 relatórios. O que não tem dado
    -- entra como indisponível: festival em números e pesquisa geral (só existem em 2026: 2 por parceria = 6), a pesquisa da
    -- ativação do Roxo e da Verde (fora da cota: 2 cada = 4) e as aplicações de marca do Roxo (sem plano de aplicações: 4).
    PERFORM pg_temp.confere('RELATÓRIOS', 'números nos 3 relatórios (72 de antes + 8 indisponíveis que a V5 passou a registrar + 3 faixas de nota da pesquisa)', 83, (SELECT count(*) FROM relatorio_indicador));
    PERFORM pg_temp.confere('RELATÓRIOS', 'números indisponíveis (festival 6 + pesquisa fora da cota 4 + aplicações do Roxo 4)', 14,
        (SELECT count(*) FROM relatorio_indicador WHERE NOT disponivel));
    PERFORM pg_temp.confere('RELATÓRIOS', 'números do relatório sem as 3 faixas novas = total de antes mais os 8 indisponíveis novos', 80,
        (SELECT count(*) FROM relatorio_indicador WHERE NOT (indicador_codigo = 'PESQUISA_RESULTADO' AND recorte IN ('P2 · Notas 9 e 10', 'P2 · Notas 7 e 8', 'P2 · Notas de 0 a 6'))));
    PERFORM pg_temp.afirma('RELATÓRIOS', 'data da geração no passado e igual no relatório e no histórico, sem UPDATE',
        (SELECT bool_and(r.gerado_em < now()) FROM relatorio r)
        AND (SELECT count(*) = 3 AND bool_and(h.ocorrido_em < now() AND EXISTS (SELECT 1 FROM relatorio r WHERE r.id = h.entidade_id
                                                                                  AND r.gerado_em = h.ocorrido_em))
               FROM historico h WHERE h.acao = 'GERACAO_RELATORIO'));
    PERFORM pg_temp.confere('RELATÓRIOS', 'indicadores do catálogo que ficaram sem linha em algum relatório (todos entram, nunca estimados)', 0,
        (SELECT count(*) FROM relatorio r CROSS JOIN indicador i
          WHERE NOT EXISTS (SELECT 1 FROM relatorio_indicador ri WHERE ri.relatorio_id = r.id AND ri.indicador_codigo = i.codigo)));
    PERFORM pg_temp.confere('RELATÓRIOS', 'indicadores sem linha de dado ficam indisponíveis, com valor nulo e sem recorte', 0,
        (SELECT count(*) FROM relatorio_indicador WHERE NOT disponivel AND (valor IS NOT NULL OR recorte <> '')));
END $$;

-- ---------------------------------------------------------------------
-- 5. Órfãos: tudo deve ficar ligado ao que o explica (esperado 0 em cada linha)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- [ISOLAMENTO] Todo registro de uma parceria chega a um evento e a um patrocinador
    PERFORM pg_temp.confere('ÓRFÃOS', 'parcerias sem evento ou sem patrocinador', 0,
        (SELECT count(*) FROM parceria pa LEFT JOIN evento e ON e.id = pa.evento_id LEFT JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE e.id IS NULL OR p.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'registros de 14 tabelas por parceria sem parceria, evento ou patrocinador', 0,
        (SELECT count(*) FROM (
            SELECT parceria_id FROM email_autorizado UNION ALL SELECT parceria_id FROM parceria_usuario UNION ALL
            SELECT parceria_id FROM parceria_aba UNION ALL SELECT parceria_id FROM entrega UNION ALL
            SELECT parceria_id FROM arquivo WHERE parceria_id IS NOT NULL UNION ALL
            SELECT parceria_id FROM historico WHERE parceria_id IS NOT NULL UNION ALL
            SELECT parceria_id FROM aproveitamento_previsto UNION ALL SELECT parceria_id FROM comprovacao_marca UNION ALL
            SELECT parceria_id FROM cortesia_cota UNION ALL SELECT parceria_id FROM credencial_cota UNION ALL
            SELECT parceria_id FROM contrato UNION ALL SELECT parceria_id FROM pesquisa_pergunta UNION ALL
            SELECT parceria_id FROM pesquisa_resultado WHERE parceria_id IS NOT NULL UNION ALL
            SELECT parceria_id FROM relatorio) t
          LEFT JOIN parceria pa ON pa.id = t.parceria_id
          LEFT JOIN evento e ON e.id = pa.evento_id LEFT JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE pa.id IS NULL OR e.id IS NULL OR p.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'veiculações, destinações e números de relatório sem a linha de origem', 0,
        (SELECT (SELECT count(*) FROM veiculacao v LEFT JOIN aproveitamento_previsto ap ON ap.id = v.aproveitamento_id WHERE ap.id IS NULL)
              + (SELECT count(*) FROM cortesia_destinacao d LEFT JOIN cortesia_cota cc ON cc.id = d.cortesia_cota_id WHERE cc.id IS NULL)
              + (SELECT count(*) FROM relatorio_indicador ri LEFT JOIN relatorio r ON r.id = ri.relatorio_id WHERE r.id IS NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'pesquisas, números do festival e cargas processadas sem evento', 0,
        (SELECT (SELECT count(*) FROM pesquisa WHERE evento_id IS NULL)
              + (SELECT count(*) FROM evento_resultado WHERE evento_id IS NULL)
              + (SELECT count(*) FROM carga WHERE processada_em IS NOT NULL AND evento_id IS NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'veiculações sem carga de origem', 0, (SELECT count(*) FROM veiculacao WHERE carga_id IS NULL));

    -- [UPLOAD] Todo material de uma parceria está numa aba (arquivo sem parceria é geral da RBS e não tem aba)
    -- Fora a categoria DOCUMENTO: o anexo de uma parceria (D06, D07, D11 do kit) é um arquivo da parceria sem aba, de propósito.
    PERFORM pg_temp.confere('ÓRFÃOS', 'materiais de parceria sem aba (fora os documentos do Atlas)', 0,
       (SELECT count(*) FROM arquivo WHERE parceria_id IS NOT NULL AND aba_id IS NULL AND categoria <> 'DOCUMENTO'));
    -- [DATAS] A amostra conta os horários para trás a partir do dia em que o banco nasce: nada fica no futuro
    PERFORM pg_temp.confere('AMOSTRA', 'horários no futuro (histórico, arquivos, cargas, relatórios, entregas, perguntas, exibições)', 0,
        (SELECT (SELECT count(*) FROM historico WHERE ocorrido_em > now()) + (SELECT count(*) FROM arquivo WHERE enviado_em > now())
              + (SELECT count(*) FROM carga WHERE executada_em > now()) + (SELECT count(*) FROM relatorio WHERE gerado_em > now())
              + (SELECT count(*) FROM entrega WHERE criado_em > now() OR atualizado_em > now())
              + (SELECT count(*) FROM pesquisa_pergunta WHERE enviada_em > now()) + (SELECT count(*) FROM veiculacao WHERE exibido_em > now())));
    -- [UPLOAD] Entrega enviada, em análise ou aprovada tem o arquivo que a marca mandou
    PERFORM pg_temp.confere('AMOSTRA', 'entregas Enviado, Em análise ou Aprovado sem nenhum arquivo', 0,
        (SELECT count(*) FROM entrega e WHERE e.status IN ('ENVIADO', 'EM_ANALISE', 'APROVADO')
            AND NOT EXISTS (SELECT 1 FROM arquivo a WHERE a.entrega_id = e.id)));
    -- [HISTORICO] Pedido de ajuste sempre leva o recado da RBS, e o ajuste que está aberto tem um envio da marca antes dele
    PERFORM pg_temp.confere('AMOSTRA', 'pedidos de ajuste no histórico sem observação', 0,
        (SELECT count(*) FROM historico WHERE acao = 'ALTERACAO_STATUS' AND entidade = 'entrega' AND valor_novo = 'AJUSTE_SOLICITADO'
            AND nullif(btrim(detalhe), '') IS NULL));
    PERFORM pg_temp.confere('AMOSTRA', 'entregas em Ajuste solicitado sem envio anterior da marca e sem pedido da RBS no histórico', 0,
        (SELECT count(*) FROM entrega e WHERE e.status = 'AJUSTE_SOLICITADO'
            AND NOT (EXISTS (SELECT 1 FROM arquivo a WHERE a.entrega_id = e.id)
                     AND EXISTS (SELECT 1 FROM historico h WHERE h.entidade = 'entrega' AND h.entidade_id = e.id
                                   AND h.acao = 'ALTERACAO_STATUS' AND h.valor_novo = 'AJUSTE_SOLICITADO'))));
    PERFORM pg_temp.confere('ÓRFÃOS', 'arquivos de uma entrega com outra parceria ou outra aba', 0,
        (SELECT count(*) FROM arquivo a JOIN entrega e ON e.id = a.entrega_id WHERE e.parceria_id <> a.parceria_id OR e.aba_id <> a.aba_id));

    -- Todo resultado de pesquisa chega à sua pergunta (ativação) ou é geral do festival (sem parceria e sem pergunta)
    PERFORM pg_temp.confere('ÓRFÃOS', 'resultados da ativação sem pergunta', 0,
        (SELECT count(*) FROM pesquisa_resultado r JOIN pesquisa p ON p.id = r.pesquisa_id WHERE p.escopo = 'ATIVACAO' AND (r.pergunta_id IS NULL OR r.parceria_id IS NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'resultados cuja pergunta é de outra parceria ou de outra pesquisa', 0,
        (SELECT count(*) FROM pesquisa_resultado r JOIN pesquisa_pergunta q ON q.id = r.pergunta_id
          WHERE q.parceria_id <> r.parceria_id OR q.pesquisa_id IS DISTINCT FROM r.pesquisa_id));
    PERFORM pg_temp.confere('ÓRFÃOS', 'resultados da pesquisa geral presos a parceria ou pergunta', 0,
        (SELECT count(*) FROM pesquisa_resultado r JOIN pesquisa p ON p.id = r.pesquisa_id WHERE p.escopo = 'GERAL' AND (r.parceria_id IS NOT NULL OR r.pergunta_id IS NOT NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'perguntas sem resultado carregado (Amarelo, 3 perguntas)', 0,
        (SELECT count(*) FROM pesquisa_pergunta q WHERE NOT EXISTS (SELECT 1 FROM pesquisa_resultado r WHERE r.pergunta_id = q.id)));

    -- [PERMISSAO] Acessos coerentes
    PERFORM pg_temp.confere('ÓRFÃOS', 'Admin RBS vinculado a uma parceria (perfil incoerente)', 0,
        (SELECT count(*) FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id WHERE u.perfil <> 'ADMIN_PATROCINADOR'));
    PERFORM pg_temp.confere('ÓRFÃOS', 'vínculos sem e-mail autorizado e vigente', 0,
        (SELECT count(*) FROM parceria_usuario pu JOIN usuario u ON u.id = pu.usuario_id
          WHERE NOT EXISTS (SELECT 1 FROM email_autorizado ea WHERE ea.parceria_id = pu.parceria_id AND lower(ea.email) = lower(u.email) AND ea.revogado_em IS NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'parcerias sem as 7 abas', 0,
        (SELECT count(*) FROM parceria pa WHERE (SELECT count(*) FROM parceria_aba x WHERE x.parceria_id = pa.id) <> 7));
    PERFORM pg_temp.confere('ÓRFÃOS', 'fotos de comprovação de outra parceria ou fora da categoria COMPROVACAO', 0,
        (SELECT count(*) FROM comprovacao_marca c JOIN aproveitamento_previsto ap ON ap.id = c.aproveitamento_id JOIN arquivo a ON a.id = c.arquivo_id
          WHERE ap.parceria_id <> a.parceria_id OR a.categoria <> 'COMPROVACAO'));
    PERFORM pg_temp.confere('ÓRFÃOS', 'destinações de cortesia acima da cota', 0,
        (SELECT count(*) FROM cortesia_cota cc WHERE (SELECT coalesce(sum(quantidade), 0) FROM cortesia_destinacao d WHERE d.cortesia_cota_id = cc.id) > cc.quantidade));
    PERFORM pg_temp.confere('ÓRFÃOS', 'veiculações repetidas (mesma linha do plano e mesma data/hora)', 0,
        (SELECT count(*) FROM (SELECT 1 FROM veiculacao GROUP BY aproveitamento_id, exibido_em HAVING count(*) > 1) x));
    PERFORM pg_temp.confere('ÓRFÃOS', 'histórico sem usuário que não seja falha de login', 0,
        (SELECT count(*) FROM historico WHERE usuario_id IS NULL AND acao <> 'LOGIN_FALHOU'));
END $$;

-- ---------------------------------------------------------------------
-- 6. Tentativas de violar as regras (cada uma deve ser recusada com SQLSTATE da classe 23 e o nome da regra; nada fica gravado)
-- ---------------------------------------------------------------------
-- Atalhos para os comandos de teste: a parceria de cada cliente em 2027 e o usuário de cada e-mail
CREATE OR REPLACE TEMP VIEW _ver_parc AS
SELECT p.nome_canonico AS cliente, e.edicao, pa.id
FROM parceria pa JOIN patrocinador p ON p.id = pa.patrocinador_id JOIN evento e ON e.id = pa.evento_id;
CREATE OR REPLACE TEMP VIEW _ver_usr AS SELECT email, id FROM usuario;

-- [ITEM-3] [ISOLAMENTO] Arquivo, entrega, pergunta, resultado e demais arquivos sempre da mesma parceria
DO $$
BEGIN
    PERFORM pg_temp.recusa('ITEM-3', 'arquivo do Roxo preso a uma entrega do Amarelo', 'fk_arquivo_entrega_parceria', $q$
        INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), e.aba_id, e.id, 'OUTRO', 'x.pdf', 'application/pdf', 10,
               'teste/a1.pdf', 9, (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')
        FROM entrega e WHERE e.titulo = 'Logo final para aplicações no evento' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'arquivo com entrega mas sem parceria (a chave composta não valeria com nulo)', 'ck_arquivo_entrega_completo', $q$
        INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por)
        SELECT NULL, e.aba_id, e.id, 'OUTRO', 'x.pdf', 'application/pdf', 10, 'teste/a2.pdf', (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')
        FROM entrega e WHERE e.titulo = 'Logo final para aplicações no evento' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'arquivo numa aba diferente da aba da entrega', 'fk_arquivo_entrega_aba', $q$
        INSERT INTO arquivo (parceria_id, aba_id, entrega_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, versao, enviado_por)
        SELECT e.parceria_id, (SELECT id FROM aba WHERE codigo = 'MIDIA'), e.id, 'OUTRO', 'x.pdf', 'application/pdf', 10, 'teste/a3.pdf', 9,
               (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')
        FROM entrega e WHERE e.titulo = 'Logo final para aplicações no evento' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'resultado do Roxo com a pergunta do Amarelo', 'fk_resultado_pergunta_parceria', $q$
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT q.pesquisa_id, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), q.id, 'Pergunta 1', 'Teste', 1, 'NOTA', 10
        FROM pesquisa_pergunta q WHERE q.ordem = 1 $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'resultado numa pesquisa diferente da pesquisa da pergunta', 'fk_resultado_pergunta_pesquisa', $q$
        WITH outra AS (INSERT INTO pesquisa (evento_id, nome, escopo) SELECT evento_id, 'Outra pesquisa de teste', 'ATIVACAO' FROM pesquisa WHERE escopo = 'ATIVACAO' RETURNING id, evento_id)
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT outra.id, q.parceria_id, q.id, 'Pergunta 1', 'Teste', 1, 'NOTA', 10 FROM outra, pesquisa_pergunta q WHERE q.ordem = 1 $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'resultado da ativação sem pergunta', 'rn_resultado_escopo', $q$
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT id, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'Pergunta 9', 'Teste', 1, 'NOTA', 10
        FROM pesquisa WHERE escopo = 'ATIVACAO' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'pergunta de uma parceria presa à pesquisa geral do festival', 'rn_pergunta_pesquisa', $q$
        INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), id, 1, 'Pergunta de teste' FROM pesquisa WHERE escopo = 'GERAL' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'foto de comprovação do Amarelo com arquivo da Verde', 'fk_comprovacao_arquivo_parceria', $q$
        INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, registrado_por)
        SELECT (SELECT ap.id FROM aproveitamento_previsto ap WHERE ap.formato = 'Elemento na Arena'),
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) LIMIT 1),
               (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example') $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'foto de comprovação com arquivo que não é da categoria COMPROVACAO', 'rn_comprovacao_categoria', $q$
        INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, registrado_por)
        SELECT (SELECT ap.id FROM aproveitamento_previsto ap WHERE ap.formato = 'Elemento na Arena'),
               (SELECT a.id FROM arquivo a WHERE a.nome_original = 'logo_final.pdf' LIMIT 1), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example') $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'foto de comprovação para uma linha de mídia', 'rn_comprovacao_so_aplicacao', $q$
        INSERT INTO comprovacao_marca (aproveitamento_id, arquivo_id, registrado_por)
        SELECT (SELECT ap.id FROM aproveitamento_previsto ap WHERE ap.formato = 'Chamadas assinadas 5"' LIMIT 1),
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' LIMIT 1), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example') $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'contrato do Roxo com o arquivo de contrato do Amarelo', 'fk_contrato_arquivo_parceria', $q$
        UPDATE contrato SET arquivo_id = (SELECT id FROM arquivo WHERE categoria = 'CONTRATO')
        WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'relatório do Roxo com um arquivo do Amarelo', 'fk_relatorio_arquivo_parceria', $q$
        UPDATE relatorio SET arquivo_id = (SELECT id FROM arquivo WHERE nome_original = 'logo_final.pdf' LIMIT 1)
        WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'fonte da pesquisa que seja arquivo de uma parceria (deve ser geral da RBS)', 'rn_pesquisa_fonte_geral', $q$
        UPDATE pesquisa SET fonte_arquivo_id = (SELECT id FROM arquivo WHERE nome_original = 'logo_final.pdf' LIMIT 1) WHERE escopo = 'ATIVACAO' $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'linha do plano do Roxo citando o PDF do plano do Amarelo', 'fk_aprov_fonte_parceria', $q$
        UPDATE aproveitamento_previsto SET fonte_arquivo_id = (SELECT id FROM arquivo WHERE categoria = 'PLANO_COMERCIAL'
                AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027))
        WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-3', 'comprovante de veiculação do Roxo com arquivo do Amarelo', 'rn_veiculacao_comprovante_parceria', $q$
        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, comprovante_arquivo_id)
        SELECT ap.id, timestamptz '2030-01-01 10:00-03', 1, (SELECT id FROM arquivo WHERE nome_original = 'logo_final.pdf' LIMIT 1)
        FROM aproveitamento_previsto ap WHERE ap.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027) LIMIT 1 $q$);
END $$;

-- [ITEM-8] [PERMISSAO] Só Admin Patrocinador é vinculado a uma parceria. O e-mail do Admin RBS é autorizado antes, de propósito:
-- assim o que recusa o vínculo é a chave composta (usuario_id, perfil), e não a falta de autorização.
DO $$
BEGIN
    PERFORM pg_temp.recusa('ITEM-8', 'Admin RBS vinculado a uma parceria', 'fk_parceria_usuario_so_admin_patrocinador', $q$
        INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), 'b2b@rbs.example', 'MARCA', (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example');
        INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), id, 'MARCA' FROM _ver_usr WHERE email = 'b2b@rbs.example' $q$);
    PERFORM pg_temp.recusa('ITEM-8', 'vínculo gravado com o perfil ADMIN_RBS', 'ck_parceria_usuario_perfil', $q$
        INSERT INTO email_autorizado (parceria_id, email, vinculo, autorizado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), 'b2b@rbs.example', 'MARCA', (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example');
        INSERT INTO parceria_usuario (parceria_id, usuario_id, perfil, vinculo)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), id, 'ADMIN_RBS', 'MARCA' FROM _ver_usr WHERE email = 'b2b@rbs.example' $q$);
    PERFORM pg_temp.recusa('ITEM-8', 'trocar para ADMIN_RBS o perfil de quem já está vinculado', 'fk_parceria_usuario_so_admin_patrocinador', $q$
        UPDATE usuario SET perfil = 'ADMIN_RBS' WHERE email = 'marketing@verde.example' $q$);
END $$;

-- Regras que a modelagem já garantia e continuam valendo: uma cota por marca por edição, um signatário por contrato,
-- chave da veiculação sem a janela de exibição
DO $$
BEGIN
    PERFORM pg_temp.recusa('GARANTIDAS', 'duas cotas para a mesma marca na mesma edição', 'uq_parceria', $q$
        INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id, situacao)
        SELECT pa.evento_id, pa.patrocinador_id, 3, 'ATIVA' FROM parceria pa WHERE pa.id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('GARANTIDAS', 'dois contratos (dois signatários) para a mesma parceria', 'contrato_parceria_id_key', $q$
        INSERT INTO contrato (parceria_id, signatario_nome) SELECT parceria_id, 'Outro signatário' FROM contrato LIMIT 1 $q$);
    PERFORM pg_temp.recusa('GARANTIDAS', 'mesma veiculação (linha do plano + data/hora) contada duas vezes', 'uq_veiculacao', $q$
        INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade) SELECT aproveitamento_id, exibido_em, 1 FROM veiculacao LIMIT 1 $q$);
    PERFORM pg_temp.recusa('GARANTIDAS', 'mesma linha do plano com outra janela (a janela fica fora da chave)', 'uq_aprov_chave_natural', $q$
        INSERT INTO aproveitamento_previsto (parceria_id, categoria, veiculo, formato, espaco_programa, janela, tipo_midia, quantidade_prevista, unidade)
        SELECT parceria_id, categoria, veiculo, formato, espaco_programa, 'outra janela', tipo_midia, quantidade_prevista, unidade
        FROM aproveitamento_previsto WHERE categoria = 'MIDIA' LIMIT 1 $q$);
    PERFORM pg_temp.recusa('GARANTIDAS', 'mesma pergunta (ordem) duas vezes na parceria', 'uq_pergunta', $q$
        INSERT INTO pesquisa_pergunta (parceria_id, pesquisa_id, ordem, texto) SELECT parceria_id, pesquisa_id, ordem, 'Repetida' FROM pesquisa_pergunta LIMIT 1 $q$);
    PERFORM pg_temp.recusa('GARANTIDAS', 'resultado de pesquisa repetido (reenvio não duplica)', 'uq_pesquisa_resultado', $q$
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes FROM pesquisa_resultado WHERE pergunta_id IS NOT NULL LIMIT 1 $q$);
END $$;

-- [PERMISSAO] [ITEM-1] E-mails autorizados e código de primeiro acesso (só o hash, com validade e uso único)
DO $$
BEGIN
    PERFORM pg_temp.recusa('PERMISSÃO', 'trocar o e-mail de uma autorização (revoga-se e autoriza-se outro)', 'rn_email_autorizado_imutavel', $q$
        UPDATE email_autorizado SET email = 'outro@roxo.example' WHERE email = 'marketing@roxo.example' $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'revogar sem dizer quem revogou', 'ck_revogacao_completa', $q$
        UPDATE email_autorizado SET revogado_em = now() WHERE email = 'marketing@roxo.example' $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'vincular usuário cujo e-mail não está autorizado na parceria', 'rn_vinculo_exige_autorizacao', $q$
        INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), id, 'MARCA' FROM _ver_usr WHERE email = 'marketing@verde.example' $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'vincular usuário depois de revogado o e-mail (sem reautorizar)', 'rn_vinculo_exige_autorizacao', $q$
        UPDATE email_autorizado SET revogado_em = now(), revogado_por = (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example') WHERE email = 'marketing@verde.example';
        INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027), id, 'MARCA' FROM _ver_usr WHERE email = 'marketing@verde.example' $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'código de ativação guardado em claro (só o hash SHA-256 em hexadecimal)', 'codigo_ativacao_codigo_hash_check', $q$
        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em)
        SELECT id, 'ABC123', now() + interval '1 day' FROM email_autorizado LIMIT 1 $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'dois códigos vigentes para a mesma autorização', 'uq_codigo_vigente', $q$
        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em)
        SELECT ea.id, h.hash, now() + interval '1 day'
        FROM (SELECT id FROM email_autorizado LIMIT 1) ea, (VALUES (repeat('a', 64)), (repeat('b', 64))) AS h(hash) $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'código que vence antes de nascer', 'ck_codigo_validade', $q$
        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, criado_em, expira_em)
        SELECT id, repeat('c', 64), now(), now() - interval '1 hour' FROM email_autorizado LIMIT 1 $q$);
    PERFORM pg_temp.recusa('PERMISSÃO', 'código ao mesmo tempo usado e invalidado (uso único)', 'ck_codigo_desfecho', $q$
        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em, usado_em, invalidado_em)
        SELECT id, repeat('d', 64), now() + interval '1 day', now(), now() FROM email_autorizado LIMIT 1 $q$);
END $$;

-- [ITEM-5] [HISTORICO] O histórico aceita as ações novas (com user_agent) e só LOGIN_FALHOU pode ficar sem usuário e sem entidade
DO $$
BEGIN
    PERFORM pg_temp.aceita('ITEM-5', 'LOGIN, LOGOUT, DOWNLOAD, AUTORIZACAO, REVOGACAO, ATIVACAO e CARGA com usuário e user_agent', $q$
        INSERT INTO historico (entidade, entidade_id, acao, usuario_id, ip, user_agent)
        SELECT 'usuario', u.id, a.acao, u.id, '200.0.2.10', 'Mozilla/5.0 (teste)'
        FROM _ver_usr u, (VALUES ('LOGIN'), ('LOGOUT'), ('DOWNLOAD'), ('AUTORIZACAO'), ('REVOGACAO'), ('ATIVACAO'), ('CARGA')) AS a(acao)
        WHERE u.email = 'b2b@rbs.example' $q$);
    PERFORM pg_temp.aceita('ITEM-5', 'LOGIN_FALHOU sem usuário e sem entidade_id, com o e-mail tentado no detalhe', $q$
        INSERT INTO historico (entidade, acao, detalhe, ip, user_agent) VALUES ('usuario', 'LOGIN_FALHOU', 'e-mail tentado: ninguem@exemplo.example', '200.0.2.10', 'Mozilla/5.0 (teste)') $q$);
    PERFORM pg_temp.aceita('ITEM-5', 'VALIDACAO e DISPONIBILIZACAO do relatório registradas no histórico', $q$
        INSERT INTO historico (parceria_id, entidade, entidade_id, acao, usuario_id)
        SELECT r.parceria_id, 'relatorio', r.id, a.acao, (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')
        FROM relatorio r, (VALUES ('VALIDACAO'), ('DISPONIBILIZACAO')) AS a(acao) LIMIT 2 $q$);
    PERFORM pg_temp.recusa('ITEM-5', 'ação sem usuário que não seja LOGIN_FALHOU', 'ck_historico_usuario', $q$
        INSERT INTO historico (entidade, entidade_id, acao) VALUES ('usuario', 1, 'LOGIN') $q$);
    PERFORM pg_temp.recusa('ITEM-5', 'LOGIN_FALHOU sem usuário e sem o e-mail tentado no detalhe', 'ck_historico_usuario', $q$
        INSERT INTO historico (entidade, acao) VALUES ('usuario', 'LOGIN_FALHOU') $q$);
    PERFORM pg_temp.recusa('ITEM-5', 'LOGIN sem entidade_id', 'ck_historico_entidade', $q$
        INSERT INTO historico (entidade, acao, usuario_id) SELECT 'usuario', 'LOGIN', id FROM _ver_usr WHERE email = 'b2b@rbs.example' $q$);
    PERFORM pg_temp.recusa('ITEM-5', 'ação que não existe na lista', 'ck_historico_acao', $q$
        INSERT INTO historico (entidade, entidade_id, acao, usuario_id) SELECT 'usuario', id, 'HACKEAR', id FROM _ver_usr WHERE email = 'b2b@rbs.example' $q$);
    -- O histórico é imutável: só INSERT. Parceria com histórico não se apaga (encerra-se pela situação).
    PERFORM pg_temp.recusa('HISTÓRICO', 'UPDATE em uma linha do histórico', 'rn_historico_imutavel', $q$
        UPDATE historico SET valor_novo = 'adulterado' WHERE id = (SELECT min(id) FROM historico) $q$);
    PERFORM pg_temp.recusa('HISTÓRICO', 'DELETE de uma linha do histórico', 'rn_historico_imutavel', $q$
        DELETE FROM historico WHERE id = (SELECT min(id) FROM historico) $q$);
    PERFORM pg_temp.recusa('HISTÓRICO', 'apagar uma parceria que tem histórico', 'historico_parceria_id_fkey', $q$
        DELETE FROM parceria WHERE id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
END $$;

-- [ITEM-6] A destinação de cortesias não passa da cota (Amarelo, Arena de sexta: 25 de 25; Verde, Arena de sábado: 2 de 3)
DO $$
BEGIN
    PERFORM pg_temp.recusa('ITEM-6', 'mais uma cortesia além da cota cheia (25 de 25)', 'rn_cortesia_limite', $q$
        INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
        SELECT cc.id, 'Convidado extra', 1, (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
        FROM cortesia_cota cc WHERE cc.setor = 'ARENA' AND cc.dia = 'SEXTA' AND cc.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'destinar 522 de uma cota de 25', 'rn_cortesia_limite', $q$
        INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
        SELECT cc.id, 'Lote gigante', 522, (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
        FROM cortesia_cota cc WHERE cc.setor = 'ARENA' AND cc.dia = 'SEXTA' AND cc.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'aumentar uma destinação além da cota (Verde: 2 passam a 5 de 3)', 'rn_cortesia_limite', $q$
        UPDATE cortesia_destinacao SET quantidade = 5
        WHERE cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SABADO' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027)) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'mover uma destinação para uma cota que não comporta', 'rn_cortesia_limite', $q$
        UPDATE cortesia_destinacao SET cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SEXTA' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027))
        WHERE cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SABADO' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027)) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'reduzir a cota abaixo do que já foi destinado', 'rn_cortesia_cota_reducao', $q$
        UPDATE cortesia_cota SET quantidade = 20 WHERE setor = 'ARENA' AND dia = 'SEXTA' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
    PERFORM pg_temp.aceita('ITEM-6', 'destinar a cortesia que sobra da Verde (3 de 3)', $q$
        INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
        SELECT cc.id, 'Convidados finais', 1, (SELECT id FROM _ver_usr WHERE email = 'marketing@verde.example')
        FROM cortesia_cota cc WHERE cc.setor = 'ARENA' AND cc.dia = 'SABADO' AND cc.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.afirma('ITEM-6', 'o gatilho trava a linha da cota (SELECT ... FOR UPDATE) contra destinações simultâneas',
        pg_get_functiondef('tg_cortesia_destinacao_limite'::regproc) LIKE '%FOR UPDATE%');
END $$;

-- [ITEM-7] [RELATORIO] O relatório só segue RASCUNHO, VALIDADO, DISPONIBILIZADO, com quem fez cada etapa e quando
DO $$
BEGIN
    PERFORM pg_temp.recusa('ITEM-7', 'relatório que já nasce DISPONIBILIZADO', 'rn_relatorio_nasce_rascunho', $q$
        INSERT INTO relatorio (parceria_id, versao, status, gerado_por, validado_por, validado_em, disponibilizado_por, disponibilizado_em)
        SELECT parceria_id, 9, 'DISPONIBILIZADO', gerado_por, gerado_por, now(), gerado_por, now() FROM relatorio LIMIT 1 $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'pular de RASCUNHO direto para DISPONIBILIZADO', 'rn_relatorio_ordem_status', $q$
        UPDATE relatorio SET status = 'DISPONIBILIZADO', validado_por = gerado_por, validado_em = now(), disponibilizado_por = gerado_por, disponibilizado_em = now()
        WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'validar sem registrar quem validou e quando', 'ck_relatorio_validacao', $q$
        UPDATE relatorio SET status = 'VALIDADO' WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.aceita('ITEM-7', 'RASCUNHO, VALIDADO e DISPONIBILIZADO nessa ordem, com validado_* e disponibilizado_*', $q$
        UPDATE relatorio SET status = 'VALIDADO', validado_por = gerado_por, validado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = gerado_por, disponibilizado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'voltar de DISPONIBILIZADO para RASCUNHO', 'rn_relatorio_ordem_status', $q$
        UPDATE relatorio SET status = 'VALIDADO', validado_por = gerado_por, validado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        UPDATE relatorio SET status = 'DISPONIBILIZADO', disponibilizado_por = gerado_por, disponibilizado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        UPDATE relatorio SET status = 'RASCUNHO', validado_por = NULL, validado_em = NULL, disponibilizado_por = NULL, disponibilizado_em = NULL WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'trocar quem validou depois de validado', 'rn_relatorio_etapas_gravadas', $q$
        UPDATE relatorio SET status = 'VALIDADO', validado_por = gerado_por, validado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        UPDATE relatorio SET validado_por = (SELECT id FROM _ver_usr WHERE email = 'pesquisa@rbs.example') WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'mudar os números de um relatório já validado (cada versão é uma fotografia)', 'rn_relatorio_congelado', $q$
        UPDATE relatorio SET status = 'VALIDADO', validado_por = gerado_por, validado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        UPDATE relatorio_indicador SET valor = 1, disponivel = TRUE WHERE relatorio_id = (SELECT id FROM relatorio WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027))
          AND indicador_codigo = 'FESTIVAL_NUMERO' $q$);
    PERFORM pg_temp.recusa('ITEM-7', 'apagar um relatório já validado', 'rn_relatorio_nao_apaga', $q$
        UPDATE relatorio SET status = 'VALIDADO', validado_por = gerado_por, validado_em = now() WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027);
        DELETE FROM relatorio WHERE parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) $q$);
END $$;

-- ---------------------------------------------------------------------
-- 7. Comportamento esperado das correções 1, 2 e 4, e do reenvio da pesquisa
-- ---------------------------------------------------------------------
-- Cada bloco faz as alterações dentro de um sub-bloco que termina em ERRCODE ZZ001: o banco desfaz tudo e a amostra fica intacta.
-- As variáveis guardam os números lidos antes do desfazer.

-- [ITEM-1] [PERMISSAO] Revogar o e-mail remove o acesso e invalida o código pendente; reautorizar volta a funcionar
DO $$
DECLARE
    v_roxo BIGINT; v_ea BIGINT;
    n_antes INT; n_revogado INT; n_codigo_revogado INT; n_reautorizado INT; n_codigo_depois INT; n_acesso_perdido_historico INT;
BEGIN
    SELECT id INTO v_roxo FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027;
    SELECT id INTO v_ea FROM email_autorizado WHERE parceria_id = v_roxo AND email = 'marketing@roxo.example';
    BEGIN
        SELECT count(*) INTO n_antes FROM parceria_usuario WHERE parceria_id = v_roxo;
        INSERT INTO codigo_ativacao (email_autorizado_id, codigo_hash, expira_em) VALUES (v_ea, repeat('e', 64), now() + interval '1 day');
        UPDATE email_autorizado SET revogado_em = now(), revogado_por = (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example') WHERE id = v_ea;
        SELECT count(*) INTO n_revogado FROM parceria_usuario WHERE parceria_id = v_roxo;
        SELECT count(*) INTO n_codigo_revogado FROM codigo_ativacao WHERE email_autorizado_id = v_ea AND usado_em IS NULL AND invalidado_em IS NULL;
        UPDATE email_autorizado SET revogado_em = NULL, revogado_por = NULL WHERE id = v_ea;
        SELECT count(*) INTO n_reautorizado FROM parceria_usuario WHERE parceria_id = v_roxo;
        SELECT count(*) INTO n_codigo_depois FROM codigo_ativacao WHERE email_autorizado_id = v_ea AND usado_em IS NULL AND invalidado_em IS NULL;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.confere('ITEM-1', 'Roxo tem 1 usuário vinculado antes de revogar', 1, n_antes);
    PERFORM pg_temp.confere('ITEM-1', 'revogar o e-mail remove o vínculo (acesso) da parceria', 0, n_revogado);
    PERFORM pg_temp.confere('ITEM-1', 'revogar invalida o código de ativação pendente', 0, n_codigo_revogado);
    PERFORM pg_temp.confere('ITEM-1', 'reautorizar o mesmo e-mail devolve o acesso', 1, n_reautorizado);
    PERFORM pg_temp.confere('ITEM-1', 'o código invalidado não volta a valer (gera-se um novo)', 0, n_codigo_depois);
END $$;

-- [ITEM-2] [RELATORIO] Rótulos longos não derrubam mais a geração (resultado de pesquisa com alternativa de 120 caracteres e
-- número do festival com fonte de 200 caracteres; antes os limites eram 80 e 120)
DO $$
DECLARE v_amarelo BIGINT; v_rel BIGINT; v_recorte INT; v_base INT; v_gerou BOOLEAN := FALSE;
BEGIN
    SELECT id INTO v_amarelo FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027;
    BEGIN
        INSERT INTO evento_resultado (evento_id, indicador, valor, unidade, fonte)
        SELECT id, 'Indicador de teste', 1, 'pessoas', repeat('f', 200) FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT q.pesquisa_id, q.parceria_id, q.id, 'Pergunta 1', repeat('a', 120), 1, 'NOTA', 10 FROM pesquisa_pergunta q WHERE q.parceria_id = v_amarelo AND q.ordem = 1;
        v_rel := fn_gerar_relatorio(v_amarelo, (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'));
        v_gerou := TRUE;
        SELECT max(length(recorte)), max(length(base_calculo)) INTO v_recorte, v_base FROM relatorio_indicador WHERE relatorio_id = v_rel;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('ITEM-2', 'o relatório com rótulos longos é gerado', v_gerou);
    PERFORM pg_temp.confere('ITEM-2', 'recorte de 125 caracteres gravado inteiro (P1 · + alternativa de 120)', 125, v_recorte);
    PERFORM pg_temp.confere('ITEM-2', 'base de cálculo de 200 caracteres gravada inteira (fonte do festival)', 200, v_base);
    PERFORM pg_temp.afirma('ITEM-2', 'recorte e base_calculo são TEXT',
        (SELECT count(*) = 2 FROM information_schema.columns WHERE table_name = 'relatorio_indicador' AND column_name IN ('recorte', 'base_calculo') AND data_type = 'text'));
END $$;

-- [ITEM-4] [CARGA] As funções de carga rodam várias vezes na mesma transação (antes: "tmp_vinculo already exists");
-- chamar de novo para a mesma carga devolve os totais gravados. Reenvio da pesquisa não duplica; vale também para a pesquisa geral.
DO $$
DECLARE
    v_ev BIGINT; v_b2b BIGINT; v_a BIGINT; v_b BIGINT; v_ativ BIGINT; v_geral BIGINT; v_c BIGINT; v_g BIGINT;
    r1 RECORD; r2 RECORD; r3 RECORD; r4 RECORD; r5 RECORD; n_antes INT; n_depois INT; n_depois_geral INT;
BEGIN
    SELECT id INTO v_ev FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;
    SELECT id INTO v_b2b FROM _ver_usr WHERE email = 'b2b@rbs.example';
    SELECT id INTO v_ativ FROM pesquisa WHERE escopo = 'ATIVACAO';
    SELECT id INTO v_geral FROM pesquisa WHERE escopo = 'GERAL';
    BEGIN
        -- duas cargas de mídia com a mesma exibição (nova), processadas uma depois da outra na mesma transação
        INSERT INTO carga (tipo, executada_por) VALUES ('MIDIA', v_b2b) RETURNING id INTO v_a;
        INSERT INTO carga (tipo, executada_por) VALUES ('MIDIA', v_b2b) RETURNING id INTO v_b;
        INSERT INTO stg_veiculacao (carga_id, linha, patrocinador, veiculo, formato, espaco_programa, exibido_em, quantidade) VALUES
         (v_a, 1, 'Cliente Verde', 'No evento', 'VT telão 30"', 'Telões (1 por dia)', timestamptz '2030-01-01 10:00:30-03', 1),
         (v_b, 1, 'Cliente Verde', 'No evento', 'VT telão 30"', 'Telões (1 por dia)', timestamptz '2030-01-01 10:00:30-03', 1);
        SELECT * INTO r1 FROM fn_carregar_veiculacoes(v_a, v_ev);
        SELECT * INTO r2 FROM fn_carregar_veiculacoes(v_b, v_ev);
        SELECT * INTO r3 FROM fn_carregar_veiculacoes(v_a, v_ev);

        -- reenvio da pesquisa da ativação: as mesmas 11 linhas da carga de pesquisa da amostra
        INSERT INTO carga (tipo, executada_por) VALUES ('PESQUISA', v_b2b) RETURNING id INTO v_c;
        INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes)
        SELECT v_c, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes
        FROM stg_pesquisa WHERE carga_id = (SELECT min(id) FROM carga WHERE tipo = 'PESQUISA');
        SELECT count(*) INTO n_antes FROM pesquisa_resultado;
        SELECT * INTO r4 FROM fn_carregar_pesquisa(v_c, v_ativ);
        SELECT count(*) INTO n_depois FROM pesquisa_resultado;

        -- pesquisa geral do festival: 1 repetida, 1 nova, 1 com patrocinador (a pesquisa geral não tem patrocinador)
        INSERT INTO carga (tipo, executada_por) VALUES ('PESQUISA', v_b2b) RETURNING id INTO v_g;
        INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, indicador, alternativa, valor, unidade, base_respondentes) VALUES
         (v_g, 1, NULL, NULL, 'Presença das marcas no festival', 'Importante ou muito importante', 86, 'PERCENTUAL', 1038),
         (v_g, 2, NULL, NULL, 'Faixa etária', '18 a 24 anos', 34, 'PERCENTUAL', 1038),
         (v_g, 3, 'Cliente Amarelo', 1, 'Qualquer', 'x', 1, 'NOTA', 1038);
        SELECT * INTO r5 FROM fn_carregar_pesquisa(v_g, v_geral);
        SELECT count(*) INTO n_depois_geral FROM pesquisa_resultado WHERE pesquisa_id = v_geral AND parceria_id IS NULL;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('ITEM-4', 'veiculação: 1ª chamada insere a linha nova (1 lida, 1 inserida)', r1.lidas = 1 AND r1.inseridas = 1 AND r1.duplicadas = 0 AND r1.rejeitadas = 0);
    PERFORM pg_temp.afirma('ITEM-4', 'veiculação: 2ª chamada na mesma transação funciona e conta a duplicada', r2.lidas = 1 AND r2.inseridas = 0 AND r2.duplicadas = 1 AND r2.rejeitadas = 0);
    PERFORM pg_temp.afirma('ITEM-4', 'veiculação: processar a mesma carga de novo devolve os totais gravados, sem refazer', r3.lidas = r1.lidas AND r3.inseridas = r1.inseridas);
    PERFORM pg_temp.afirma('REENVIO PESQUISA', 'o mesmo arquivo reenviado: 11 lidas, 0 inseridas, 10 duplicadas, 1 rejeitada',
        r4.lidas = 11 AND r4.inseridas = 0 AND r4.duplicadas = 10 AND r4.rejeitadas = 1);
    PERFORM pg_temp.confere('REENVIO PESQUISA', 'resultados na base antes e depois do reenvio (não duplica)', n_antes, n_depois);
    PERFORM pg_temp.afirma('PESQUISA GERAL', 'pesquisa geral: 3 lidas, 1 inserida, 1 duplicada, 1 rejeitada (patrocinador não vale)',
        r5.lidas = 3 AND r5.inseridas = 1 AND r5.duplicadas = 1 AND r5.rejeitadas = 1);
    PERFORM pg_temp.confere('PESQUISA GERAL', 'resultados gerais do festival depois da carga (5 + 1 nova)', 6, n_depois_geral);
END $$;

-- [CARGA] Resultado só para pergunta aprovada pela RBS: com a pergunta apenas ENVIADA, a linha é rejeitada com o motivo; aprovada, entra
DO $$
DECLARE v_b2b BIGINT; v_c1 BIGINT; v_c2 BIGINT; r1 RECORD; r2 RECORD; v_motivo TEXT;
BEGIN
    SELECT id INTO v_b2b FROM _ver_usr WHERE email = 'b2b@rbs.example';
    BEGIN
        UPDATE pesquisa_pergunta SET status = 'ENVIADA' WHERE ordem = 1;
        INSERT INTO carga (tipo, executada_por) VALUES ('PESQUISA', v_b2b) RETURNING id INTO v_c1;
        INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, alternativa, valor, unidade, base_respondentes)
        VALUES (v_c1, 1, 'Cliente Amarelo', 1, 'Talvez', 5, 'PERCENTUAL', 412);
        SELECT * INTO r1 FROM fn_carregar_pesquisa(v_c1, (SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'));
        SELECT motivo INTO v_motivo FROM carga_rejeicao WHERE carga_id = v_c1;
        UPDATE pesquisa_pergunta SET status = 'APROVADA' WHERE ordem = 1;
        INSERT INTO carga (tipo, executada_por) VALUES ('PESQUISA', v_b2b) RETURNING id INTO v_c2;
        INSERT INTO stg_pesquisa (carga_id, linha, patrocinador, pergunta, alternativa, valor, unidade, base_respondentes)
        VALUES (v_c2, 1, 'Cliente Amarelo', 1, 'Talvez', 5, 'PERCENTUAL', 412);
        SELECT * INTO r2 FROM fn_carregar_pesquisa(v_c2, (SELECT id FROM pesquisa WHERE escopo = 'ATIVACAO'));
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('PESQUISA', 'pergunta só ENVIADA: resultado rejeitado (1 lida, 0 inseridas, 1 rejeitada)', r1.lidas = 1 AND r1.inseridas = 0 AND r1.rejeitadas = 1);
    PERFORM pg_temp.afirma('PESQUISA', 'motivo da rejeição: "Pergunta ainda não aprovada pela RBS"', v_motivo = 'Pergunta ainda não aprovada pela RBS');
    PERFORM pg_temp.afirma('PESQUISA', 'depois de aprovada pela RBS, o mesmo resultado entra (1 inserida)', r2.inseridas = 1 AND r2.rejeitadas = 0);
END $$;

-- [ITEM-3] Apagar uma entrega solta só o vínculo do arquivo (SET NULL só em entrega_id): o arquivo continua da parceria
DO $$
DECLARE n_com_parceria INT; n_geral INT;
BEGIN
    BEGIN
        DELETE FROM entrega WHERE titulo = 'Logo final para aplicações no evento';
        SELECT count(*) INTO n_com_parceria FROM arquivo WHERE nome_original = 'logo_final.pdf' AND entrega_id IS NULL AND parceria_id IS NOT NULL AND aba_id IS NOT NULL;
        SELECT count(*) INTO n_geral FROM arquivo WHERE nome_original = 'logo_final.pdf' AND parceria_id IS NULL;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.confere('ITEM-3', 'apagar a entrega deixa as 2 versões do logo na parceria, sem entrega', 2, n_com_parceria);
    PERFORM pg_temp.confere('ITEM-3', 'nenhuma versão do logo virou arquivo "geral da RBS" por engano', 0, n_geral);
END $$;

-- ---------------------------------------------------------------------
-- 8. V7: plano comercial por CSV, catálogo de campos e dados informados
-- ---------------------------------------------------------------------
-- O catálogo que a V7 carrega em todos os ambientes: os quatro inteiros da aba Aplicações e os dois limites entre campos
DO $$
BEGIN
    PERFORM pg_temp.confere('V7 CATÁLOGO', 'campos da aba Aplicações (inteiros a partir de 0, sem máximo)', 4,
        (SELECT count(*) FROM campo_catalogo c JOIN aba a ON a.id = c.aba_id
          WHERE a.codigo = 'APLICACOES' AND c.tipo = 'INTEIRO' AND c.minimo = 0 AND c.maximo IS NULL));
    PERFORM pg_temp.afirma('V7 CATÁLOGO', 'cadastros não passam de visitantes e brindes distribuídos não passam dos disponíveis (limite_campo)',
        (SELECT limite_campo FROM campo_catalogo WHERE chave = 'ativacao.cadastros_consentidos') = 'ativacao.visitantes_total'
        AND (SELECT limite_campo FROM campo_catalogo WHERE chave = 'ativacao.brindes_distribuidos') = 'ativacao.brindes_disponiveis'
        AND (SELECT count(*) FROM campo_catalogo WHERE limite_campo IS NOT NULL) = 2);
    PERFORM pg_temp.recusa('V7 CATÁLOGO', 'campo que limita a si mesmo', 'ck_campo_limite_proprio', $q$
        UPDATE campo_catalogo SET limite_campo = chave WHERE chave = 'ativacao.brindes_disponiveis' $q$);
END $$;

-- [CARGA] [VALIDACAO] [DUPLICIDADE] A carga do plano: uma parceria nova, 15 linhas (3 entram, 2 são duplicadas, 10 são rejeitadas, cada
-- uma por um motivo), o reenvio do mesmo arquivo e o texto gravado sem espaços nas pontas. Tudo dentro do sub-bloco desfeito no fim.
DO $$
DECLARE
    v_ev BIGINT; v_b2b BIGINT; v_pat BIGINT; v_pa BIGINT; v_c1 BIGINT; v_c2 BIGINT;
    r1 RECORD; r1b RECORD; r2 RECORD;
    n_plano INT; n_depois_do_reenvio INT; n_sem_btrim INT; n_aplicacao_com_tipo INT; v_qtd NUMERIC; v_marcada BOOLEAN;
    v_motivos TEXT[]; v_conteudo TEXT;
BEGIN
    SELECT id INTO v_ev FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = 2027;
    SELECT id INTO v_b2b FROM _ver_usr WHERE email = 'b2b@rbs.example';
    BEGIN
        INSERT INTO patrocinador (nome_canonico) VALUES ('Cliente Teste V7') RETURNING id INTO v_pat;
        INSERT INTO patrocinador_alias (patrocinador_id, alias) VALUES (v_pat, 'Teste V7');
        INSERT INTO parceria (evento_id, patrocinador_id, cota_tipo_id) VALUES (v_ev, v_pat, 3) RETURNING id INTO v_pa;
        INSERT INTO carga (tipo, executada_por) VALUES ('PLANO_COMERCIAL', v_b2b) RETURNING id INTO v_c1;
        INSERT INTO stg_plano (carga_id, linha, patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                               quantidade_prevista, quantidade_texto, unidade) VALUES
         -- entram: uma mídia, uma aplicação pelo apelido da marca e uma aplicação sem espaço (textos com espaço nas pontas)
         (v_c1, 1,  'Cliente Teste V7',   'MIDIA',           'Transmissão',         'Rede Atlantida RS', 'Chamadas assinadas 5"',   'Programa da Manha', 'Out/2027', 'ASSINADA', 10, NULL, 'INSERCAO'),
         (v_c1, 2,  'Teste V7',           'APLICACAO_MARCA', 'Aplicações de marca', 'No evento',         'Testeira no palco',        NULL, NULL, NULL, 2, NULL, 'APLICACAO'),
         (v_c1, 4,  'Cliente Teste V7',   'APLICACAO_MARCA', NULL,                  ' No evento ',       '  Elemento no Camarote ',  NULL, NULL, NULL, 1, NULL, 'APLICACAO'),
         -- duplicadas: a linha 1 com outra caixa e espaços, e a linha 4 com outra caixa e outra quantidade
         (v_c1, 3,  '  cliente teste v7 ', 'MIDIA',          NULL,                  ' REDE ATLANTIDA RS ', ' chamadas assinadas 5" ', ' programa da manha ', NULL, 'ASSINADA', 99, NULL, 'INSERCAO'),
         (v_c1, 5,  'Cliente Teste V7',   'APLICACAO_MARCA', NULL,                  'No evento',         'ELEMENTO NO CAMAROTE',     NULL, NULL, NULL, 5, NULL, 'APLICACAO'),
         -- rejeitadas, uma por motivo (linhas 6 a 15)
         (v_c1, 6,  'Cliente Teste V7',   'APLICACAO_MARCA', NULL,                  NULL,                'Elemento sem veículo',     NULL, NULL, NULL, 1, NULL, 'APLICACAO'),
         (v_c1, 7,  'Cliente Teste V7',   'MIDIA',           NULL,                  'Rádio 92',          'Chamadas',                 NULL, NULL, 'PROPRIA', NULL, '1,5', 'INSERCAO'),
         (v_c1, 8,  'Cliente Teste V7',   'MIDIA',           NULL,                  'Rádio 92',          'Vinhetas',                 NULL, NULL, 'PROPRIA', 0, NULL, 'INSERCAO'),
         (v_c1, 9,  'Cliente Teste V7',   'OUTRA',           NULL,                  'Rádio 92',          'Menções',                  NULL, NULL, 'PROPRIA', 1, NULL, 'INSERCAO'),
         (v_c1, 10, 'Cliente Teste V7',   'MIDIA',           NULL,                  'Rádio 92',          'Boletins',                 NULL, NULL, 'PROPRIA', 1, NULL, 'PACOTE'),
         (v_c1, 11, 'Cliente Teste V7',   'APLICACAO_MARCA', NULL,                  'No evento',         'Elemento na Arena',        NULL, NULL, NULL, 1, NULL, 'INSERCAO'),
         (v_c1, 12, 'Cliente Teste V7',   'MIDIA',           NULL,                  'Rádio 92',          'Programete',               NULL, NULL, NULL, 1, NULL, 'INSERCAO'),
         (v_c1, 13, 'Cliente Teste V7',   'APLICACAO_MARCA', NULL,                  'No evento',         'Elemento no Lounge',       NULL, NULL, 'FOO', 1, NULL, 'APLICACAO'),
         (v_c1, 14, 'Cliente Azul',       'MIDIA',           NULL,                  'Rádio 92',          'Chamadas',                 NULL, NULL, 'PROPRIA', 1, NULL, 'INSERCAO'),
         (v_c1, 15, 'Cliente Teste V7',   'MIDIA',           NULL,                  'Rádio 92',          'Selos',                    NULL, NULL, 'PROPRIA', NULL, NULL, 'INSERCAO');

        SELECT * INTO r1 FROM fn_carregar_plano(v_c1, v_ev);
        SELECT * INTO r1b FROM fn_carregar_plano(v_c1, v_ev);
        SELECT count(*) INTO n_plano FROM aproveitamento_previsto WHERE parceria_id = v_pa;
        SELECT quantidade_prevista INTO v_qtd FROM aproveitamento_previsto WHERE parceria_id = v_pa AND lower(veiculo) = 'rede atlantida rs';
        SELECT count(*) INTO n_sem_btrim FROM aproveitamento_previsto
         WHERE parceria_id = v_pa AND (veiculo <> btrim(veiculo) OR formato <> btrim(formato) OR espaco_programa <> btrim(espaco_programa));
        SELECT count(*) INTO n_aplicacao_com_tipo FROM aproveitamento_previsto
         WHERE parceria_id = v_pa AND categoria = 'APLICACAO_MARCA' AND tipo_midia IS NOT NULL;
        SELECT processada_em IS NOT NULL AND evento_id = v_ev AND linhas_lidas = 15 INTO v_marcada FROM carga WHERE id = v_c1;
        SELECT array_agg(campo || ': ' || motivo ORDER BY linha, id) INTO v_motivos FROM carga_rejeicao WHERE carga_id = v_c1;
        SELECT conteudo INTO v_conteudo FROM carga_rejeicao WHERE carga_id = v_c1 AND linha = 7;

        -- o mesmo arquivo reenviado, numa carga nova: nada entra de novo, as 5 válidas viram duplicadas e as 10 ruins seguem rejeitadas
        INSERT INTO carga (tipo, executada_por) VALUES ('PLANO_COMERCIAL', v_b2b) RETURNING id INTO v_c2;
        INSERT INTO stg_plano (carga_id, linha, patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                               quantidade_prevista, quantidade_texto, unidade, fonte_pagina)
        SELECT v_c2, linha, patrocinador, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
               quantidade_prevista, quantidade_texto, unidade, fonte_pagina FROM stg_plano WHERE carga_id = v_c1;
        SELECT * INTO r2 FROM fn_carregar_plano(v_c2, v_ev);
        SELECT count(*) INTO n_depois_do_reenvio FROM aproveitamento_previsto WHERE parceria_id = v_pa;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V7 PLANO', 'carga do plano: 15 lidas, 3 inseridas, 2 duplicadas, 10 rejeitadas',
        r1.lidas = 15 AND r1.inseridas = 3 AND r1.duplicadas = 2 AND r1.rejeitadas = 10);
    PERFORM pg_temp.afirma('V7 PLANO', 'lidas = inseridas + duplicadas + rejeitadas', r1.lidas = r1.inseridas + r1.duplicadas + r1.rejeitadas);
    PERFORM pg_temp.afirma('V7 PLANO', 'processar a mesma carga de novo devolve os totais gravados, sem refazer',
        r1b.lidas = r1.lidas AND r1b.inseridas = r1.inseridas AND r1b.duplicadas = r1.duplicadas AND r1b.rejeitadas = r1.rejeitadas);
    PERFORM pg_temp.afirma('V7 PLANO', 'a carga fica marcada como processada, com a edição e as 15 linhas lidas', v_marcada);
    PERFORM pg_temp.confere('V7 PLANO', 'linhas do plano gravadas para a parceria nova', 3, n_plano);
    PERFORM pg_temp.confere('V7 PLANO', 'a primeira versão prevalece: a linha repetida com outra quantidade não troca a quantidade', 10, v_qtd);
    PERFORM pg_temp.confere('V7 PLANO', 'texto gravado com btrim (linhas com espaço sobrando nas pontas)', 0, n_sem_btrim);
    PERFORM pg_temp.confere('V7 PLANO', 'aplicação de marca não leva tipo de mídia', 0, n_aplicacao_com_tipo);
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 6: aplicação sem veículo (obrigatório vazio)', v_motivos[1] = 'veiculo: Campo obrigatório vazio ou inválido');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 7: quantidade ilegível diz o formato',
        v_motivos[2] = 'quantidade_prevista: Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 7: o conteúdo mostra o texto como foi escrito', v_conteudo LIKE '%1,5%');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 8: quantidade zero', v_motivos[3] = 'quantidade_prevista: A quantidade deve ser maior que zero.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 9: categoria fora da lista', v_motivos[4] = 'categoria: Categoria inválida. Use MIDIA ou APLICACAO_MARCA.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 10: unidade fora da lista', v_motivos[5] = 'unidade: Unidade inválida. Use INSERCAO, CPM ou APLICACAO.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 11: aplicação de marca só aceita a unidade APLICACAO',
        v_motivos[6] = 'unidade: Aplicação de marca usa a unidade APLICACAO.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 12: mídia sem tipo',
        v_motivos[7] = 'tipo_midia: Tipo de mídia inválido. Use ASSINADA, PROPRIA, MERCHANDISING, BRANDED ou CONTEUDO_DE_MARCA.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 13: tipo preenchido e inválido numa aplicação',
        v_motivos[8] = 'tipo_midia: Tipo de mídia inválido. Use ASSINADA, PROPRIA, MERCHANDISING, BRANDED ou CONTEUDO_DE_MARCA.');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 14: patrocinador sem parceria na edição', v_motivos[9] = 'patrocinador: Patrocinador sem parceria nesta edição');
    PERFORM pg_temp.afirma('V7 PLANO', 'rejeição linha 15: quantidade vazia', v_motivos[10] = 'quantidade_prevista: Campo obrigatório vazio ou inválido');
    PERFORM pg_temp.afirma('V7 PLANO', 'reenvio do mesmo arquivo: 15 lidas, 0 inseridas, 5 duplicadas, 10 rejeitadas',
        r2.lidas = 15 AND r2.inseridas = 0 AND r2.duplicadas = 5 AND r2.rejeitadas = 10);
    PERFORM pg_temp.confere('V7 PLANO', 'linhas do plano depois do reenvio (não duplica)', 3, n_depois_do_reenvio);
    PERFORM pg_temp.recusa('V7 PLANO', 'carga de outro tipo (mídia) não passa pela função do plano', 'rn_carga_tipo', $q$
        SELECT * FROM fn_carregar_plano((SELECT min(id) FROM carga WHERE tipo = 'MIDIA'), (SELECT id FROM evento WHERE edicao = 2027)) $q$);
END $$;

-- [HISTORICO] [ISOLAMENTO] Dados informados: um valor por linha (CHECK), anexo só da própria parceria (chave composta), nunca
-- alterado nem apagado (gatilho), e correção como linha nova: a vigente é a mais recente.
DO $$
BEGIN
    PERFORM pg_temp.recusa('V7 DADOS', 'dado informado sem nenhum valor', 'ck_dado_informado_um_valor', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total', 'PORTAL',
               (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'dado informado com dois valores ao mesmo tempo', 'ck_dado_informado_um_valor', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, valor_data, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total', 10, DATE '2026-12-01',
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'origem que não é PORTAL nem ATLAS', 'dado_informado_origem_check', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total', 10, 'OUTRA',
               (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'campo que não está no catálogo', 'dado_informado_campo_chave_fkey', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.nao_existe', 10, 'PORTAL',
               (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'anexo do Amarelo com arquivo da Verde', 'fk_dado_informado_arquivo_parceria', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total',
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) LIMIT 1),
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.aceita('V7 DADOS', 'anexo do Amarelo com arquivo do próprio Amarelo', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total',
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) LIMIT 1),
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'alterar um dado informado', 'rn_dado_informado_imutavel', $q$
        DO $d$ BEGIN
            INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total', 1000, 'PORTAL',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example');
            UPDATE dado_informado SET valor_numero = 9 WHERE campo_chave = 'ativacao.visitantes_total';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V7 DADOS', 'apagar um dado informado', 'rn_dado_informado_imutavel', $q$
        DO $d$ BEGIN
            INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.visitantes_total', 1000, 'PORTAL',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example');
            DELETE FROM dado_informado WHERE campo_chave = 'ativacao.visitantes_total';
        END $d$ $q$);
END $$;

DO $$
DECLARE
    v_amarelo BIGINT; v_roxo BIGINT; v_usuario BIGINT;
    v_vigente NUMERIC; v_origem TEXT; v_linhas INT; v_vigente_do_roxo INT; v_vigentes_do_amarelo INT;
BEGIN
    SELECT id INTO v_amarelo FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027;
    SELECT id INTO v_roxo FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027;
    SELECT id INTO v_usuario FROM _ver_usr WHERE email = 'marketing@amarelo.example';
    BEGIN
        -- a correção é uma linha nova na mesma transação (mesmo now()): o desempate é pelo id
        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, origem, informado_por) VALUES
         (v_amarelo, 'ativacao.visitantes_total', 1000, 'PORTAL', v_usuario),
         (v_amarelo, 'ativacao.visitantes_total', 1200, 'ATLAS', v_usuario),
         (v_amarelo, 'ativacao.brindes_disponiveis', 300, 'PORTAL', v_usuario);
        SELECT valor_numero, origem INTO v_vigente, v_origem FROM vw_dado_vigente
         WHERE parceria_id = v_amarelo AND campo_chave = 'ativacao.visitantes_total';
        SELECT count(*) INTO v_linhas FROM dado_informado WHERE parceria_id = v_amarelo AND campo_chave = 'ativacao.visitantes_total';
        SELECT count(*) INTO v_vigentes_do_amarelo FROM vw_dado_vigente WHERE parceria_id = v_amarelo;
        SELECT count(*) INTO v_vigente_do_roxo FROM vw_dado_vigente WHERE parceria_id = v_roxo;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.confere('V7 DADOS', 'o valor vigente é o da correção mais recente', 1200, v_vigente);
    PERFORM pg_temp.afirma('V7 DADOS', 'a origem do valor vigente é a da última linha (ATLAS)', v_origem = 'ATLAS');
    PERFORM pg_temp.confere('V7 DADOS', 'a correção é linha nova: o valor antigo continua como trilha', 2, v_linhas);
    PERFORM pg_temp.confere('V7 DADOS', 'um vigente por campo informado (2 campos do Amarelo)', 2, v_vigentes_do_amarelo);
    PERFORM pg_temp.confere('V7 DADOS', 'o dado de uma parceria não aparece como vigente de outra', 0, v_vigente_do_roxo);
END $$;

-- Órfãos das tabelas novas: esperado 0 em cada linha
DO $$
BEGIN
    PERFORM pg_temp.confere('ÓRFÃOS', 'dados informados sem parceria, campo ou autor', 0,
        (SELECT count(*) FROM dado_informado d
           LEFT JOIN parceria pa ON pa.id = d.parceria_id LEFT JOIN campo_catalogo c ON c.chave = d.campo_chave
           LEFT JOIN usuario u ON u.id = d.informado_por
          WHERE pa.id IS NULL OR c.chave IS NULL OR u.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'dados informados sem valor ou com mais de um', 0,
        (SELECT count(*) FROM dado_informado WHERE num_nonnulls(valor_numero, valor_data, arquivo_id) <> 1));
    PERFORM pg_temp.confere('ÓRFÃOS', 'dados informados com arquivo de outra parceria', 0,
        (SELECT count(*) FROM dado_informado d JOIN arquivo a ON a.id = d.arquivo_id WHERE a.parceria_id IS DISTINCT FROM d.parceria_id));
    PERFORM pg_temp.confere('ÓRFÃOS', 'campos do catálogo cujo limite não existe ou é de outra aba', 0,
        (SELECT count(*) FROM campo_catalogo c LEFT JOIN campo_catalogo l ON l.chave = c.limite_campo
          WHERE c.limite_campo IS NOT NULL AND (l.chave IS NULL OR l.aba_id <> c.aba_id)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'linhas de stg_plano sem a carga do plano que as recebeu', 0,
        (SELECT count(*) FROM stg_plano s LEFT JOIN carga c ON c.id = s.carga_id WHERE c.id IS NULL OR c.tipo <> 'PLANO_COMERCIAL'));
END $$;

-- ---------------------------------------------------------------------
-- 9. V8: as tabelas do Atlas (conversa, relatório, cartão, cálculo) e o relatório que fica congelado
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM pg_temp.confere('V8 ESTRUTURA', 'tabelas do Atlas (conversa, mensagem, relatório, solicitação, cálculo, tool_call, decisão e, desde a V10, traço e, desde a V12, fonte)', 9,
        (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name LIKE 'atlas\_%'));
    PERFORM pg_temp.afirma('V8 ESTRUTURA', 'o gatilho rn_atlas_relatorio_congelado existe, com a função tg_atlas_relatorio_congelado',
        EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                 WHERE t.tgname = 'rn_atlas_relatorio_congelado' AND p.proname = 'tg_atlas_relatorio_congelado'));
    PERFORM pg_temp.confere('V8 ESTRUTURA', 'a amostra não traz conversa, relatório nem cartão do Atlas', 0,
        (SELECT (SELECT count(*) FROM atlas_conversa) + (SELECT count(*) FROM atlas_mensagem) + (SELECT count(*) FROM atlas_relatorio)
              + (SELECT count(*) FROM atlas_solicitacao) + (SELECT count(*) FROM atlas_calculo)));
END $$;

-- [ISOLAMENTO] As chaves compostas: um relatório só nasce numa conversa da própria parceria, e um cartão só se liga a um
-- relatório da própria parceria. Cada tentativa monta o que precisa e termina recusada (nada fica gravado).
DO $$
BEGIN
    PERFORM pg_temp.recusa('V8 ISOLAMENTO', 'relatório do Roxo numa conversa do Amarelo', 'fk_atlas_relatorio_conversa', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'MONTANDO', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example');
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 ISOLAMENTO', 'cartão do Roxo num relatório do Amarelo', 'fk_atlas_solicitacao_relatorio', $q$
        DO $d$
        DECLARE v_conversa BIGINT; v_relatorio BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'MONTANDO', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_relatorio;
            INSERT INTO atlas_solicitacao (relatorio_id, parceria_id, tool_call_id, mensagem, campos, status)
            SELECT v_relatorio, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), 'botao_x', 'Preciso destes números.',
                   '["ativacao.visitantes_total"]', 'PENDENTE';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 ISOLAMENTO', 'status de relatório fora da lista', 'atlas_relatorio_status_check', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'PUBLICADO', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example');
        END $d$ $q$);
END $$;

-- A conversa gravada no formato de chat: papel só user, assistant ou tool; uma ordem por mensagem; um relatório em montagem e um
-- cartão aberto por vez; um nome de cálculo por relatório.
DO $$
BEGIN
    PERFORM pg_temp.recusa('V8 CONVERSA', 'mensagem com papel fora de user, assistant e tool', 'atlas_mensagem_papel_check', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (v_conversa, 1, 'system', 'regras');
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 CONVERSA', 'duas mensagens com a mesma ordem na conversa', 'uq_atlas_mensagem_ordem', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (v_conversa, 1, 'user', 'a'), (v_conversa, 1, 'assistant', 'b');
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 CONVERSA', 'dois relatórios em montagem na mesma conversa', 'uq_atlas_relatorio_ativo', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', s, '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            FROM unnest(ARRAY['MONTANDO', 'AGUARDANDO_RESPOSTA']) AS s;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V8 CONVERSA', 'um relatório pronto e outro em montagem na mesma conversa', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, snapshot, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', s, '{}',
                   CASE WHEN s = 'PRONTO' THEN '{}'::jsonb END, (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            FROM unnest(ARRAY['PRONTO', 'MONTANDO']) AS s;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 CONVERSA', 'dois cartões abertos no mesmo relatório', 'uq_atlas_solicitacao_pendente', $q$
        DO $d$
        DECLARE v_conversa BIGINT; v_relatorio BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'AGUARDANDO_RESPOSTA', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_relatorio;
            INSERT INTO atlas_solicitacao (relatorio_id, parceria_id, tool_call_id, mensagem, campos, status)
            SELECT v_relatorio, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), t, 'Preciso destes números.',
                   '["ativacao.visitantes_total"]', 'PENDENTE'
            FROM unnest(ARRAY['botao_a', 'botao_b']) AS t;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V8 CONVERSA', 'cartões respondidos, recusados e cancelados convivem com um aberto', $q$
        DO $d$
        DECLARE v_conversa BIGINT; v_relatorio BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'AGUARDANDO_RESPOSTA', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_relatorio;
            INSERT INTO atlas_solicitacao (relatorio_id, parceria_id, tool_call_id, mensagem, campos, status)
            SELECT v_relatorio, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), t.id, 'Preciso destes números.',
                   '["ativacao.visitantes_total"]', t.status
            FROM (VALUES ('botao_a', 'CANCELADA'), ('botao_b', 'RESPONDIDA'), ('botao_c', 'RECUSADA'), ('botao_d', 'PENDENTE')) AS t(id, status);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V8 CONVERSA', 'dois cálculos com o mesmo nome no relatório', 'uq_atlas_calculo_nome', $q$
        DO $d$
        DECLARE v_conversa BIGINT; v_relatorio BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'MONTANDO', '{}',
                   (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_relatorio;
            INSERT INTO atlas_calculo (relatorio_id, nome, script, variaveis, resultado, unidade)
            VALUES (v_relatorio, 'taxa_cadastro', 'pct(cadastros, visitantes)', '[]', 89.3, '%'),
                   (v_relatorio, 'taxa_cadastro', 'pct(cadastros, visitantes)', '[]', 89.3, '%');
        END $d$ $q$);
    PERFORM pg_temp.aceita('V8 CONVERSA', 'o mesmo nome de cálculo em dois relatórios diferentes', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, snapshot, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', s, '{}',
                   CASE WHEN s = 'PRONTO' THEN '{}'::jsonb END, (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            FROM unnest(ARRAY['PRONTO', 'MONTANDO']) AS s;
            INSERT INTO atlas_calculo (relatorio_id, nome, script, variaveis, resultado, unidade)
            SELECT r.id, 'taxa_cadastro', 'pct(cadastros, visitantes)', '[]', 89.3, '%' FROM atlas_relatorio r WHERE r.conversa_id = v_conversa;
        END $d$ $q$);
END $$;

-- [HISTORICO] O relatório pronto ou descartado não muda mais (especificação, snapshot e status), e o pronto não se apaga. O que
-- está montando muda à vontade e sai por PRONTO (com o snapshot, na mesma operação) ou DESCARTADO.
DO $$
DECLARE
    -- O molde de cada tentativa: uma conversa do Amarelo e, nela, um relatório no status dado (%1$s; o snapshot só existe no pronto),
    -- seguido do comando que a tentativa roda sobre ele (%2$s, com v_relatorio).
    c_novo CONSTANT TEXT := $c$
        DO $d$
        DECLARE v_conversa BIGINT; v_relatorio BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, snapshot, criado_por)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', '%1$s', '{"modelo":"ATIVACAO_EVENTO"}',
                   CASE WHEN '%1$s' = 'PRONTO' THEN '{"secoes":[]}'::jsonb END, (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_relatorio;
            %2$s
        END $d$ $c$;
BEGIN
    PERFORM pg_temp.recusa('V8 CONGELADO', 'mudar a especificação de um relatório pronto', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'PRONTO', $q$ UPDATE atlas_relatorio SET especificacao = '{"modelo":"OUTRO"}' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'mudar o snapshot de um relatório pronto', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'PRONTO', $q$ UPDATE atlas_relatorio SET snapshot = '{"secoes":[{"id":"x"}]}' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'reabrir um relatório pronto (status de volta a MONTANDO)', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'PRONTO', $q$ UPDATE atlas_relatorio SET status = 'MONTANDO' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'descartar um relatório pronto', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'PRONTO', $q$ UPDATE atlas_relatorio SET status = 'DESCARTADO' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'apagar um relatório pronto', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'PRONTO', $q$ DELETE FROM atlas_relatorio WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'reabrir um relatório descartado', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'DESCARTADO', $q$ UPDATE atlas_relatorio SET status = 'MONTANDO' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.recusa('V8 CONGELADO', 'mudar a especificação de um relatório descartado', 'rn_atlas_relatorio_congelado',
        format(c_novo, 'DESCARTADO', $q$ UPDATE atlas_relatorio SET especificacao = '{"modelo":"OUTRO"}' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.aceita('V8 CONGELADO', 'o que está montando vira PRONTO com o snapshot, na mesma operação',
        format(c_novo, 'MONTANDO', $q$ UPDATE atlas_relatorio SET status = 'PRONTO', snapshot = '{"secoes":[]}', finalizado_em = now() WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.aceita('V8 CONGELADO', 'o que está montando muda a especificação e a versão',
        format(c_novo, 'MONTANDO', $q$ UPDATE atlas_relatorio SET especificacao = '{"modelo":"ATIVACAO_EVENTO","texto":"novo"}', versao_especificacao = versao_especificacao + 1 WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.aceita('V8 CONGELADO', 'o que está aguardando resposta pode ser descartado',
        format(c_novo, 'AGUARDANDO_RESPOSTA', $q$ UPDATE atlas_relatorio SET status = 'DESCARTADO' WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.aceita('V8 CONGELADO', 'o que está montando pode ser apagado',
        format(c_novo, 'MONTANDO', $q$ DELETE FROM atlas_relatorio WHERE id = v_relatorio; $q$));
    PERFORM pg_temp.aceita('V8 CONGELADO', 'o relatório pronto aceita mexer no que não é congelado (finalizado_em)',
        format(c_novo, 'PRONTO', $q$ UPDATE atlas_relatorio SET finalizado_em = now() WHERE id = v_relatorio; $q$));
END $$;

-- O resumo do raciocínio (V9): só a mensagem do assistente tem, e o texto não é vazio.
DO $$
DECLARE
    -- O molde de cada tentativa: uma conversa do Amarelo e, nela, uma mensagem com o papel dado (%1$s) e o resumo dado (%2$s, já entre aspas).
    c_novo CONSTANT TEXT := $c$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, reasoning_content, resumo_do_raciocinio)
            VALUES (v_conversa, 1, '%1$s', 'a', 'Raciocínio.', %2$s);
        END $d$ $c$;
BEGIN
    PERFORM pg_temp.recusa('V9 RESUMO', 'resumo do raciocínio numa mensagem que não é do assistente', 'ck_atlas_mensagem_resumo',
        format(c_novo, 'user', quote_literal('Conferi os números.')));
    PERFORM pg_temp.recusa('V9 RESUMO', 'resumo do raciocínio em branco', 'ck_atlas_mensagem_resumo',
        format(c_novo, 'assistant', quote_literal('   ')));
    PERFORM pg_temp.aceita('V9 RESUMO', 'resumo do raciocínio numa mensagem do assistente',
        format(c_novo, 'assistant', quote_literal('Conferi os números.')));
END $$;

-- O traço do diagnóstico (V10): uma linha por etapa do turno, presa à conversa e à parceria dela, com a lista completa de etapas.
DO $$
DECLARE
    -- O molde de cada tentativa: uma conversa do Amarelo e, nela, um traço com a etapa (%1$s), o resultado (%2$s) e a duração (%3$s) dados.
    c_novo CONSTANT TEXT := $c$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            SELECT c.parceria_id, c.id, gen_random_uuid(), 1, '%1$s', '%2$s', now(), %3$s FROM atlas_conversa c WHERE c.id = v_conversa;
        END $d$ $c$;
BEGIN
    PERFORM pg_temp.afirma('V10 ESTRUTURA', 'o CHECK de etapa aceita as 15 etapas do plano',
        (SELECT count(*) FROM unnest(ARRAY['G1_LIMITES','J1_INTENCAO','J1_CARTAO','LLM_IDA','FERRAMENTA','BUSCA','R3_FONTES','CHECAGEM',
                                           'R5_RESPOSTA','V2_COERENCIA','LACUNAS','RESUMO','SAIDA','DISJUNTOR','ERRO']) e
          WHERE (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_atlas_traco_etapa') LIKE '%''' || e || '''%') = 15);
    PERFORM pg_temp.confere('V10 ESTRUTURA', 'a amostra não traz traço do diagnóstico', 0, (SELECT count(*) FROM atlas_traco));
    PERFORM pg_temp.aceita('V10 TRACO', 'um traço de LLM_IDA numa conversa do Amarelo', format(c_novo, 'LLM_IDA', 'OK', '12'));
    PERFORM pg_temp.recusa('V10 TRACO', 'etapa fora da lista', 'ck_atlas_traco_etapa', format(c_novo, 'INVENTADA', 'OK', '12'));
    PERFORM pg_temp.recusa('V10 TRACO', 'resultado fora da lista', 'ck_atlas_traco_resultado', format(c_novo, 'LLM_IDA', 'TALVEZ', '12'));
    PERFORM pg_temp.recusa('V10 TRACO', 'duração negativa', 'ck_atlas_traco_duracao', format(c_novo, 'LLM_IDA', 'OK', '-1'));
    PERFORM pg_temp.recusa('V10 ISOLAMENTO', 'traço da parceria Roxo numa conversa do Amarelo', 'fk_atlas_traco_conversa', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            VALUES ((SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), v_conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V10 TRACO', 'duas etapas com a mesma ordem no mesmo turno', 'uq_atlas_traco_ordem', $q$
        DO $d$
        DECLARE v_conversa BIGINT; v_turno UUID := gen_random_uuid();
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            SELECT c.parceria_id, c.id, v_turno, 1, e, 'OK', now(), 1 FROM atlas_conversa c, unnest(ARRAY['G1_LIMITES','LLM_IDA']) e WHERE c.id = v_conversa;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V10 TRACO', 'dados que não são um objeto JSON', 'ck_atlas_traco_dados', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms, dados)
            SELECT c.parceria_id, c.id, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1, '["texto"]' FROM atlas_conversa c WHERE c.id = v_conversa;
        END $d$ $q$);
END $$;

-- Órfãos das tabelas novas: esperado 0 em cada linha
DO $$
BEGIN
    PERFORM pg_temp.confere('ÓRFÃOS', 'relatórios do Atlas sem a conversa da mesma parceria', 0,
        (SELECT count(*) FROM atlas_relatorio r LEFT JOIN atlas_conversa c ON c.id = r.conversa_id AND c.parceria_id = r.parceria_id WHERE c.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'cartões do Atlas sem o relatório da mesma parceria', 0,
        (SELECT count(*) FROM atlas_solicitacao s LEFT JOIN atlas_relatorio r ON r.id = s.relatorio_id AND r.parceria_id = s.parceria_id WHERE r.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'mensagens do Atlas sem conversa', 0,
        (SELECT count(*) FROM atlas_mensagem m LEFT JOIN atlas_conversa c ON c.id = m.conversa_id WHERE c.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'mensagens de ferramenta sem o pedido que respondem', 0,
        (SELECT count(*) FROM atlas_mensagem m WHERE m.papel = 'tool' AND (m.tool_call_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM atlas_mensagem a WHERE a.conversa_id = m.conversa_id AND a.papel = 'assistant' AND a.ordem < m.ordem
               AND a.tool_calls @> jsonb_build_array(jsonb_build_object('id', m.tool_call_id))))));
    PERFORM pg_temp.confere('ÓRFÃOS', 'cálculos do Atlas sem relatório', 0,
        (SELECT count(*) FROM atlas_calculo k LEFT JOIN atlas_relatorio r ON r.id = k.relatorio_id WHERE r.id IS NULL));
    PERFORM pg_temp.confere('ÓRFÃOS', 'relatórios do Atlas prontos sem snapshot, ou com snapshot sem estarem prontos', 0,
        (SELECT count(*) FROM atlas_relatorio WHERE (status = 'PRONTO') <> (snapshot IS NOT NULL)));
    PERFORM pg_temp.confere('ÓRFÃOS', 'traços do Atlas ligados a uma mensagem de outra conversa', 0,
        (SELECT count(*) FROM atlas_traco t JOIN atlas_mensagem m ON m.id = t.mensagem_id WHERE m.conversa_id <> t.conversa_id));
END $$;

-- ---------------------------------------------------------------------
-- 10. V11: documentos, trechos, busca textual sem acento e RLS do atlas_ro
-- ---------------------------------------------------------------------
-- O kit de documentos fica confirmado no banco de teste (KitDeDocumentosDeTeste) e pode estar aqui: os documentos montados abaixo têm
-- chaves e títulos próprios (rls-*, v11-*), e as consultas só olham para eles. Cada tentativa é desfeita.

-- Tentativa de uma operação que o papel não pode fazer: roda o comando COMO o papel e exige a recusa 42501 (permissão negada). O
-- pg_temp.recusa só aceita a classe 23 (violação de regra), por isso este auxiliar. O papel volta ao normal no fim (a tentativa é desfeita).
CREATE OR REPLACE FUNCTION pg_temp.nega(p_grupo TEXT, p_conferencia TEXT, p_papel TEXT, p_comando TEXT)
RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE v_estado TEXT := 'ZZ001';
BEGIN
    BEGIN
        EXECUTE format('SET LOCAL ROLE %I', p_papel);
        EXECUTE p_comando;
        RAISE EXCEPTION 'desfazer a tentativa' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_estado = RETURNED_SQLSTATE;
    END;
    INSERT INTO _ver (grupo, conferencia, esperado, obtido, ok)
    VALUES (p_grupo, p_conferencia, 'recusa 42501 (sem permissão)',
            CASE WHEN v_estado = 'ZZ001' THEN 'ACEITO (sem recusa)' ELSE v_estado END, v_estado = '42501');
END $f$;

-- Atalhos: a edição, o usuário da RBS, um vetor de 768 dimensões qualquer e um arquivo de documento (da parceria do cliente, ou geral).
CREATE OR REPLACE FUNCTION pg_temp.ev(p_ano INT) RETURNS BIGINT LANGUAGE sql AS $f$
    SELECT id FROM evento WHERE nome = 'Planeta Atlântida' AND edicao = p_ano
$f$;
CREATE OR REPLACE FUNCTION pg_temp.rbs() RETURNS BIGINT LANGUAGE sql AS $f$
    SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'
$f$;
CREATE OR REPLACE FUNCTION pg_temp.vetor() RETURNS vector LANGUAGE sql AS $f$
    SELECT ('[' || array_to_string(array_fill(0.1::float8, ARRAY[768]), ',') || ']')::vector
$f$;
CREATE OR REPLACE FUNCTION pg_temp.arq(p_cliente TEXT DEFAULT NULL, p_categoria TEXT DEFAULT 'DOCUMENTO') RETURNS BIGINT LANGUAGE sql AS $f$
    INSERT INTO arquivo (parceria_id, categoria, nome_original, mime_type, tamanho_bytes, caminho, enviado_por)
    VALUES ((SELECT id FROM _ver_parc WHERE cliente = p_cliente AND edicao = 2027), p_categoria, 'v11.pdf', 'application/pdf', 10,
            'teste/' || gen_random_uuid() || '.pdf', pg_temp.rbs())
    RETURNING id
$f$;

-- Monta um documento de teste e o arquivo dele; devolve o id do documento. p_estado: AGUARDANDO (só enviado), PROCESSANDO (aprovado,
-- com um trecho inativo, sem publicar) ou INDEXADO (aprovado, com um trecho e publicado por fn_publicar_documento). A versão é a maior
-- do grupo mais 1. O trecho se chama "Trecho de <título>".
CREATE OR REPLACE FUNCTION pg_temp.doc(p_titulo TEXT, p_chave TEXT, p_escopo TEXT, p_ano INT, p_cota TEXT, p_cliente TEXT, p_estado TEXT,
                                       p_dados BOOLEAN DEFAULT FALSE)
RETURNS BIGINT LANGUAGE plpgsql AS $f$
DECLARE
    v_evento BIGINT := pg_temp.ev(p_ano);
    v_cota SMALLINT := (SELECT id FROM cota_tipo WHERE codigo = p_cota);
    v_parceria BIGINT := (SELECT id FROM _ver_parc WHERE cliente = p_cliente AND edicao = p_ano);
    v_doc BIGINT;
BEGIN
    INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, parceria_id, arquivo_id, enviado_por,
                           contem_dados_pessoais)
    VALUES (p_titulo, p_chave,
            (SELECT coalesce(max(versao), 0) + 1 FROM documento WHERE evento_id = v_evento AND escopo = p_escopo
              AND cota_tipo_id IS NOT DISTINCT FROM v_cota AND parceria_id IS NOT DISTINCT FROM v_parceria AND chave = p_chave),
            'OUTRO', p_escopo, v_evento, v_cota, v_parceria, pg_temp.arq(p_cliente), pg_temp.rbs(), p_dados)
    RETURNING id INTO v_doc;
    IF p_estado <> 'AGUARDANDO' THEN
        UPDATE documento SET status = 'PROCESSANDO', aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now()
         WHERE id = v_doc;
        INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding)
        VALUES (v_doc, 1, 1, 1, 'Trecho de ' || p_titulo, 5, pg_temp.vetor());
        UPDATE documento SET modelo_embedding = 'teste', dimensoes = 768 WHERE id = v_doc;
        IF p_estado = 'INDEXADO' THEN
            PERFORM fn_publicar_documento(v_doc);
        END IF;
    END IF;
    RETURN v_doc;
END $f$;

-- Os dois textos casam na busca (nos dois sentidos), com a mesma função na coluna e na consulta.
CREATE OR REPLACE FUNCTION pg_temp.casa(p_a TEXT, p_b TEXT) RETURNS BOOLEAN LANGUAGE sql AS $f$
    SELECT to_tsvector('pt_sem_acento', fn_texto_de_busca(p_a)) @@ plainto_tsquery('pt_sem_acento', fn_texto_de_busca(p_b))
       AND to_tsvector('pt_sem_acento', fn_texto_de_busca(p_b)) @@ plainto_tsquery('pt_sem_acento', fn_texto_de_busca(p_a))
$f$;

-- Estrutura
DO $$
BEGIN
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'a extensão unaccent está em public',
        (SELECT count(*) = 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'unaccent' AND n.nspname = 'public'));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'a configuração de busca pt_sem_acento existe',
        (SELECT count(*) = 1 FROM pg_ts_config WHERE cfgname = 'pt_sem_acento'));
    PERFORM pg_temp.confere('V11 ESTRUTURA', 'funções fn_texto_de_busca, fn_trava_grupo_documento, fn_publicar_documento', 3,
        (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname IN ('fn_texto_de_busca', 'fn_trava_grupo_documento', 'fn_publicar_documento')));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'fn_texto_de_busca é IMMUTABLE (entra na coluna gerada)',
        (SELECT provolatile = 'i' FROM pg_proc WHERE proname = 'fn_texto_de_busca' AND pronamespace = 'public'::regnamespace));
    PERFORM pg_temp.confere('V11 ESTRUTURA', 'gatilhos rn_documento* (7 da V11 e o rn_documento_congelado da V12)', 8,
        (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'rn\_documento%'));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'tsv é coluna gerada STORED (no PostgreSQL 18 a gerada é virtual por padrão)',
        (SELECT attgenerated = 's' FROM pg_attribute WHERE attrelid = 'documento_trecho'::regclass AND attname = 'tsv'));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'o vetor do trecho tem 768 dimensões',
        (SELECT format_type(atttypid, atttypmod) = 'vector(768)' FROM pg_attribute WHERE attrelid = 'documento_trecho'::regclass AND attname = 'embedding'));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'índices HNSW (cosseno) no vetor e GIN no tsvector',
        (SELECT count(*) = 2 FROM pg_indexes WHERE tablename = 'documento_trecho'
          AND (indexdef LIKE '%USING hnsw (embedding%' OR indexdef LIKE '%USING gin (tsv)%')));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'o índice único do grupo e da versão é NULLS NOT DISTINCT',
        (SELECT i.indnullsnotdistinct AND i.indisunique FROM pg_index i WHERE i.indexrelid = 'uq_documento_grupo_versao'::regclass));
    PERFORM pg_temp.afirma('V11 ESTRUTURA', 'o CHECK de arquivo aceita a categoria DOCUMENTO',
        (SELECT pg_get_constraintdef(oid) LIKE '%''DOCUMENTO''%' FROM pg_constraint WHERE conname = 'ck_arquivo_categoria'));
    PERFORM pg_temp.afirma('V11 RLS', 'a RLS está ligada nas duas tabelas e sem FORCE (o dono segue como antes)',
        (SELECT bool_and(relrowsecurity AND NOT relforcerowsecurity) AND count(*) = 2 FROM pg_class
          WHERE oid IN ('documento'::regclass, 'documento_trecho'::regclass)));
    PERFORM pg_temp.afirma('V11 RLS', 'uma política de leitura por tabela, só para o atlas_ro',
        (SELECT count(*) = 2 AND bool_and(cmd = 'SELECT' AND roles = ARRAY['atlas_ro']::name[]) FROM pg_policies
          WHERE tablename IN ('documento', 'documento_trecho')));
    PERFORM pg_temp.afirma('V11 RLS', 'o atlas_ro lê documento e documento_trecho e não grava em nenhuma',
        has_table_privilege('atlas_ro', 'documento', 'SELECT') AND has_table_privilege('atlas_ro', 'documento_trecho', 'SELECT')
        AND NOT has_table_privilege('atlas_ro', 'documento', 'INSERT') AND NOT has_table_privilege('atlas_ro', 'documento', 'UPDATE')
        AND NOT has_table_privilege('atlas_ro', 'documento', 'DELETE') AND NOT has_table_privilege('atlas_ro', 'documento_trecho', 'INSERT')
        AND NOT has_table_privilege('atlas_ro', 'documento_trecho', 'UPDATE') AND NOT has_table_privilege('atlas_ro', 'documento_trecho', 'DELETE'));
    PERFORM pg_temp.afirma('V11 RLS', 'o atlas_ro não lê nenhuma outra tabela nem visão do portal (camada 4)',
        (SELECT NOT coalesce(bool_or(has_table_privilege('atlas_ro', c.oid, 'SELECT')), FALSE) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v') AND c.relname NOT IN ('documento', 'documento_trecho')));
END $$;

-- Busca textual: singular e plural, com e sem acento, casam (conferido em 30/10 com 11 pares)
DO $$
BEGIN
    PERFORM pg_temp.afirma('V11 BUSCA', 'inserção ~ inserções', pg_temp.casa('inserção', 'inserções'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'inserção ~ insercoes', pg_temp.casa('inserção', 'insercoes'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'inserções ~ insercao', pg_temp.casa('inserções', 'insercao'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'credencial ~ credenciais', pg_temp.casa('credencial', 'credenciais'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'informação ~ informacoes', pg_temp.casa('informação', 'informacoes'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'informações ~ informacao', pg_temp.casa('informações', 'informacao'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'material ~ materiais', pg_temp.casa('material', 'materiais'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'geral ~ gerais', pg_temp.casa('geral', 'gerais'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'gerador ~ geradores', pg_temp.casa('gerador', 'geradores'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'portão ~ portões', pg_temp.casa('portão', 'portões'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'tarifa ~ Tarifas', pg_temp.casa('tarifa', 'Tarifas'));
    PERFORM pg_temp.afirma('V11 BUSCA', 'palavras diferentes não casam (tarifa de energia x credencial)', NOT pg_temp.casa('tarifa de energia', 'credencial'));
END $$;

-- A coluna tsv indexa a seção e o texto, e acha o plural sem acento (a coluna gerada e a consulta usam a mesma função)
DO $$
DECLARE v_achou BOOLEAN;
BEGIN
    BEGIN
        PERFORM pg_temp.doc('v11-busca', 'v11-busca', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        UPDATE documento_trecho SET secao_caminho = '3 Energia › 3.2 Tarifa', texto = 'Cobrança das inserções do patrocinador'
         WHERE documento_id = (SELECT id FROM documento WHERE titulo = 'v11-busca');
        SELECT bool_and(tsv @@ plainto_tsquery('pt_sem_acento', fn_texto_de_busca(consulta))) INTO v_achou
          FROM documento_trecho, unnest(ARRAY['insercao', 'insercoes', 'inserção', 'tarifa', 'energia', 'patrocinador']) AS consulta
         WHERE documento_id = (SELECT id FROM documento WHERE titulo = 'v11-busca');
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 BUSCA', 'o tsv gerado acha seção e texto no singular e no plural, com e sem acento', coalesce(v_achou, FALSE));
END $$;

-- As regras do documento
DO $$
BEGIN
    -- O escopo
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'GLOBAL com cota', 'ck_documento_escopo', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), 1, pg_temp.arq(), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'COTA sem cota', 'ck_documento_escopo', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'COTA', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'COTA com parceria', 'ck_documento_escopo', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, parceria_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'COTA', pg_temp.ev(2027), 1, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                pg_temp.arq('Cliente Amarelo'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'PARCERIA sem parceria', 'ck_documento_escopo', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'PARCERIA', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'PARCERIA com cota', 'ck_documento_escopo', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, cota_tipo_id, parceria_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'PARCERIA', pg_temp.ev(2027), 1, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                pg_temp.arq('Cliente Amarelo'), pg_temp.rbs()) $q$);
    -- O arquivo e a edição
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'documento GLOBAL com o arquivo de uma parceria', 'rn_documento_coerente', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq('Cliente Amarelo'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'documento PARCERIA com arquivo geral (sem parceria)', 'rn_documento_coerente', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, parceria_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'PARCERIA', pg_temp.ev(2027), (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                pg_temp.arq(), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 ISOLAMENTO', 'documento do Amarelo com o arquivo do Roxo', 'rn_documento_coerente', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, parceria_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'PARCERIA', pg_temp.ev(2027), (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                pg_temp.arq('Cliente Roxo'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'arquivo de outra categoria que não DOCUMENTO', 'rn_documento_coerente', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(NULL, 'OUTRO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'documento PARCERIA de uma edição que não é a da parceria', 'rn_documento_coerente', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, parceria_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'PARCERIA', pg_temp.ev(2026), (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                pg_temp.arq('Cliente Amarelo'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'um arquivo serve a um documento só', 'uq_documento_arquivo', $q$
        DO $d$
        DECLARE v_arq BIGINT := pg_temp.arq();
        BEGIN
            INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
            VALUES ('v11-a', 'v11-a', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), v_arq, pg_temp.rbs());
            INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
            VALUES ('v11-b', 'v11-b', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), v_arq, pg_temp.rbs());
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'chave que não é um identificador (maiúsculas e espaço)', 'documento_chave_check', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'Manual Operacoes', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V11 DOCUMENTO', 'tipo fora da lista', 'documento_tipo_check', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
        VALUES ('v11-x', 'v11-x', 1, 'PLANILHA', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs()) $q$);
    -- O grupo e a versão
    PERFORM pg_temp.recusa('V11 GRUPO', 'dois documentos GLOBAL iguais (mesma edição, chave e versão): a cota e a parceria são nulas', 'uq_documento_grupo_versao', $q$
        DO $d$
        BEGIN
            PERFORM pg_temp.doc('v11-dup', 'v11-dup', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
            INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por)
            VALUES ('v11-dup', 'v11-dup', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs());
        END $d$ $q$);
    PERFORM pg_temp.aceita('V11 GRUPO', 'anexos de duas parcerias com a mesma chave convivem, os dois na versão 1', $q$
        DO $d$
        BEGIN
            PERFORM pg_temp.doc('v11-anexo-amarelo', 'v11-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO');
            PERFORM pg_temp.doc('v11-anexo-roxo', 'v11-anexo', 'PARCERIA', 2027, NULL, 'Cliente Roxo', 'INDEXADO');
            IF (SELECT count(*) FROM documento WHERE chave = 'v11-anexo' AND versao = 1 AND status = 'INDEXADO') <> 2 THEN
                RAISE EXCEPTION 'os dois anexos deviam estar indexados na versão 1';
            END IF;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V11 GRUPO', 'a mesma chave em outra edição nasce na versão 1 e não toca a outra (o D10)', $q$
        DO $d$
        BEGIN
            PERFORM pg_temp.doc('v11-manual-27', 'v11-manual', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
            PERFORM pg_temp.doc('v11-manual-26', 'v11-manual', 'GLOBAL', 2026, NULL, NULL, 'INDEXADO');
            IF (SELECT count(*) FROM documento WHERE chave = 'v11-manual' AND versao = 1 AND status = 'INDEXADO') <> 2 THEN
                RAISE EXCEPTION 'as duas edições deviam estar indexadas na versão 1';
            END IF;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V11 GRUPO', 'o mesmo plano em cotas diferentes convive (D03, D04 e D05)', $q$
        DO $d$
        BEGIN
            PERFORM pg_temp.doc('v11-plano-m', 'v11-plano', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO');
            PERFORM pg_temp.doc('v11-plano-g', 'v11-plano', 'COTA', 2027, 'SUBCOTA_G', NULL, 'INDEXADO');
            PERFORM pg_temp.doc('v11-plano-s', 'v11-plano', 'COTA', 2027, 'SUBCOTA', NULL, 'INDEXADO');
            IF (SELECT count(*) FROM documento WHERE chave = 'v11-plano' AND status = 'INDEXADO') <> 3 THEN
                RAISE EXCEPTION 'os três planos deviam estar indexados';
            END IF;
        END $d$ $q$);
END $$;

-- O ciclo de status
DO $$
BEGIN
    PERFORM pg_temp.recusa('V11 STATUS', 'documento novo nasce INDEXADO', 'rn_documento_transicao', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por, status)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs(), 'INDEXADO') $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'documento novo nasce ERRO sem dizer o erro', 'ck_documento_erro', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por, status)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs(), 'ERRO') $q$);
    PERFORM pg_temp.aceita('V11 STATUS', 'documento novo nasce ERRO com o motivo e sem aprovado_por (PDF sem texto)', $q$
        INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por, status, erro)
        VALUES ('v11-x', 'v11-x', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs(), 'ERRO', 'Sem texto extraível; OCR fora do escopo.') $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'aguardando aprovação direto para INDEXADO', 'rn_documento_transicao', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t1', 'v11-t1', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'INDEXADO' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'aprovar sem dizer quem aprovou', 'rn_documento_aprovacao', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t2', 'v11-t2', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 PERMISSAO', 'aprovar com um Admin Patrocinador (a chave composta só aceita a RBS)', 'fk_documento_aprovador', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t3', 'v11-t3', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', aprovado_por = (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example'),
                                 aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 PERMISSAO', 'aprovado_por_perfil que não é ADMIN_RBS', 'documento_aprovado_por_perfil_check', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t4', 'v11-t4', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', aprovado_por = (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example'),
                                 aprovado_por_perfil = 'ADMIN_PATROCINADOR', aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 DADOS PESSOAIS', 'aprovar documento com suspeita de dado pessoal (o D11)', 'rn_documento_dados_pessoais', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t5', 'v11-t5', 'PARCERIA', 2027, NULL, 'Cliente Verde', 'AGUARDANDO', TRUE);
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 DADOS PESSOAIS', 'aprovar um documento que nasceu em ERRO e tem suspeita de dado pessoal', 'rn_documento_dados_pessoais', $q$
        DO $d$
        DECLARE v_id BIGINT;
        BEGIN
            INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, status, erro, contem_dados_pessoais, enviado_por)
            VALUES ('v11-t6', 'v11-t6', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), 'ERRO', 'Falha de teste.', TRUE, pg_temp.rbs())
            RETURNING id INTO v_id;
            UPDATE documento SET status = 'PROCESSANDO', erro = NULL, aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS',
                                 aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'reindexar documento que nasceu em ERRO (nunca foi aprovado)', 'rn_documento_aprovacao', $q$
        DO $d$
        DECLARE v_id BIGINT;
        BEGIN
            INSERT INTO documento (titulo, chave, versao, tipo, escopo, evento_id, arquivo_id, enviado_por, status, erro)
            VALUES ('v11-t7', 'v11-t7', 1, 'OUTRO', 'GLOBAL', pg_temp.ev(2027), pg_temp.arq(), pg_temp.rbs(), 'ERRO', 'Sem texto extraível.') RETURNING id INTO v_id;
            UPDATE documento SET status = 'PROCESSANDO', erro = NULL WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V11 STATUS', 'reindexar documento aprovado que deu erro (ERRO volta a PROCESSANDO)', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t8', 'v11-t8', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        BEGIN
            UPDATE documento SET status = 'ERRO', erro = 'Falha de teste.' WHERE id = v_id;
            UPDATE documento SET status = 'PROCESSANDO', erro = NULL WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'documento indexado voltando a PROCESSANDO', 'rn_documento_transicao', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t9', 'v11-t9', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'documento recusado voltando a PROCESSANDO', 'rn_documento_transicao', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t10', 'v11-t10', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'RECUSADO' WHERE id = v_id;
            UPDATE documento SET status = 'PROCESSANDO', aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'documento revogado voltando a INDEXADO', 'rn_documento_transicao', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t11', 'v11-t11', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET status = 'REVOGADO', revogado_por = pg_temp.rbs(), revogado_em = now() WHERE id = v_id;
            UPDATE documento SET status = 'INDEXADO' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'indexar sem nenhum trecho', 'ck_documento_indexado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t12', 'v11-t12', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = v_id;
            UPDATE documento SET status = 'INDEXADO', modelo_embedding = 'teste', dimensoes = 768 WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 STATUS', 'revogar sem dizer quem revogou', 'ck_documento_revogado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t13', 'v11-t13', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET status = 'REVOGADO' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V11 STATUS', 'um documento substituído ainda pode ser revogado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t14', 'v11-t14', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            PERFORM pg_temp.doc('v11-t14-v2', 'v11-t14', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
            UPDATE documento SET status = 'REVOGADO', revogado_por = pg_temp.rbs(), revogado_em = now() WHERE id = v_id AND status = 'SUBSTITUIDO';
            IF (SELECT status FROM documento WHERE id = v_id) <> 'REVOGADO' THEN
                RAISE EXCEPTION 'a v1 substituída devia ter sido revogada';
            END IF;
        END $d$ $q$);
END $$;

-- Os trechos e fn_publicar_documento
DO $$
DECLARE
    v_ok_substituicao BOOLEAN; v_ok_anexos BOOLEAN; v_ok_edicoes BOOLEAN; v_ok_revogacao BOOLEAN; v_ok_antiga BOOLEAN; v_ok_copia BOOLEAN;
    v_ok_publicar_duas_vezes BOOLEAN;
    v1 BIGINT; v2 BIGINT; a BIGINT; b BIGINT;
BEGIN
    -- v2 desativa a v1 na mesma transação
    BEGIN
        v1 := pg_temp.doc('v11-s', 'v11-s', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        v2 := pg_temp.doc('v11-s-v2', 'v11-s', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        PERFORM fn_publicar_documento(v2);
        v_ok_substituicao := (SELECT status FROM documento WHERE id = v1) = 'SUBSTITUIDO'
            AND (SELECT status FROM documento WHERE id = v2) = 'INDEXADO'
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = v1 AND ativo) = 0
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = v2 AND ativo) = 1
            AND (SELECT versao FROM documento WHERE id = v2) = 2 AND (SELECT trechos FROM documento WHERE id = v2) = 1;
        BEGIN
            PERFORM fn_publicar_documento(v2);
            v_ok_publicar_duas_vezes := FALSE;
        EXCEPTION WHEN OTHERS THEN
            v_ok_publicar_duas_vezes := TRUE;
        END;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'publicar a v2 passa a v1 a SUBSTITUIDO com os trechos inativos e a v2 a INDEXADO com os trechos ativos', coalesce(v_ok_substituicao, FALSE));
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'publicar um documento que não está em processamento é recusado', coalesce(v_ok_publicar_duas_vezes, FALSE));

    -- Publicar um anexo não toca o de outra parceria (mesma chave)
    BEGIN
        a := pg_temp.doc('v11-an-a', 'v11-an', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO');
        b := pg_temp.doc('v11-an-r', 'v11-an', 'PARCERIA', 2027, NULL, 'Cliente Roxo', 'INDEXADO');
        v2 := pg_temp.doc('v11-an-a2', 'v11-an', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'PROCESSANDO');
        PERFORM fn_publicar_documento(v2);
        v_ok_anexos := (SELECT status FROM documento WHERE id = a) = 'SUBSTITUIDO' AND (SELECT status FROM documento WHERE id = b) = 'INDEXADO'
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = b AND ativo) = 1;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'publicar a v2 do anexo do Amarelo não toca o anexo do Roxo (mesma chave)', coalesce(v_ok_anexos, FALSE));

    -- Publicar a edição de 2027 não toca a de 2026 (o D10)
    BEGIN
        a := pg_temp.doc('v11-ed-26', 'v11-ed', 'GLOBAL', 2026, NULL, NULL, 'INDEXADO');
        v2 := pg_temp.doc('v11-ed-27', 'v11-ed', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        PERFORM fn_publicar_documento(v2);
        v_ok_edicoes := (SELECT status FROM documento WHERE id = a) = 'INDEXADO' AND (SELECT versao FROM documento WHERE id = v2) = 1
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = a AND ativo) = 1;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'publicar o manual de 2027 não toca o de 2026', coalesce(v_ok_edicoes, FALSE));

    -- A versão mais velha que termina depois da mais nova já indexada nasce substituída
    BEGIN
        v1 := pg_temp.doc('v11-vel', 'v11-vel', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        v2 := pg_temp.doc('v11-vel-v2', 'v11-vel', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        PERFORM fn_publicar_documento(v1);
        v_ok_antiga := (SELECT status FROM documento WHERE id = v1) = 'SUBSTITUIDO' AND (SELECT status FROM documento WHERE id = v2) = 'INDEXADO'
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = v1 AND ativo) = 0
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = v2 AND ativo) = 1;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'a versão velha que termina depois da nova fica substituída, sem trecho ativo', coalesce(v_ok_antiga, FALSE));

    -- Revogar desliga os trechos na hora
    BEGIN
        a := pg_temp.doc('v11-rev', 'v11-rev', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        UPDATE documento SET status = 'REVOGADO', revogado_por = pg_temp.rbs(), revogado_em = now() WHERE id = a;
        v_ok_revogacao := (SELECT count(*) FROM documento_trecho WHERE documento_id = a AND ativo) = 0
            AND (SELECT count(*) FROM documento_trecho WHERE documento_id = a) = 1;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 PUBLICAR', 'revogar o documento desliga os trechos na hora (e não os apaga)', coalesce(v_ok_revogacao, FALSE));

    -- O trecho copia do documento o escopo, a edição, a cota, a parceria e a versão, qualquer que seja o valor dado
    BEGIN
        a := pg_temp.doc('v11-cp', 'v11-cp', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'AGUARDANDO');
        UPDATE documento SET status = 'PROCESSANDO', aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = a;
        INSERT INTO documento_trecho (documento_id, escopo, evento_id, cota_tipo_id, parceria_id, versao, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding)
        VALUES (a, 'GLOBAL', pg_temp.ev(2026), 1, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027), 99, 1, 1, 1, 'x', 1, pg_temp.vetor());
        v_ok_copia := (SELECT (t.escopo, t.evento_id, t.cota_tipo_id, t.parceria_id, t.versao) IS NOT DISTINCT FROM (d.escopo, d.evento_id, d.cota_tipo_id, d.parceria_id, d.versao)
                         FROM documento_trecho t JOIN documento d ON d.id = t.documento_id WHERE t.documento_id = a);
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V11 TRECHO', 'o trecho copia o escopo, a edição, a cota, a parceria e a versão do documento', coalesce(v_ok_copia, FALSE));

    PERFORM pg_temp.recusa('V11 TRECHO', 'trocar o escopo de um trecho', 'rn_documento_trecho_coerente', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t20', 'v11-t20', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'PROCESSANDO');
        BEGIN
            UPDATE documento_trecho SET escopo = 'GLOBAL', parceria_id = NULL WHERE documento_id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 TRECHO', 'mover um trecho para outro documento', 'rn_documento_trecho_coerente', $q$
        DO $d$
        DECLARE
            v_de BIGINT := pg_temp.doc('v11-t21', 'v11-t21', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'PROCESSANDO');
            v_para BIGINT := pg_temp.doc('v11-t21b', 'v11-t21b', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        BEGIN
            UPDATE documento_trecho SET documento_id = v_para WHERE documento_id = v_de;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 TRECHO', 'trecho ativo de documento que não está indexado', 'rn_documento_trecho_ativo', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t22', 'v11-t22', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        BEGIN
            UPDATE documento_trecho SET ativo = TRUE WHERE documento_id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 TRECHO', 'dois trechos com a mesma ordem no mesmo documento', 'uq_documento_trecho_ordem', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t23', 'v11-t23', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        BEGIN
            INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding)
            VALUES (v_id, 1, 1, 1, 'repetido', 1, pg_temp.vetor());
        END $d$ $q$);
    PERFORM pg_temp.recusa('V11 TRECHO', 'trecho que termina antes de começar', 'ck_documento_trecho_paginas', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v11-t24', 'v11-t24', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        BEGIN
            INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding)
            VALUES (v_id, 2, 3, 2, 'invertido', 1, pg_temp.vetor());
        END $d$ $q$);
END $$;

-- RLS: o que o atlas_ro enxerga. Sem contexto, nada; com o contexto do Amarelo em 2027, só o que é dele. Os documentos são montados
-- como o dono do banco (que a RLS não alcança) e lidos depois de SET LOCAL ROLE atlas_ro.
DO $$
DECLARE
    v_amarelo BIGINT := (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027);
    v_evento27 BIGINT := pg_temp.ev(2027);
    v_master SMALLINT := (SELECT id FROM cota_tipo WHERE codigo = 'MASTER');
    v_subcota_g SMALLINT := (SELECT id FROM cota_tipo WHERE codigo = 'SUBCOTA_G');
    v_roxo BIGINT := (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027);
    v_evento26 BIGINT := pg_temp.ev(2026);
    v_revogado BIGINT;
    n_nunca_definido INT; n_texto_vazio INT; n_documentos_visiveis INT;
    v_visiveis TEXT[]; v_visiveis_da_cota_g TEXT[]; v_visiveis_de_2026 TEXT[]; n_sem_parceria INT;
BEGIN
    BEGIN
        PERFORM pg_temp.doc('rls-global-2027', 'rls-global', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        PERFORM pg_temp.doc('rls-global-2026', 'rls-global', 'GLOBAL', 2026, NULL, NULL, 'INDEXADO');
        PERFORM pg_temp.doc('rls-cota-master-2027', 'rls-cota', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO');
        PERFORM pg_temp.doc('rls-cota-subcota-g-2027', 'rls-cota', 'COTA', 2027, 'SUBCOTA_G', NULL, 'INDEXADO');
        PERFORM pg_temp.doc('rls-cota-subcota-2027', 'rls-cota', 'COTA', 2027, 'SUBCOTA', NULL, 'INDEXADO');
        PERFORM pg_temp.doc('rls-parceria-amarelo-2027', 'rls-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO');
        PERFORM pg_temp.doc('rls-parceria-roxo-2027', 'rls-anexo', 'PARCERIA', 2027, NULL, 'Cliente Roxo', 'INDEXADO');
        PERFORM pg_temp.doc('rls-parceria-verde-2027', 'rls-anexo', 'PARCERIA', 2027, NULL, 'Cliente Verde', 'INDEXADO');
        PERFORM pg_temp.doc('rls-aguardando-2027', 'rls-aguardando', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO');
        PERFORM pg_temp.doc('rls-processando-2027', 'rls-processando', 'GLOBAL', 2027, NULL, NULL, 'PROCESSANDO');
        v_revogado := pg_temp.doc('rls-revogado-2027', 'rls-revogado', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        UPDATE documento SET status = 'REVOGADO', revogado_por = pg_temp.rbs(), revogado_em = now() WHERE id = v_revogado;

        SET LOCAL ROLE atlas_ro;
        -- 1. Sem contexto (o parâmetro nunca foi definido nesta sessão): não vê nada.
        SELECT count(*) INTO n_nunca_definido FROM documento_trecho WHERE texto LIKE 'Trecho de rls-%';
        -- 2. Depois de um set_config(..., true) o parâmetro fica como texto vazio: continua sem ver nada, e sem erro de conversão.
        PERFORM set_config('app.evento_id', '', true);
        PERFORM set_config('app.cota_tipo_id', '', true);
        PERFORM set_config('app.parceria_id', '', true);
        SELECT count(*) INTO n_texto_vazio FROM documento_trecho WHERE texto LIKE 'Trecho de rls-%';
        -- 3. O contexto do Amarelo em 2027 (Master).
        PERFORM set_config('app.evento_id', v_evento27::text, true);
        PERFORM set_config('app.cota_tipo_id', v_master::text, true);
        PERFORM set_config('app.parceria_id', v_amarelo::text, true);
        SELECT array_agg(substr(texto, 11) ORDER BY texto) INTO v_visiveis FROM documento_trecho WHERE texto LIKE 'Trecho de rls-%';
        SELECT count(*) INTO n_documentos_visiveis FROM documento WHERE titulo LIKE 'rls-%';
        -- 4. Sem a parceria no contexto, o anexo de ninguém aparece; a cota e a edição continuam valendo.
        PERFORM set_config('app.parceria_id', '', true);
        SELECT count(*) INTO n_sem_parceria FROM documento_trecho WHERE texto LIKE 'Trecho de rls-parceria%';
        -- 5. A Subcota G (o Roxo) em 2027, sem a parceria Amarelo.
        PERFORM set_config('app.cota_tipo_id', v_subcota_g::text, true);
        PERFORM set_config('app.parceria_id', v_roxo::text, true);
        SELECT array_agg(substr(texto, 11) ORDER BY texto) INTO v_visiveis_da_cota_g FROM documento_trecho WHERE texto LIKE 'Trecho de rls-%';
        -- 6. A edição de 2026: só o global de 2026.
        PERFORM set_config('app.evento_id', v_evento26::text, true);
        SELECT array_agg(substr(texto, 11) ORDER BY texto) INTO v_visiveis_de_2026 FROM documento_trecho WHERE texto LIKE 'Trecho de rls-%';
        RESET ROLE;
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.confere('V11 RLS', 'sem contexto (parâmetro nunca definido), o atlas_ro não vê nenhum trecho', 0, n_nunca_definido);
    PERFORM pg_temp.confere('V11 RLS', 'com o contexto vazio (depois de um set_config local), não vê nada e não dá erro de conversão', 0, n_texto_vazio);
    PERFORM pg_temp.afirma('V11 RLS', 'no contexto do Amarelo em 2027 (Master) vê o GLOBAL de 2027, a COTA Master e o anexo do Amarelo, e só isso',
        v_visiveis IS NOT DISTINCT FROM ARRAY['rls-cota-master-2027', 'rls-global-2027', 'rls-parceria-amarelo-2027']);
    PERFORM pg_temp.confere('V11 RLS', 'a tabela documento mostra os mesmos 3 (indexados): nem aguardando, processando, revogado, 2026, outra cota ou outra parceria', 3, n_documentos_visiveis);
    PERFORM pg_temp.confere('V11 RLS', 'sem a parceria no contexto, nenhum anexo de parceria aparece', 0, n_sem_parceria);
    PERFORM pg_temp.afirma('V11 RLS', 'no contexto do Roxo (Subcota G) vê o GLOBAL, a COTA Subcota G e o anexo do Roxo, e não o do Amarelo nem o do Verde',
        v_visiveis_da_cota_g IS NOT DISTINCT FROM ARRAY['rls-cota-subcota-g-2027', 'rls-global-2027', 'rls-parceria-roxo-2027']);
    PERFORM pg_temp.afirma('V11 RLS', 'no contexto de 2026 vê só o GLOBAL de 2026',
        v_visiveis_de_2026 IS NOT DISTINCT FROM ARRAY['rls-global-2026']);

    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: SELECT em parceria', 'atlas_ro', 'SELECT count(*) FROM parceria');
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: SELECT em arquivo', 'atlas_ro', 'SELECT count(*) FROM arquivo');
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: SELECT em usuario', 'atlas_ro', 'SELECT count(*) FROM usuario');
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: SELECT em dado_informado', 'atlas_ro', 'SELECT count(*) FROM dado_informado');
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: INSERT em documento_trecho', 'atlas_ro', $q$
        INSERT INTO documento_trecho (documento_id, ordem, pagina_inicio, pagina_fim, texto, tokens, embedding)
        VALUES (1, 1, 1, 1, 'x', 1, NULL) $q$);
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: UPDATE em documento', 'atlas_ro', $q$ UPDATE documento SET titulo = 'x' $q$);
    PERFORM pg_temp.nega('V11 RLS', 'atlas_ro: DELETE em documento_trecho', 'atlas_ro', 'DELETE FROM documento_trecho');
END $$;

-- ---------------------------------------------------------------------
-- 11. V12: as fontes que a resposta cita, o documento congelado e o plural da busca
-- ---------------------------------------------------------------------
-- Uma mensagem do assistente numa conversa do Amarelo (ou, com p_papel, de outro papel) e o trecho de um documento indexado.
CREATE OR REPLACE FUNCTION pg_temp.msg(p_papel TEXT DEFAULT 'assistant') RETURNS BIGINT LANGUAGE plpgsql AS $f$
DECLARE v_conversa BIGINT; v_msg BIGINT;
BEGIN
    INSERT INTO atlas_conversa (parceria_id, usuario_id)
    SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
    RETURNING id INTO v_conversa;
    INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (v_conversa, 1, p_papel, 'texto') RETURNING id INTO v_msg;
    RETURN v_msg;
END $f$;
CREATE OR REPLACE FUNCTION pg_temp.trecho_de(p_documento BIGINT) RETURNS BIGINT LANGUAGE sql AS $f$
    SELECT id FROM documento_trecho WHERE documento_id = p_documento ORDER BY ordem LIMIT 1
$f$;

-- Estrutura
DO $$
BEGIN
    PERFORM pg_temp.afirma('V12 ESTRUTURA', 'a tabela atlas_fonte existe e a amostra não traz fonte nenhuma',
        (SELECT count(*) = 0 FROM atlas_fonte));
    PERFORM pg_temp.afirma('V12 ESTRUTURA', 'os gatilhos rn_atlas_fonte_mensagem e rn_documento_congelado existem',
        (SELECT count(*) = 2 FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('rn_atlas_fonte_mensagem', 'rn_documento_congelado')));
    PERFORM pg_temp.afirma('V12 ESTRUTURA', 'a coluna tsv continua STORED e com o índice GIN, refeitos com a função nova',
        (SELECT attgenerated = 's' FROM pg_attribute WHERE attrelid = 'documento_trecho'::regclass AND attname = 'tsv')
        AND EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'documento_trecho' AND indexdef LIKE '%USING gin (tsv)%'));
    PERFORM pg_temp.afirma('V12 ESTRUTURA', 'o kit indexado continua achável pela coluna tsv refeita (o tsv não ficou vazio)',
        (SELECT count(*) FILTER (WHERE tsv = ''::tsvector) = 0 FROM documento_trecho));
    PERFORM pg_temp.afirma('V12 ESTRUTURA', 'o atlas_ro não lê atlas_fonte (ela é do portal)',
        NOT has_table_privilege('atlas_ro', 'atlas_fonte', 'SELECT'));
END $$;

-- atlas_fonte: o que a resposta cita
DO $$
BEGIN
    PERFORM pg_temp.aceita('V12 FONTE', 'uma fonte de trecho com título e seção em dados', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-ok', 'v12-ok', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina, dados)
            VALUES (pg_temp.msg(), 'T1', 'TRECHO', pg_temp.trecho_de(v_doc), v_doc, 1, 1, '{"titulo": "v12-ok", "secao": "1 Teste"}');
        END $d$ $q$);
    PERFORM pg_temp.aceita('V12 FONTE', 'uma fonte de registro (entidade e id) e uma de conta (dados)', $q$
        DO $d$
        DECLARE v_msg BIGINT := pg_temp.msg();
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, registro_entidade, registro_id) VALUES (v_msg, 'R1', 'REGISTRO', 'entrega', 1);
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (v_msg, 'C1', 'CALCULO', '{"script": "34 * 312", "resultado": "10608"}');
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'fonte numa mensagem da pessoa (só o assistente cita)', 'rn_atlas_fonte_mensagem', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg('user'), 'C1', 'CALCULO', '{}') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'fonte numa mensagem de ferramenta', 'rn_atlas_fonte_mensagem', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg('tool'), 'C1', 'CALCULO', '{}') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'o mesmo marcador duas vezes na mesma mensagem', 'uq_atlas_fonte_marcador', $q$
        DO $d$
        DECLARE v_msg BIGINT := pg_temp.msg();
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (v_msg, 'C1', 'CALCULO', '{}');
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (v_msg, 'C1', 'CALCULO', '{}');
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'marcador que não é T, R ou C seguido de número (X1)', 'atlas_fonte_marcador_check', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg(), 'X1', 'CALCULO', '{}') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'marcador com zero (T0) ou minúsculo (t1)', 'atlas_fonte_marcador_check', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg(), 't1', 'CALCULO', '{}') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'tipo fora de TRECHO, REGISTRO e CALCULO', 'atlas_fonte_tipo_check', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg(), 'C1', 'OUTRO', '{}') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'a letra do marcador não é a do tipo (R1 num TRECHO)', 'ck_atlas_fonte_tipo', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-t3', 'v12-t3', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina)
            VALUES (pg_temp.msg(), 'R1', 'TRECHO', pg_temp.trecho_de(v_doc), v_doc, 1, 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'fonte de trecho sem o trecho', 'rn_atlas_fonte_mensagem', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, documento_id, versao, pagina) VALUES (pg_temp.msg(), 'T1', 'TRECHO', 1, 1, 1) $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'conta com trecho colado', 'ck_atlas_fonte_tipo', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-t4', 'v12-t4', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id) VALUES (pg_temp.msg(), 'C1', 'CALCULO', pg_temp.trecho_de(v_doc));
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'registro sem a entidade ou sem o id', 'ck_atlas_fonte_tipo', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, registro_entidade) VALUES (pg_temp.msg(), 'R1', 'REGISTRO', 'entrega') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'dados que não é um objeto', 'atlas_fonte_dados_check', $q$
        INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, dados) VALUES (pg_temp.msg(), 'C1', 'CALCULO', '[1, 2]') $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'trecho que não é do documento citado', 'rn_atlas_fonte_mensagem', $q$
        DO $d$
        DECLARE v_um BIGINT := pg_temp.doc('v12-t6a', 'v12-t6a', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
                v_outro BIGINT := pg_temp.doc('v12-t6b', 'v12-t6b', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina)
            VALUES (pg_temp.msg(), 'T1', 'TRECHO', pg_temp.trecho_de(v_um), v_outro, 1, 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'página que o trecho não tem (a 7 num trecho da página 1)', 'rn_atlas_fonte_mensagem', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-t7', 'v12-t7', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina)
            VALUES (pg_temp.msg(), 'T1', 'TRECHO', pg_temp.trecho_de(v_doc), v_doc, 1, 7);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 FONTE', 'versão do trecho trocada na citação', 'rn_atlas_fonte_mensagem', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-t8', 'v12-t8', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO atlas_fonte (mensagem_id, marcador, tipo, trecho_id, documento_id, versao, pagina)
            VALUES (pg_temp.msg(), 'T1', 'TRECHO', pg_temp.trecho_de(v_doc), v_doc, 2, 1);
        END $d$ $q$);
END $$;

-- documento congelado
DO $$
BEGIN
    PERFORM pg_temp.recusa('V12 CONGELADO', 'trocar o escopo de um documento ativo', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c1', 'v12-c1', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET escopo = 'COTA' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'tornar global o anexo de uma parceria', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c2', 'v12-c2', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO');
        BEGIN
            UPDATE documento SET escopo = 'GLOBAL', parceria_id = NULL, arquivo_id = pg_temp.arq() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'trocar a chave', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c3', 'v12-c3', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET chave = 'manual-operacoes' WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'trocar a versão', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c4', 'v12-c4', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET versao = 9 WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'trocar a edição', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c5', 'v12-c5', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET evento_id = pg_temp.ev(2026) WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'pôr uma cota num documento global', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c6', 'v12-c6', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET cota_tipo_id = (SELECT id FROM cota_tipo WHERE codigo = 'MASTER') WHERE id = v_id;
        END $d$ $q$);
    -- A parceria já é guardada pelo rn_documento_coerente (o arquivo é da parceria); aqui o gatilho de coerência é desligado, dentro da
    -- tentativa que é desfeita, para provar que o congelado a guarda sozinho.
    PERFORM pg_temp.recusa('V12 CONGELADO', 'trocar a parceria do anexo (com a coerência desligada)', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v12-c7', 'v12-c7', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO');
        BEGIN
            ALTER TABLE documento DISABLE TRIGGER rn_documento_coerente;
            UPDATE documento SET parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027) WHERE id = v_doc;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'tirar a suspeita de dado pessoal', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c8', 'v12-c8', 'GLOBAL', 2027, NULL, NULL, 'AGUARDANDO', TRUE);
        BEGIN
            UPDATE documento SET contem_dados_pessoais = FALSE WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'o contorno de antes: aprovar tirando a suspeita no mesmo UPDATE', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c9', 'v12-c9', 'PARCERIA', 2027, NULL, 'Cliente Verde', 'AGUARDANDO', TRUE);
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', contem_dados_pessoais = FALSE, aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS',
                             aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.recusa('V12 CONGELADO', 'o contorno de antes: trocar o escopo e aprovar no mesmo UPDATE', 'rn_documento_congelado', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c10', 'v12-c10', 'PARCERIA', 2027, NULL, 'Cliente Verde', 'AGUARDANDO', TRUE);
        BEGIN
            UPDATE documento SET status = 'PROCESSANDO', escopo = 'GLOBAL', parceria_id = NULL, arquivo_id = pg_temp.arq(),
                             aprovado_por = pg_temp.rbs(), aprovado_por_perfil = 'ADMIN_RBS', aprovado_em = now() WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V12 CONGELADO', 'o que continua mudando: título, tipo, erro, modelo e as colunas da ingestão', $q$
        DO $d$
        DECLARE v_id BIGINT := pg_temp.doc('v12-c11', 'v12-c11', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET titulo = 'v12-novo', tipo = 'FAQ', paginas = 3, modelo_embedding = 'outro', dimensoes = 768, extracao_ms = 5 WHERE id = v_id;
        END $d$ $q$);
    PERFORM pg_temp.aceita('V12 CONGELADO', 'a revogação e a publicação seguem como antes (status e trechos)', $q$
        DO $d$
        DECLARE v_um BIGINT := pg_temp.doc('v12-c12', 'v12-c12', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
                v_dois BIGINT := pg_temp.doc('v12-c12', 'v12-c12', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET status = 'REVOGADO', revogado_por = pg_temp.rbs(), revogado_em = now() WHERE id = v_dois;
            IF (SELECT status FROM documento WHERE id = v_um) <> 'SUBSTITUIDO' THEN
                RAISE EXCEPTION 'a v2 devia ter substituído a v1';
            END IF;
        END $d$ $q$);
END $$;

-- O plural da busca: singular e plural casam nos dois sentidos, e as palavras que não são plural não mudam
DO $$
DECLARE
    v_par TEXT[];
    v_doc BIGINT;
    v_visto BOOLEAN;
BEGIN
    FOREACH v_par SLICE 1 IN ARRAY ARRAY[
        ['animal', 'animais'], ['nacional', 'nacionais'], ['final', 'finais'], ['social', 'sociais'], ['geral', 'gerais'],
        ['principal', 'principais'], ['pessoal', 'pessoais'], ['profissional', 'profissionais'], ['canal', 'canais'],
        ['real', 'reais'], ['moral', 'morais'], ['corporal', 'corporais'], ['papel', 'papéis'],
        ['hotel', 'hotéis'], ['inflamável', 'inflamáveis'], ['reciclável', 'recicláveis'], ['possível', 'possíveis'],
        ['útil', 'úteis'], ['cão', 'cães'], ['pão', 'pães'], ['credencial', 'credenciais'], ['material', 'materiais'],
        ['informação', 'informações'], ['inserção', 'inserções']] LOOP
        PERFORM pg_temp.afirma('V12 BUSCA', v_par[1] || ' e ' || v_par[2] || ' casam, no singular e no plural', pg_temp.casa(v_par[1], v_par[2]));
    END LOOP;
    PERFORM pg_temp.afirma('V12 BUSCA', 'mais, demais, jamais, pais, cais e vais não mudam (nem viram mal, dem, jam ou pai)',
        (SELECT bool_and(fn_texto_de_busca(p) = p) FROM unnest(ARRAY['mais', 'demais', 'jamais', 'pais', 'cais', 'vais']) AS p));
    PERFORM pg_temp.afirma('V12 BUSCA', 'reis, leis, bois e dois (plural de -ei, -oi) também não mudam',
        (SELECT bool_and(fn_texto_de_busca(p) = p) FROM unnest(ARRAY['reis', 'leis', 'bois', 'dois', 'gás', 'país']) AS p));
    PERFORM pg_temp.afirma('V12 BUSCA', 'só o plural muda: aqui, mesmo e ainda ficam, jornais vira jornal',
        fn_texto_de_busca('aqui mesmo ainda jornais') = 'aqui mesmo ainda jornal');
    PERFORM pg_temp.afirma('V12 BUSCA', 'sais passa a ser o plural de sal', fn_texto_de_busca('sais') = 'sal');
    PERFORM pg_temp.afirma('V12 BUSCA', 'mais, demais, jamais, pais e cais não viram mal, jamal, pal nem cal (o -ais não vira -al neles)',
        NOT pg_temp.casa('mal', 'mais') AND NOT pg_temp.casa('mal', 'demais') AND NOT pg_temp.casa('jamal', 'jamais')
        AND NOT pg_temp.casa('pal', 'pais') AND NOT pg_temp.casa('cal', 'cais'));
    PERFORM pg_temp.afirma('V12 BUSCA', 'a função continua IMMUTABLE e PARALLEL SAFE (a coluna gerada a usa)',
        (SELECT provolatile = 'i' AND proparallel = 's' FROM pg_proc WHERE proname = 'fn_texto_de_busca' AND pronamespace = 'public'::regnamespace));
    -- A coluna tsv gravada usa a regra nova: um trecho com "animais" é achado pela consulta "animal"
    v_visto := NULL;
    BEGIN
        v_doc := pg_temp.doc('animais de estimação e papéis', 'v12-busca', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        v_visto := (SELECT tsv @@ plainto_tsquery('pt_sem_acento', fn_texto_de_busca('animal papel')) FROM documento_trecho WHERE documento_id = v_doc);
        RAISE EXCEPTION 'desfazer' USING ERRCODE = 'ZZ001';
    EXCEPTION WHEN SQLSTATE 'ZZ001' THEN NULL;
    END;
    PERFORM pg_temp.afirma('V12 BUSCA', 'o tsv gravado do trecho "animais ... papéis" casa com a consulta "animal papel"', coalesce(v_visto, FALSE));
END $$;

-- ---------------------------------------------------------------------
-- 12. V13: os prazos da edição, da cota e da parceria
-- ---------------------------------------------------------------------
-- Estrutura
DO $$
BEGIN
    PERFORM pg_temp.afirma('V13 ESTRUTURA', 'a tabela prazo existe',
        EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'prazo'));
    PERFORM pg_temp.afirma('V13 ESTRUTURA', 'os gatilhos rn_prazo_coerente e rn_prazo_imutavel existem',
        (SELECT count(*) = 2 FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('rn_prazo_coerente', 'rn_prazo_imutavel')));
    PERFORM pg_temp.afirma('V13 ESTRUTURA', 'o atlas_ro não lê prazo (ele é do portal: a ferramenta lê como o resto do portal)',
        NOT has_table_privilege('atlas_ro', 'prazo', 'SELECT'));
    PERFORM pg_temp.afirma('V13 ESTRUTURA', 'a hora é TIME(0) e a data é DATE',
        (SELECT bool_and(CASE column_name WHEN 'hora' THEN data_type = 'time without time zone' AND datetime_precision = 0
                                          WHEN 'data' THEN data_type = 'date' END)
           FROM information_schema.columns WHERE table_name = 'prazo' AND column_name IN ('hora', 'data')));
END $$;

-- As regras do prazo
DO $$
BEGIN
    PERFORM pg_temp.aceita('V13 PRAZO', 'prazo GLOBAL da edição, sem documento', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-global', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.aceita('V13 PRAZO', 'prazo COTA com hora e descrição', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, hora, descricao, criado_por)
        VALUES (pg_temp.ev(2027), 'COTA', 1, 'v13-cota', DATE '2026-12-04', TIME '18:00', 'Entregar o briefing.', pg_temp.rbs()) $q$);
    PERFORM pg_temp.aceita('V13 PRAZO', 'prazo PARCERIA do Amarelo em 2027', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-parceria', DATE '2026-12-04', pg_temp.rbs()) $q$);
    -- O escopo
    PERFORM pg_temp.recusa('V13 PRAZO', 'GLOBAL com cota', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 1, 'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'GLOBAL com parceria', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'COTA sem cota', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'COTA', 'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'COTA com parceria', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, parceria_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'COTA', 1, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'PARCERIA sem parceria', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', 'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'PARCERIA com cota', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, parceria_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', 1, (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'escopo fora da lista', 'ck_prazo_escopo', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'TODOS', 'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    -- Os campos
    PERFORM pg_temp.recusa('V13 PRAZO', 'título em branco', 'prazo_titulo_check', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', '   ', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'data antes do ano 2000', 'prazo_data_check', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '1999-12-31', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'data depois do ano 2100', 'prazo_data_check', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2101-01-01', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'hora com segundos', 'ck_prazo_hora', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, hora, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', TIME '18:00:30', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'descrição em branco', 'prazo_descricao_check', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, descricao, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', '  ', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'página sem documento', 'ck_prazo_pagina', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, pagina, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', 3, pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'página 0 (a primeira é a 1)', 'ck_prazo_pagina', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, pagina, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-pagina-zero', 'v13-pagina-zero', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO'), 0, pg_temp.rbs()) $q$);
    -- Quem cadastra é da RBS
    PERFORM pg_temp.recusa('V13 PERMISSAO', 'prazo criado por um Admin Patrocinador', 'fk_prazo_criador', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')) $q$);
    PERFORM pg_temp.recusa('V13 PERMISSAO', 'perfil de quem criou que não é ADMIN_RBS', 'prazo_criado_por_perfil_check', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por, criado_por_perfil)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example'),
                'ADMIN_PATROCINADOR') $q$);
END $$;

-- A parceria, o documento e a página batem com o prazo; o prazo não tem alcance maior que o do documento
DO $$
BEGIN
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'prazo de 2026 com a parceria do Amarelo de 2027', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, criado_por)
        VALUES (pg_temp.ev(2026), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04', pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'prazo de 2027 com um documento de 2026', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-doc-2026', 'v13-doc-2026', 'GLOBAL', 2026, NULL, NULL, 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 PRAZO', 'página maior que as páginas do documento', 'rn_prazo_coerente', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v13-tres-paginas', 'v13-tres-paginas', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET paginas = 3 WHERE id = v_doc;
            INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, pagina, criado_por)
            VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', v_doc, 4, pg_temp.rbs());
        END $d$ $q$);
    PERFORM pg_temp.aceita('V13 PRAZO', 'a última página do documento serve', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v13-tres-paginas', 'v13-tres-paginas', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            UPDATE documento SET paginas = 3 WHERE id = v_doc;
            INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, pagina, criado_por)
            VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04', v_doc, 3, pg_temp.rbs());
        END $d$ $q$);
    -- Documento GLOBAL: o prazo pode ser de qualquer alcance
    PERFORM pg_temp.aceita('V13 PRAZO', 'documento GLOBAL: prazo da edição, da cota e de uma parceria', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v13-global', 'v13-global', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO');
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, criado_por)
            VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-a', DATE '2026-12-04', v_doc, pg_temp.rbs());
            INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, documento_id, criado_por)
            VALUES (pg_temp.ev(2027), 'COTA', 2, 'v13-b', DATE '2026-12-04', v_doc, pg_temp.rbs());
            INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, documento_id, criado_por)
            VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027),
                    'v13-c', DATE '2026-12-04', v_doc, pg_temp.rbs());
        END $d$ $q$);
    -- Documento COTA (Master): só prazo da Master ou de uma parceria Master
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'documento da cota Master com prazo da edição inteira', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-plano-master', 'v13-plano-master', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'documento da cota Master com prazo da Subcota G', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'COTA', 2, 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-plano-master', 'v13-plano-master', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'documento da cota Master com prazo da parceria do Roxo (Subcota G)', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-plano-master', 'v13-plano-master', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.aceita('V13 PRAZO', 'documento da cota Master: prazo da Master e da parceria do Amarelo (Master)', $q$
        DO $d$
        DECLARE v_doc BIGINT := pg_temp.doc('v13-plano-master', 'v13-plano-master', 'COTA', 2027, 'MASTER', NULL, 'INDEXADO');
        BEGIN
            INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, documento_id, criado_por)
            VALUES (pg_temp.ev(2027), 'COTA', 1, 'v13-a', DATE '2026-12-04', v_doc, pg_temp.rbs());
            INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, documento_id, criado_por)
            VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                    'v13-b', DATE '2026-12-04', v_doc, pg_temp.rbs());
        END $d$ $q$);
    -- Documento PARCERIA (Amarelo): só prazo daquela parceria
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'anexo do Amarelo com prazo da edição inteira', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-anexo-amarelo', 'v13-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'anexo do Amarelo com prazo da cota Master', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, cota_tipo_id, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'COTA', 1, 'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-anexo-amarelo', 'v13-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.recusa('V13 ISOLAMENTO', 'anexo do Amarelo com prazo da parceria do Roxo', 'rn_prazo_coerente', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Roxo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-anexo-amarelo', 'v13-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO'), pg_temp.rbs()) $q$);
    PERFORM pg_temp.aceita('V13 PRAZO', 'anexo do Amarelo com prazo da parceria do Amarelo', $q$
        INSERT INTO prazo (evento_id, escopo, parceria_id, titulo, data, documento_id, criado_por)
        VALUES (pg_temp.ev(2027), 'PARCERIA', (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027),
                'v13-x', DATE '2026-12-04',
                pg_temp.doc('v13-anexo-amarelo', 'v13-anexo', 'PARCERIA', 2027, NULL, 'Cliente Amarelo', 'INDEXADO'), pg_temp.rbs()) $q$);
END $$;

-- Depois de criado, o prazo só se desativa
DO $$
BEGIN
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'mudar o título', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET titulo = 'v13-outro' WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'mudar a data', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET data = DATE '2026-12-05' WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'mudar a hora', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET hora = TIME '18:00' WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'passar de GLOBAL para uma parceria', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET escopo = 'PARCERIA',
                             parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027)
             WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'trocar o documento de origem', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET documento_id = pg_temp.doc('v13-troca', 'v13-troca', 'GLOBAL', 2027, NULL, NULL, 'INDEXADO') WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'mudar o título junto com a desativação', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET titulo = 'v13-outro', ativo = FALSE WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.recusa('V13 IMUTAVEL', 'reativar um prazo desativado', 'rn_prazo_imutavel', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por, ativo) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs(), FALSE);
            UPDATE prazo SET ativo = TRUE WHERE titulo = 'v13-i';
        END $d$ $q$);
    PERFORM pg_temp.aceita('V13 IMUTAVEL', 'desativar um prazo (e repetir a desativação)', $q$
        DO $d$
        BEGIN
            INSERT INTO prazo (evento_id, escopo, titulo, data, criado_por) VALUES (pg_temp.ev(2027), 'GLOBAL', 'v13-i', DATE '2026-12-04', pg_temp.rbs());
            UPDATE prazo SET ativo = FALSE WHERE titulo = 'v13-i';
            UPDATE prazo SET ativo = FALSE WHERE titulo = 'v13-i';
            IF (SELECT ativo FROM prazo WHERE titulo = 'v13-i') THEN
                RAISE EXCEPTION 'o prazo devia estar desativado';
            END IF;
        END $d$ $q$);
END $$;

-- ---------------------------------------------------------------------
-- 13. V14: o campo de arquivo da planilha da agência (M6)
-- ---------------------------------------------------------------------
-- A V14 só acrescenta uma linha ao catálogo: o tipo ARQUIVO, a coluna dado_informado.arquivo_id e a chave composta que prende o arquivo à
-- parceria são da V7. Aqui se confere a linha nova e que o valor de arquivo funciona como os outros (vigente, um valor só, mesma parceria).
DO $$
BEGIN
    PERFORM pg_temp.confere('V14 CATÁLOGO', 'campos da aba Aplicações (os quatro inteiros e a planilha)', 5,
        (SELECT count(*) FROM campo_catalogo c JOIN aba a ON a.id = c.aba_id WHERE a.codigo = 'APLICACOES'));
    PERFORM pg_temp.confere('V14 CATÁLOGO', 'campos de arquivo no catálogo (só a planilha da agência)', 1,
        (SELECT count(*) FROM campo_catalogo WHERE tipo = 'ARQUIVO'));
    PERFORM pg_temp.afirma('V14 CATÁLOGO', 'a planilha: aba Aplicações, ordem 5, rótulo e ajuda, sem unidade, limites nem campo-limite',
        EXISTS (SELECT 1 FROM campo_catalogo c JOIN aba a ON a.id = c.aba_id
                 WHERE c.chave = 'ativacao.planilha_agencia' AND a.codigo = 'APLICACOES' AND c.tipo = 'ARQUIVO' AND c.ordem = 5
                   AND c.rotulo = 'Planilha da agência' AND c.ajuda ILIKE '%planilha%agência%'
                   AND c.unidade IS NULL AND c.minimo IS NULL AND c.maximo IS NULL AND c.limite_campo IS NULL));
    PERFORM pg_temp.afirma('V14 CATÁLOGO', 'a planilha vem depois dos quatro inteiros na ordem do formulário',
        (SELECT max(ordem) FROM campo_catalogo WHERE tipo = 'INTEIRO') < (SELECT ordem FROM campo_catalogo WHERE chave = 'ativacao.planilha_agencia'));
    PERFORM pg_temp.afirma('V14 CATÁLOGO', 'nenhum campo limita a planilha nem é limitado por ela',
        NOT EXISTS (SELECT 1 FROM campo_catalogo WHERE limite_campo = 'ativacao.planilha_agencia'));
    PERFORM pg_temp.aceita('V14 DADOS', 'a planilha do Amarelo com um arquivo do próprio Amarelo', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.planilha_agencia',
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) LIMIT 1),
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V14 DADOS', 'a planilha do Amarelo com um arquivo da Verde', 'fk_dado_informado_arquivo_parceria', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.planilha_agencia',
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027) LIMIT 1),
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.recusa('V14 DADOS', 'a planilha com o arquivo e um número ao mesmo tempo', 'ck_dado_informado_um_valor', $q$
        INSERT INTO dado_informado (parceria_id, campo_chave, valor_numero, arquivo_id, origem, informado_por)
        SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), 'ativacao.planilha_agencia', 10,
               (SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) LIMIT 1),
               'PORTAL', (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example') $q$);
    PERFORM pg_temp.aceita('V14 DADOS', 'a planilha vigente é o arquivo mais recente, e o anterior fica guardado', $q$
        DO $d$
        DECLARE
            v_parceria BIGINT := (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027);
            v_usuario  BIGINT := (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example');
            v_arquivos BIGINT[] := ARRAY(SELECT a.id FROM arquivo a WHERE a.categoria = 'COMPROVACAO' AND a.parceria_id = v_parceria ORDER BY a.id LIMIT 2);
        BEGIN
            INSERT INTO dado_informado (parceria_id, campo_chave, arquivo_id, origem, informado_por, informado_em)
            VALUES (v_parceria, 'ativacao.planilha_agencia', v_arquivos[1], 'PORTAL', v_usuario, now() - interval '1 hour'),
                   (v_parceria, 'ativacao.planilha_agencia', v_arquivos[2], 'PORTAL', v_usuario, now());
            IF (SELECT arquivo_id FROM vw_dado_vigente WHERE parceria_id = v_parceria AND campo_chave = 'ativacao.planilha_agencia') IS DISTINCT FROM v_arquivos[2] THEN
                RAISE EXCEPTION 'o vigente devia ser o arquivo mais recente';
            END IF;
            IF (SELECT count(*) FROM dado_informado WHERE parceria_id = v_parceria AND campo_chave = 'ativacao.planilha_agencia') <> 2 THEN
                RAISE EXCEPTION 'o arquivo anterior devia continuar guardado';
            END IF;
        END $d$ $q$);
    PERFORM pg_temp.confere('V14 DADOS', 'as tentativas não deixaram planilha nenhuma na amostra', 0,
        (SELECT count(*) FROM dado_informado WHERE campo_chave = 'ativacao.planilha_agencia'));
END $$;

-- ---------------------------------------------------------------------
-- 14. V15: o JEV nos pontos de decisão e a conversa sem parceria (M7, com a reserva do M8)
-- ---------------------------------------------------------------------
-- A V15 só acrescenta colunas e regras: atlas_decisao (ponto, modo, limiar, acao, alvo e turno), atlas_mensagem (fora_do_modelo e
-- sem_modelo) e, para o Atlas da RBS, a conversa e o traço sem parceria (evento_id na conversa, a regra "parceria ou evento" e o gatilho que
-- impede um traço sem parceria numa conversa de parceria).
DO $$
DECLARE
    -- O molde de cada tentativa: uma conversa do Amarelo com uma mensagem da pessoa e uma decisão do JEV com o ponto (%1$s), o modo (%2$s)
    -- e o limiar (%3$s) dados.
    c_decisao CONSTANT TEXT := $c$
        DO $d$
        DECLARE v_conversa BIGINT; v_mensagem BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo) VALUES (v_conversa, 1, 'user', 'oi') RETURNING id INTO v_mensagem;
            INSERT INTO atlas_decisao (mensagem_id, pergunta, escolha, confianca, ponto, modo, limiar, acao, alvo, turno)
            VALUES (v_mensagem, 'intencao', 'saudacao_uso', 0.9876, %1$s, %2$s, %3$s, 'teria respondido sem o modelo', NULL, gen_random_uuid());
        END $d$ $c$;
    -- Uma mensagem (%1$s papel, %2$s fora_do_modelo, %3$s sem_modelo) numa conversa do Amarelo.
    c_mensagem CONSTANT TEXT := $c$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_mensagem (conversa_id, ordem, papel, conteudo, tool_call_id, fora_do_modelo, sem_modelo)
            VALUES (v_conversa, 1, '%1$s', 'texto', CASE WHEN '%1$s' = 'tool' THEN 'call_x' END, %2$s, %3$s);
        END $d$ $c$;
BEGIN
    PERFORM pg_temp.confere('V15 ESTRUTURA', 'as 6 colunas novas de atlas_decisao', 6,
        (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'atlas_decisao'
            AND column_name IN ('ponto', 'modo', 'limiar', 'acao', 'alvo', 'turno')));
    PERFORM pg_temp.afirma('V15 ESTRUTURA', 'fora_do_modelo e sem_modelo: obrigatórias, falsas por padrão',
        (SELECT count(*) = 2 AND bool_and(is_nullable = 'NO' AND column_default = 'false') FROM information_schema.columns
          WHERE table_schema = 'public' AND table_name = 'atlas_mensagem' AND column_name IN ('fora_do_modelo', 'sem_modelo')));
    PERFORM pg_temp.afirma('V15 ESTRUTURA', 'parceria_id deixou de ser obrigatório em atlas_conversa e em atlas_traco',
        (SELECT count(*) = 2 AND bool_and(is_nullable = 'YES') FROM information_schema.columns
          WHERE table_schema = 'public' AND column_name = 'parceria_id' AND table_name IN ('atlas_conversa', 'atlas_traco')));
    PERFORM pg_temp.afirma('V15 ESTRUTURA', 'atlas_conversa.evento_id existe e aponta para evento',
        EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_atlas_conversa_evento' AND contype = 'f' AND conrelid = 'atlas_conversa'::regclass
                  AND confrelid = 'evento'::regclass));
    PERFORM pg_temp.afirma('V15 ESTRUTURA', 'o gatilho rn_atlas_traco_parceria existe',
        EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'rn_atlas_traco_parceria' AND NOT tgisinternal));
    PERFORM pg_temp.confere('V15 ESTRUTURA', 'a amostra não traz decisão do JEV', 0, (SELECT count(*) FROM atlas_decisao));
    PERFORM pg_temp.confere('V15 ESTRUTURA', 'nenhuma conversa da amostra está sem parceria', 0,
        (SELECT count(*) FROM atlas_conversa WHERE parceria_id IS NULL));

    -- atlas_decisao
    PERFORM pg_temp.aceita('V15 DECISAO', 'uma decisão em sombra, com ponto, modo, limiar, ação e turno', format(c_decisao, '''intencao''', '''SOMBRA''', '0.9'));
    PERFORM pg_temp.aceita('V15 DECISAO', 'uma decisão ativa de outro ponto', format(c_decisao, '''fontes''', '''ATIVO''', '0.5'));
    PERFORM pg_temp.recusa('V15 DECISAO', 'ponto fora da lista', 'ck_atlas_decisao_ponto', format(c_decisao, '''inventado''', '''SOMBRA''', '0.9'));
    PERFORM pg_temp.recusa('V15 DECISAO', 'o modo DESLIGADO nunca grava', 'ck_atlas_decisao_modo', format(c_decisao, '''intencao''', '''DESLIGADO''', '0.9'));
    PERFORM pg_temp.recusa('V15 DECISAO', 'limiar acima de 1', 'ck_atlas_decisao_limiar', format(c_decisao, '''intencao''', '''SOMBRA''', '1.5'));

    -- atlas_mensagem
    PERFORM pg_temp.aceita('V15 MENSAGEM', 'a mensagem da pessoa fora do modelo', format(c_mensagem, 'user', 'TRUE', 'FALSE'));
    PERFORM pg_temp.aceita('V15 MENSAGEM', 'a mensagem do assistente fora do modelo e sem modelo', format(c_mensagem, 'assistant', 'TRUE', 'TRUE'));
    PERFORM pg_temp.recusa('V15 MENSAGEM', 'mensagem de ferramenta fora do modelo (deixaria o pedido dela sem resposta)', 'ck_atlas_mensagem_fora_do_modelo',
        format(c_mensagem, 'tool', 'TRUE', 'FALSE'));
    PERFORM pg_temp.recusa('V15 MENSAGEM', 'mensagem da pessoa marcada como sem modelo', 'ck_atlas_mensagem_sem_modelo', format(c_mensagem, 'user', 'FALSE', 'TRUE'));
    PERFORM pg_temp.recusa('V15 MENSAGEM', 'mensagem de ferramenta marcada como sem modelo', 'ck_atlas_mensagem_sem_modelo', format(c_mensagem, 'tool', 'FALSE', 'TRUE'));

    -- A conversa sem parceria
    PERFORM pg_temp.aceita('V15 CONVERSA', 'uma conversa de um evento, sem parceria', $q$
        INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
        VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')) $q$);
    PERFORM pg_temp.recusa('V15 CONVERSA', 'conversa sem parceria e sem evento', 'ck_atlas_conversa_dono', $q$
        INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
        VALUES (NULL, NULL, (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')) $q$);
    PERFORM pg_temp.recusa('V15 CONVERSA', 'conversa de um evento que não existe', 'fk_atlas_conversa_evento', $q$
        INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
        VALUES (NULL, -1, (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example')) $q$);
    PERFORM pg_temp.aceita('V15 TRACO', 'o traço sem parceria de uma conversa sem parceria', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
            VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'))
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            VALUES (NULL, v_conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V15 ISOLAMENTO', 'traço sem parceria numa conversa de parceria', 'rn_atlas_traco_parceria', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, usuario_id)
            SELECT (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'marketing@amarelo.example')
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            VALUES (NULL, v_conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V15 ISOLAMENTO', 'traço com parceria numa conversa sem parceria', 'fk_atlas_traco_conversa', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
            VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'))
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_traco (parceria_id, conversa_id, turno, ordem, etapa, resultado, iniciada_em, duracao_ms)
            VALUES ((SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, gen_random_uuid(), 1, 'LLM_IDA', 'OK', now(), 1);
        END $d$ $q$);
    PERFORM pg_temp.recusa('V15 ISOLAMENTO', 'relatório do Atlas numa conversa sem parceria', 'fk_atlas_relatorio_conversa', $q$
        DO $d$
        DECLARE v_conversa BIGINT;
        BEGIN
            INSERT INTO atlas_conversa (parceria_id, evento_id, usuario_id)
            VALUES (NULL, (SELECT id FROM evento WHERE edicao = 2027), (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'))
            RETURNING id INTO v_conversa;
            INSERT INTO atlas_relatorio (parceria_id, conversa_id, modelo, status, especificacao, criado_por)
            VALUES ((SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027), v_conversa, 'ATIVACAO_EVENTO', 'MONTANDO', '{}',
                    (SELECT id FROM _ver_usr WHERE email = 'b2b@rbs.example'));
        END $d$ $q$);
    PERFORM pg_temp.confere('V15 CONVERSA', 'as tentativas não deixaram conversa sem parceria', 0,
        (SELECT count(*) FROM atlas_conversa WHERE parceria_id IS NULL));
END $$;

-- ---------------------------------------------------------------------
-- 15. Resultado: a lista de conferências e, se alguma falhou, o erro final (saída diferente de zero com ON_ERROR_STOP)
-- ---------------------------------------------------------------------
SELECT grupo, conferencia, esperado, obtido, CASE WHEN ok THEN 'ok' ELSE 'FALHOU' END AS resultado FROM _ver ORDER BY ordem;
SELECT count(*) AS conferencias, count(*) FILTER (WHERE ok) AS ok, count(*) FILTER (WHERE NOT ok) AS falhas FROM _ver;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM _ver WHERE NOT ok) THEN
        RAISE EXCEPTION 'A verificação falhou em % conferência(s): veja as linhas FALHOU na tabela acima.', (SELECT count(*) FROM _ver WHERE NOT ok);
    END IF;
END $$;

DROP VIEW IF EXISTS pg_temp._ver_parc;
DROP VIEW IF EXISTS pg_temp._ver_usr;

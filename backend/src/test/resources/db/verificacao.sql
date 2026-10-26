-- Confere o banco depois de V1, V2, V3 e da amostra R__amostra.sql: contagens, cargas, relatórios, órfãos e uma tentativa de violar cada regra.
-- Atende o item 1 da Entrega 2 (amostra e verificações) e prova as 8 correções do item 8. Nenhuma linha fica gravada: as tentativas são desfeitas (só as sequências avançam).

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
-- 1. Estrutura e dados base (V1, V2, V3)
-- ---------------------------------------------------------------------
DO $$
BEGIN
    -- A tabela de controle do Flyway (flyway_schema_history) não é do portal: fica de fora quando o banco foi criado pelo Flyway.
    PERFORM pg_temp.confere('ESTRUTURA', 'tabelas (29 da modelagem + 4 novas)', 33,
       (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
           AND table_name <> 'flyway_schema_history'));
    PERFORM pg_temp.confere('ESTRUTURA', 'visões de consolidação', 7,
       (SELECT count(*) FROM information_schema.views WHERE table_schema = 'public'));
    PERFORM pg_temp.confere('ESTRUTURA', 'funções fn_carregar_veiculacoes, fn_carregar_pesquisa, fn_gerar_relatorio', 3,
       (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public' AND p.proname IN ('fn_carregar_veiculacoes', 'fn_carregar_pesquisa', 'fn_gerar_relatorio')));
    PERFORM pg_temp.confere('ESTRUTURA', 'gatilhos de regra (rn_*)', 15,
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
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Roxo, inserções: exibido de previsto (%)', 97.1,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Roxo' AND u.unidade = 'INSERCAO'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Roxo, CPM: exibido de previsto (%), sem somar com inserções', 97.3,
        (SELECT u.pct_entrega FROM vw_midia_unidade u JOIN parceria pa ON pa.id = u.parceria_id JOIN patrocinador p ON p.id = pa.patrocinador_id
          WHERE p.nome_canonico = 'Cliente Roxo' AND u.unidade = 'CPM'));
    PERFORM pg_temp.confere('CONSOLIDAÇÃO', 'Amarelo, inserções: exibido de previsto (%)', 99.3,
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
    PERFORM pg_temp.confere('RELATÓRIOS', 'números nos 3 relatórios (72 de antes + 3 faixas de nota da pesquisa)', 75, (SELECT count(*) FROM relatorio_indicador));
    PERFORM pg_temp.confere('RELATÓRIOS', 'números indisponíveis (festival em números e pesquisa geral, só existem em 2026)', 6,
        (SELECT count(*) FROM relatorio_indicador WHERE NOT disponivel));
    PERFORM pg_temp.confere('RELATÓRIOS', 'números do relatório sem as 3 faixas novas = total de antes', 72,
        (SELECT count(*) FROM relatorio_indicador WHERE NOT (indicador_codigo = 'PESQUISA_RESULTADO' AND recorte IN ('P2 · Notas 9 e 10', 'P2 · Notas 7 e 8', 'P2 · Notas de 0 a 6'))));
    PERFORM pg_temp.afirma('RELATÓRIOS', 'data simulada (21/02/2027 11:00) no relatório e no histórico, sem UPDATE',
        (SELECT bool_and(r.gerado_em = timestamptz '2027-02-21 11:00-03') FROM relatorio r)
        AND (SELECT count(*) = 3 AND bool_and(h.ocorrido_em = timestamptz '2027-02-21 11:00-03') FROM historico h WHERE h.acao = 'GERACAO_RELATORIO'));
    PERFORM pg_temp.confere('RELATÓRIOS', 'indicadores disponíveis do catálogo que ficaram sem linha (nunca estimados)', 0,
        (SELECT count(*) FROM relatorio r JOIN parceria pa ON pa.id = r.parceria_id JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
          JOIN indicador i ON (i.cotas IS NULL OR ct.codigo = ANY (i.cotas))
                          AND (i.requer_previsto IS NULL OR EXISTS (SELECT 1 FROM aproveitamento_previsto ap WHERE ap.parceria_id = pa.id AND ap.categoria = i.requer_previsto))
          WHERE NOT EXISTS (SELECT 1 FROM relatorio_indicador ri WHERE ri.relatorio_id = r.id AND ri.indicador_codigo = i.codigo)));
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
    PERFORM pg_temp.confere('ÓRFÃOS', 'materiais de parceria sem aba', 0, (SELECT count(*) FROM arquivo WHERE parceria_id IS NOT NULL AND aba_id IS NULL));
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

-- [ITEM-6] A destinação de cortesias não passa da cota (Amarelo, Arena de sexta: 25 de 25; Verde, Arena de sábado: 2 de 4)
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
    PERFORM pg_temp.recusa('ITEM-6', 'aumentar uma destinação além da cota (Verde: 2 passam a 5 de 4)', 'rn_cortesia_limite', $q$
        UPDATE cortesia_destinacao SET quantidade = 5
        WHERE cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SABADO' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027)) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'mover uma destinação para uma cota que não comporta', 'rn_cortesia_limite', $q$
        UPDATE cortesia_destinacao SET cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SEXTA' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027))
        WHERE cortesia_cota_id = (SELECT id FROM cortesia_cota WHERE setor = 'ARENA' AND dia = 'SABADO' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Verde' AND edicao = 2027)) $q$);
    PERFORM pg_temp.recusa('ITEM-6', 'reduzir a cota abaixo do que já foi destinado', 'rn_cortesia_cota_reducao', $q$
        UPDATE cortesia_cota SET quantidade = 20 WHERE setor = 'ARENA' AND dia = 'SEXTA' AND parceria_id = (SELECT id FROM _ver_parc WHERE cliente = 'Cliente Amarelo' AND edicao = 2027) $q$);
    PERFORM pg_temp.aceita('ITEM-6', 'destinar as 2 cortesias que sobram da Verde (4 de 4)', $q$
        INSERT INTO cortesia_destinacao (cortesia_cota_id, destinatario_nome, quantidade, registrado_por)
        SELECT cc.id, 'Convidados finais', 2, (SELECT id FROM _ver_usr WHERE email = 'marketing@verde.example')
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
-- 8. Resultado: a lista de conferências e, se alguma falhou, o erro final (saída diferente de zero com ON_ERROR_STOP)
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

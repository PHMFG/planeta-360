-- =====================================================================
-- Regras de integridade, consolidação e indicadores (PostgreSQL 16)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Carga de veiculações: valida, vincula, deduplica e registra faltantes
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_carregar_veiculacoes(p_carga_id BIGINT, p_evento_id BIGINT)
RETURNS TABLE (lidas INT, inseridas INT, duplicadas INT, rejeitadas INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_lidas INT; v_ok INT; v_ins INT; v_rej INT;
BEGIN
    SELECT count(*) INTO v_lidas FROM stg_veiculacao WHERE carga_id = p_carga_id;

    -- 1. Campos obrigatórios
    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT s.carga_id, s.linha,
           CASE WHEN s.patrocinador IS NULL THEN 'patrocinador'
                WHEN s.veiculo IS NULL THEN 'veiculo'
                WHEN s.formato IS NULL THEN 'formato'
                WHEN s.exibido_em IS NULL THEN 'exibido_em'
                ELSE 'quantidade' END,
           'Campo obrigatório vazio ou inválido',
           concat_ws(' | ', s.patrocinador, s.veiculo, s.formato, s.espaco_programa, to_char(s.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'))
    FROM stg_veiculacao s
    WHERE s.carga_id = p_carga_id
      AND (s.patrocinador IS NULL OR s.veiculo IS NULL OR s.formato IS NULL
           OR s.exibido_em IS NULL OR coalesce(s.quantidade, 0) <= 0);

    -- 2. Vínculo com patrocinador (nome canônico ou grafia alternativa), parceria e plano
    CREATE TEMP TABLE tmp_vinculo ON COMMIT DROP AS
    SELECT s.linha, s.exibido_em, s.quantidade, s.link,
           pa.id AS parceria_id, ap.id AS aproveitamento_id,
           s.patrocinador, s.veiculo, s.formato, s.espaco_programa
    FROM stg_veiculacao s
    LEFT JOIN patrocinador p
           ON lower(p.nome_canonico) = lower(s.patrocinador)
           OR p.id = (SELECT a.patrocinador_id FROM patrocinador_alias a WHERE lower(a.alias) = lower(s.patrocinador))
    LEFT JOIN parceria pa ON pa.patrocinador_id = p.id AND pa.evento_id = p_evento_id
    LEFT JOIN aproveitamento_previsto ap
           ON ap.parceria_id = pa.id
          AND lower(ap.veiculo) = lower(s.veiculo)
          AND lower(ap.formato) = lower(s.formato)
          AND lower(coalesce(ap.espaco_programa, '')) = lower(coalesce(s.espaco_programa, ''))
    WHERE s.carga_id = p_carga_id
      AND s.patrocinador IS NOT NULL AND s.veiculo IS NOT NULL AND s.formato IS NOT NULL
      AND s.exibido_em IS NOT NULL AND coalesce(s.quantidade, 0) > 0;

    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT p_carga_id, linha,
           CASE WHEN parceria_id IS NULL THEN 'patrocinador' ELSE 'formato' END,
           CASE WHEN parceria_id IS NULL THEN 'Patrocinador sem parceria nesta edição'
                ELSE 'Aproveitamento não encontrado no plano comercial' END,
           concat_ws(' | ', patrocinador, veiculo, formato, espaco_programa, to_char(exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'))
    FROM tmp_vinculo WHERE aproveitamento_id IS NULL;

    SELECT count(*) INTO v_ok FROM tmp_vinculo WHERE aproveitamento_id IS NOT NULL;

    -- 3. Inserção sem contagem duplicada (chave natural: aproveitamento + data/hora)
    INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, link, carga_id)
    SELECT DISTINCT ON (aproveitamento_id, exibido_em)
           aproveitamento_id, exibido_em, quantidade, link, p_carga_id
    FROM tmp_vinculo WHERE aproveitamento_id IS NOT NULL
    ORDER BY aproveitamento_id, exibido_em, linha
    ON CONFLICT ON CONSTRAINT uq_veiculacao DO NOTHING;
    GET DIAGNOSTICS v_ins = ROW_COUNT;

    SELECT count(DISTINCT linha) INTO v_rej FROM carga_rejeicao WHERE carga_id = p_carga_id;

    UPDATE carga SET linhas_lidas = v_lidas, linhas_inseridas = v_ins,
                     linhas_duplicadas = v_ok - v_ins, linhas_rejeitadas = v_rej
    WHERE id = p_carga_id;

    RETURN QUERY SELECT v_lidas, v_ins, v_ok - v_ins, v_rej;
END $$;

-- ---------------------------------------------------------------------
-- Visões de consolidação
-- ---------------------------------------------------------------------
-- Previsto x exibido por veículo, sem nunca somar unidades diferentes
CREATE VIEW vw_midia_veiculo AS
SELECT ap.parceria_id, ap.veiculo, ap.unidade,
       sum(ap.quantidade_prevista)                         AS previsto,
       coalesce(sum(v.exibido), 0)                         AS exibido,
       round(100 * coalesce(sum(v.exibido), 0) / sum(ap.quantidade_prevista), 1) AS pct_entrega
FROM aproveitamento_previsto ap
LEFT JOIN (SELECT aproveitamento_id, sum(quantidade) AS exibido
           FROM veiculacao GROUP BY aproveitamento_id) v ON v.aproveitamento_id = ap.id
WHERE ap.categoria = 'MIDIA'
GROUP BY ap.parceria_id, ap.veiculo, ap.unidade;

CREATE VIEW vw_midia_unidade AS
SELECT parceria_id, unidade, sum(previsto) AS previsto, sum(exibido) AS exibido,
       round(100 * sum(exibido) / sum(previsto), 1) AS pct_entrega
FROM vw_midia_veiculo GROUP BY parceria_id, unidade;

CREATE VIEW vw_entregas_resumo AS
SELECT parceria_id,
       count(*)                                              AS total,
       count(*) FILTER (WHERE status = 'NAO_APLICAVEL')      AS nao_aplicaveis,
       count(*) FILTER (WHERE status = 'APROVADO')           AS aprovadas,
       count(*) FILTER (WHERE status NOT IN ('APROVADO','NAO_APLICAVEL')) AS pendentes,
       round(100.0 * count(*) FILTER (WHERE status = 'APROVADO')
             / nullif(count(*) FILTER (WHERE status <> 'NAO_APLICAVEL'), 0), 1) AS pct_concluidas
FROM entrega GROUP BY parceria_id;

-- Uma aplicação prevista conta como comprovada até o limite da quantidade contratada
CREATE VIEW vw_aplicacoes AS
SELECT ap.parceria_id,
       sum(ap.quantidade_prevista)                                   AS previstas,
       sum(least(ap.quantidade_prevista, coalesce(c.fotos, 0)))      AS comprovadas,
       sum(coalesce(c.fotos, 0))                                     AS fotos
FROM aproveitamento_previsto ap
LEFT JOIN (SELECT aproveitamento_id, count(*) AS fotos FROM comprovacao_marca GROUP BY aproveitamento_id) c
       ON c.aproveitamento_id = ap.id
WHERE ap.categoria = 'APLICACAO_MARCA'
GROUP BY ap.parceria_id;

CREATE VIEW vw_cortesias AS
SELECT cc.parceria_id, sum(cc.quantidade) AS disponiveis, coalesce(sum(d.destinadas), 0) AS destinadas
FROM cortesia_cota cc
LEFT JOIN (SELECT cortesia_cota_id, sum(quantidade) AS destinadas FROM cortesia_destinacao GROUP BY cortesia_cota_id) d
       ON d.cortesia_cota_id = cc.id
GROUP BY cc.parceria_id;

-- Todos os números do relatório, com a base de cálculo de cada um
CREATE VIEW vw_indicadores_parceria AS
SELECT parceria_id, 'ENTREGAS_CONCLUIDAS_PCT' AS indicador_codigo, '' AS recorte, pct_concluidas AS valor,
       aprovadas || ' aprovadas de ' || (total - nao_aplicaveis) || ' aplicáveis' AS base_calculo
FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'ENTREGAS_PENDENTES', '', pendentes, total || ' entregas cadastradas' FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'MIDIA_PREVISTO', unidade, previsto, 'soma do plano comercial' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_EXIBIDO', unidade, exibido, 'veiculações sem duplicidade' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_ENTREGA_PCT', unidade, pct_entrega, trim_scale(exibido) || ' de ' || trim_scale(previsto) FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_VEICULO_PCT', veiculo || ' · ' || unidade, pct_entrega, trim_scale(exibido) || ' de ' || trim_scale(previsto) FROM vw_midia_veiculo
UNION ALL
SELECT parceria_id, 'APLICACOES_PREVISTAS', '', previstas, 'plano comercial' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS', '', comprovadas, trim_scale(fotos) || ' fotos enviadas' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS_PCT', '', round(100 * comprovadas / previstas, 1),
       trim_scale(comprovadas) || ' de ' || trim_scale(previstas) FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'COMPROVACOES_ENVIADAS', '', fotos, 'arquivos de comprovação' FROM vw_aplicacoes
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_BASE', '', max(r.base_respondentes), p.nome
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id JOIN pesquisa p ON p.id = r.pesquisa_id
GROUP BY pp.parceria_id, p.nome
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_RESULTADO', 'P' || pp.ordem || ' · ' || r.alternativa, r.valor,
       'base de ' || r.base_respondentes || ' respondentes (' || lower(r.unidade) || ')'
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id
UNION ALL
SELECT parceria_id, 'CORTESIAS_DISPONIVEIS', '', disponiveis, 'soma por setor e dia' FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CORTESIAS_DESTINADAS_PCT', '', round(100.0 * destinadas / disponiveis, 1),
       destinadas || ' de ' || disponiveis FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CREDENCIAIS_DIA', setor || ' · ' || beneficiario, quantidade_por_dia, 'plano comercial'
FROM credencial_cota
UNION ALL
SELECT pa.id, 'FESTIVAL_NUMERO', er.indicador, er.valor,
       CASE er.qualificador WHEN 'MAIS_DE' THEN 'mais de; ' ELSE '' END || er.fonte
FROM parceria pa JOIN evento_resultado er ON er.evento_id = pa.evento_id
UNION ALL
SELECT pa.id, 'FESTIVAL_PESQUISA', r.indicador || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || r.base_respondentes || ' respondentes'
FROM parceria pa JOIN pesquisa p ON p.evento_id = pa.evento_id AND p.escopo = 'GERAL'
JOIN pesquisa_resultado r ON r.pesquisa_id = p.id AND r.parceria_id IS NULL;

-- Faltantes e inconsistências que impedem ou enfraquecem o relatório
CREATE VIEW vw_faltantes AS
SELECT ap.parceria_id, 'MIDIA_SEM_EXIBICAO' AS tipo,
       ap.veiculo || ' · ' || ap.formato || coalesce(' · ' || ap.espaco_programa, '') AS detalhe
FROM aproveitamento_previsto ap
WHERE ap.categoria = 'MIDIA' AND NOT EXISTS (SELECT 1 FROM veiculacao v WHERE v.aproveitamento_id = ap.id)
UNION ALL
SELECT ap.parceria_id, 'MIDIA_ABAIXO_DO_PREVISTO',
       ap.veiculo || ' · ' || ap.formato || coalesce(' · ' || ap.espaco_programa, '') || ': ' ||
       trim_scale(sum(v.quantidade)) || ' de ' || trim_scale(ap.quantidade_prevista)
FROM aproveitamento_previsto ap JOIN veiculacao v ON v.aproveitamento_id = ap.id
WHERE ap.categoria = 'MIDIA'
GROUP BY ap.id HAVING sum(v.quantidade) < ap.quantidade_prevista
UNION ALL
SELECT ap.parceria_id, 'APLICACAO_SEM_COMPROVACAO', ap.formato || coalesce(' · ' || ap.espaco_programa, '')
FROM aproveitamento_previsto ap
WHERE ap.categoria = 'APLICACAO_MARCA' AND NOT EXISTS (SELECT 1 FROM comprovacao_marca c WHERE c.aproveitamento_id = ap.id)
UNION ALL
SELECT pa.id, 'PESQUISA_SEM_PERGUNTAS', 'Cota Master sem as 3 perguntas cadastradas'
FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
WHERE ct.codigo = 'MASTER' AND (SELECT count(*) FROM pesquisa_pergunta q WHERE q.parceria_id = pa.id) < 3
UNION ALL
SELECT q.parceria_id, 'PERGUNTA_SEM_RESULTADO', 'P' || q.ordem || ': ' || q.texto
FROM pesquisa_pergunta q WHERE NOT EXISTS (SELECT 1 FROM pesquisa_resultado r WHERE r.pergunta_id = q.id)
UNION ALL
SELECT e.parceria_id, 'ENTREGA_PENDENTE', e.titulo || ' (' || lower(replace(e.status, '_', ' ')) || ')'
FROM entrega e WHERE e.status NOT IN ('APROVADO','NAO_APLICAVEL')
UNION ALL
SELECT pa.id, 'PARCERIA_SEM_USUARIO', 'Nenhum usuário do patrocinador vinculado'
FROM parceria pa WHERE NOT EXISTS (SELECT 1 FROM parceria_usuario pu WHERE pu.parceria_id = pa.id)
UNION ALL
SELECT pa.id, 'FESTIVAL_SEM_NUMEROS', 'Números gerais da edição ainda não carregados'
FROM parceria pa WHERE NOT EXISTS (SELECT 1 FROM evento_resultado er WHERE er.evento_id = pa.evento_id);

-- ---------------------------------------------------------------------
-- Geração do relatório: copia os números da base; o que falta fica indisponível
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_gerar_relatorio(p_parceria_id BIGINT, p_usuario_id BIGINT)
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE v_rel BIGINT; v_versao SMALLINT; v_cota VARCHAR(20);
BEGIN
    SELECT coalesce(max(versao), 0) + 1 INTO v_versao FROM relatorio WHERE parceria_id = p_parceria_id;
    SELECT ct.codigo INTO v_cota FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id WHERE pa.id = p_parceria_id;

    INSERT INTO relatorio (parceria_id, versao, gerado_por) VALUES (p_parceria_id, v_versao, p_usuario_id)
    RETURNING id INTO v_rel;

    INSERT INTO relatorio_indicador (relatorio_id, indicador_codigo, recorte, valor, base_calculo, disponivel)
    SELECT v_rel, indicador_codigo, recorte, valor, base_calculo, valor IS NOT NULL
    FROM vw_indicadores_parceria WHERE parceria_id = p_parceria_id;

    -- Indicadores aplicáveis à cota sem dado na base: registrados como indisponíveis, nunca estimados
    INSERT INTO relatorio_indicador (relatorio_id, indicador_codigo, recorte, valor, base_calculo, disponivel)
    SELECT v_rel, i.codigo, '', NULL, 'sem registro na base', FALSE
    FROM indicador i
    WHERE (i.cotas IS NULL OR v_cota = ANY (i.cotas))
      AND (i.requer_previsto IS NULL OR EXISTS (SELECT 1 FROM aproveitamento_previsto ap
                                                WHERE ap.parceria_id = p_parceria_id AND ap.categoria = i.requer_previsto))
      AND NOT EXISTS (SELECT 1 FROM relatorio_indicador ri WHERE ri.relatorio_id = v_rel AND ri.indicador_codigo = i.codigo);

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id)
    VALUES (p_parceria_id, 'relatorio', v_rel, 'GERACAO_RELATORIO', 'versão ' || v_versao || ' (RASCUNHO)', p_usuario_id);

    RETURN v_rel;
END $$;

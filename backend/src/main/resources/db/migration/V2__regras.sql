-- Cria as 7 visões, as funções de carga (veiculação e pesquisa) e de geração do relatório, e os gatilhos das 8 correções.
-- Atende o item 8 da Entrega 2 e sustenta os itens 5 (consolidação) e 6 (relatório).

-- Toda regra de negócio recusada sai com SQLSTATE da classe 23 (check_violation, foreign_key_violation ...) e mensagem em
-- português, e o nome da regra vai em CONSTRAINT (ex.: rn_cortesia_limite). Assim a API traduz a classe 23 em 409/422;
-- um RAISE comum sairia como P0001 e viraria erro 500.

-- ---------------------------------------------------------------------
-- 1. CARGA DE VEICULAÇÕES
-- ---------------------------------------------------------------------
-- [CARGA] [DUPLICIDADE] Valida, vincula, deduplica e registra faltantes. Idempotente: reenviar o mesmo relatório (carga nova
-- com as mesmas linhas) não insere nada e conta tudo como duplicada; chamar de novo para a mesma carga devolve os totais
-- já gravados, sem refazer nada.
-- [ITEM-4] Pode ser chamada várias vezes na mesma transação (Flyway, @Transactional): a tabela temporária é apagada e
-- recriada a cada chamada, em vez de depender do ON COMMIT DROP, que só apaga no fim da transação.
CREATE OR REPLACE FUNCTION fn_carregar_veiculacoes(p_carga_id BIGINT, p_evento_id BIGINT)
RETURNS TABLE (lidas INT, inseridas INT, duplicadas INT, rejeitadas INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_carga carga%ROWTYPE;
    v_lidas INT; v_ok INT; v_ins INT; v_rej INT;
BEGIN
    SELECT * INTO v_carga FROM carga WHERE id = p_carga_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A carga % não existe.', p_carga_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_carga_inexistente';
    END IF;
    IF v_carga.tipo <> 'MIDIA' THEN
        RAISE EXCEPTION 'A carga % é do tipo %, e esta função só processa cargas de mídia.', p_carga_id, v_carga.tipo
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_carga_tipo';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM evento WHERE id = p_evento_id) THEN
        RAISE EXCEPTION 'O evento % não existe.', p_evento_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_evento_inexistente';
    END IF;
    IF v_carga.processada_em IS NOT NULL THEN
        RETURN QUERY SELECT v_carga.linhas_lidas, v_carga.linhas_inseridas, v_carga.linhas_duplicadas, v_carga.linhas_rejeitadas;
        RETURN;
    END IF;

    SELECT count(*) INTO v_lidas FROM stg_veiculacao WHERE carga_id = p_carga_id;

    -- [VALIDACAO] 1. Campos obrigatórios
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

    -- 2. Vínculo com patrocinador (nome canônico ou grafia alternativa), parceria da edição e linha do plano
    DROP TABLE IF EXISTS pg_temp.tmp_vinculo;
    CREATE TEMP TABLE tmp_vinculo ON COMMIT DROP AS
    SELECT s.linha, s.exibido_em, s.quantidade, s.link,
           pa.id AS parceria_id, ap.id AS aproveitamento_id,
           s.patrocinador, s.veiculo, s.formato, s.espaco_programa
    FROM stg_veiculacao s
    LEFT JOIN LATERAL (
         SELECT coalesce(
                    (SELECT p.id FROM patrocinador p WHERE lower(p.nome_canonico) = lower(btrim(s.patrocinador))),
                    (SELECT a.patrocinador_id FROM patrocinador_alias a WHERE lower(a.alias) = lower(btrim(s.patrocinador)))
                ) AS id) pat ON TRUE
    LEFT JOIN parceria pa ON pa.patrocinador_id = pat.id AND pa.evento_id = p_evento_id
    LEFT JOIN LATERAL (
         SELECT x.id FROM aproveitamento_previsto x
         WHERE x.parceria_id = pa.id
           AND lower(btrim(x.veiculo)) = lower(btrim(s.veiculo))
           AND lower(btrim(x.formato)) = lower(btrim(s.formato))
           AND lower(btrim(coalesce(x.espaco_programa, ''))) = lower(btrim(coalesce(s.espaco_programa, '')))
         ORDER BY x.id LIMIT 1) ap ON TRUE
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

    -- [DUPLICIDADE] 3. Inserção sem contagem duplicada. A chave é aproveitamento + data/hora (sem a janela do plano).
    -- DISTINCT ON cobre a repetição dentro do próprio arquivo (fica a linha de menor número); ON CONFLICT cobre o que
    -- já está na base (reenvio). Uma linha reenviada com quantidade ou link corrigido também conta como duplicada:
    -- a primeira versão prevalece.
    INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, link, carga_id)
    SELECT DISTINCT ON (aproveitamento_id, exibido_em)
           aproveitamento_id, exibido_em, quantidade, link, p_carga_id
    FROM tmp_vinculo WHERE aproveitamento_id IS NOT NULL
    ORDER BY aproveitamento_id, exibido_em, linha
    ON CONFLICT ON CONSTRAINT uq_veiculacao DO NOTHING;
    GET DIAGNOSTICS v_ins = ROW_COUNT;

    SELECT count(DISTINCT linha) INTO v_rej FROM carga_rejeicao WHERE carga_id = p_carga_id;

    UPDATE carga SET linhas_lidas = v_lidas, linhas_inseridas = v_ins,
                     linhas_duplicadas = v_ok - v_ins, linhas_rejeitadas = v_rej,
                     evento_id = p_evento_id, processada_em = now()
    WHERE id = p_carga_id;

    DROP TABLE IF EXISTS pg_temp.tmp_vinculo;
    RETURN QUERY SELECT v_lidas, v_ins, v_ok - v_ins, v_rej;
END $$;

-- ---------------------------------------------------------------------
-- 2. CARGA DOS RESULTADOS DA PESQUISA
-- ---------------------------------------------------------------------
-- [CARGA] [DUPLICIDADE] Mesmo espírito da carga de veiculação, para o CSV de resultados agregados (stg_pesquisa).
-- Uma carga trata uma pesquisa (p_pesquisa_id). Na pesquisa da ativação, cada linha precisa de patrocinador e pergunta
-- (1 a 3) e o resultado é ligado à pergunta e à parceria corretas; na pesquisa geral do festival, a linha traz só o
-- indicador. A pergunta precisa estar APROVADA pela RBS (a API tem uma rota de aprovação): assim o resultado não fica preso a um texto que ainda pode mudar.
-- Reenvio não duplica: a chave é a do índice uq_pesquisa_resultado, e quem chega repetido conta como duplicado.
-- Um valor corrigido num reenvio também é ignorado como duplicado; corrigir é um trabalho à parte da RBS.
CREATE OR REPLACE FUNCTION fn_carregar_pesquisa(p_carga_id BIGINT, p_pesquisa_id BIGINT)
RETURNS TABLE (lidas INT, inseridas INT, duplicadas INT, rejeitadas INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_carga carga%ROWTYPE;
    v_pesq  pesquisa%ROWTYPE;
    v_lidas INT; v_ok INT; v_ins INT; v_rej INT;
BEGIN
    SELECT * INTO v_carga FROM carga WHERE id = p_carga_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A carga % não existe.', p_carga_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_carga_inexistente';
    END IF;
    IF v_carga.tipo <> 'PESQUISA' THEN
        RAISE EXCEPTION 'A carga % é do tipo %, e esta função só processa cargas de pesquisa.', p_carga_id, v_carga.tipo
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_carga_tipo';
    END IF;
    SELECT * INTO v_pesq FROM pesquisa WHERE id = p_pesquisa_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A pesquisa % não existe.', p_pesquisa_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_pesquisa_inexistente';
    END IF;
    IF v_carga.processada_em IS NOT NULL THEN
        RETURN QUERY SELECT v_carga.linhas_lidas, v_carga.linhas_inseridas, v_carga.linhas_duplicadas, v_carga.linhas_rejeitadas;
        RETURN;
    END IF;

    SELECT count(*) INTO v_lidas FROM stg_pesquisa WHERE carga_id = p_carga_id;

    -- [VALIDACAO] 1. Valor, unidade e base. Cada linha recebe no máximo um motivo (o primeiro problema encontrado).
    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT s.carga_id, s.linha,
           CASE WHEN s.valor IS NULL THEN 'valor'
                WHEN s.unidade IS NULL OR s.unidade NOT IN ('PERCENTUAL','NOTA','CONTAGEM') THEN 'unidade'
                ELSE 'base_respondentes' END,
           'Campo obrigatório vazio ou inválido',
           concat_ws(' | ', s.patrocinador, s.pergunta::text, s.indicador, s.alternativa, s.valor::text, s.unidade, s.base_respondentes::text)
    FROM stg_pesquisa s
    WHERE s.carga_id = p_carga_id
      AND (s.valor IS NULL OR s.unidade IS NULL OR s.unidade NOT IN ('PERCENTUAL','NOTA','CONTAGEM')
           OR coalesce(s.base_respondentes, 0) <= 0);

    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT s.carga_id, s.linha, 'valor', 'Percentual fora do intervalo de 0 a 100',
           concat_ws(' | ', s.patrocinador, s.pergunta::text, s.indicador, s.alternativa, s.valor::text, s.unidade, s.base_respondentes::text)
    FROM stg_pesquisa s
    WHERE s.carga_id = p_carga_id AND s.unidade = 'PERCENTUAL' AND s.valor NOT BETWEEN 0 AND 100
      AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = s.carga_id AND r.linha = s.linha);

    -- 2. Campos que dependem do tipo da pesquisa
    IF v_pesq.escopo = 'ATIVACAO' THEN
        INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
        SELECT s.carga_id, s.linha,
               CASE WHEN nullif(btrim(s.patrocinador), '') IS NULL THEN 'patrocinador' ELSE 'pergunta' END,
               CASE WHEN s.pergunta IS NOT NULL AND s.pergunta NOT BETWEEN 1 AND 3 THEN 'Pergunta fora do intervalo de 1 a 3'
                    ELSE 'Campo obrigatório vazio ou inválido' END,
               concat_ws(' | ', s.patrocinador, s.pergunta::text, s.indicador, s.alternativa, s.valor::text, s.unidade, s.base_respondentes::text)
        FROM stg_pesquisa s
        WHERE s.carga_id = p_carga_id
          AND (nullif(btrim(s.patrocinador), '') IS NULL OR s.pergunta IS NULL OR s.pergunta NOT BETWEEN 1 AND 3)
          AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = s.carga_id AND r.linha = s.linha);
    ELSE
        INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
        SELECT s.carga_id, s.linha,
               CASE WHEN nullif(btrim(s.indicador), '') IS NULL THEN 'indicador'
                    WHEN s.patrocinador IS NOT NULL THEN 'patrocinador' ELSE 'pergunta' END,
               CASE WHEN nullif(btrim(s.indicador), '') IS NULL THEN 'Campo obrigatório vazio ou inválido'
                    ELSE 'A pesquisa geral do festival não tem patrocinador nem pergunta' END,
               concat_ws(' | ', s.patrocinador, s.pergunta::text, s.indicador, s.alternativa, s.valor::text, s.unidade, s.base_respondentes::text)
        FROM stg_pesquisa s
        WHERE s.carga_id = p_carga_id
          AND (nullif(btrim(s.indicador), '') IS NULL OR nullif(btrim(s.patrocinador), '') IS NOT NULL OR s.pergunta IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = s.carga_id AND r.linha = s.linha);
    END IF;

    -- 3. Vínculo: patrocinador (nome ou grafia alternativa) -> parceria da edição -> pergunta de mesma ordem
    DROP TABLE IF EXISTS pg_temp.tmp_pesq_vinculo;
    CREATE TEMP TABLE tmp_pesq_vinculo ON COMMIT DROP AS
    SELECT s.linha, s.patrocinador, s.pergunta AS ordem, nullif(btrim(s.indicador), '') AS indicador,
           nullif(btrim(s.alternativa), '') AS alternativa, s.valor, s.unidade, s.base_respondentes,
           pa.id AS parceria_id, q.id AS pergunta_id, q.pesquisa_id AS pergunta_pesquisa_id, q.status AS pergunta_status
    FROM stg_pesquisa s
    LEFT JOIN LATERAL (
         SELECT coalesce(
                    (SELECT p.id FROM patrocinador p WHERE lower(p.nome_canonico) = lower(btrim(s.patrocinador))),
                    (SELECT a.patrocinador_id FROM patrocinador_alias a WHERE lower(a.alias) = lower(btrim(s.patrocinador)))
                ) AS id) pat ON TRUE
    LEFT JOIN parceria pa ON pa.patrocinador_id = pat.id AND pa.evento_id = v_pesq.evento_id
    LEFT JOIN pesquisa_pergunta q ON q.parceria_id = pa.id AND q.ordem = s.pergunta
    WHERE s.carga_id = p_carga_id
      AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = s.carga_id AND r.linha = s.linha);

    IF v_pesq.escopo = 'ATIVACAO' THEN
        INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
        SELECT p_carga_id, v.linha,
               CASE WHEN v.parceria_id IS NULL THEN 'patrocinador' ELSE 'pergunta' END,
               CASE WHEN v.parceria_id IS NULL THEN 'Patrocinador sem parceria nesta edição'
                    WHEN v.pergunta_id IS NULL OR v.pergunta_pesquisa_id <> p_pesquisa_id
                         THEN 'Pergunta não cadastrada para esta parceria nesta pesquisa'
                    ELSE 'Pergunta ainda não aprovada pela RBS' END,
               concat_ws(' | ', v.patrocinador, v.ordem::text, v.indicador, v.alternativa, v.valor::text, v.unidade, v.base_respondentes::text)
        FROM tmp_pesq_vinculo v
        WHERE v.parceria_id IS NULL
           OR v.pergunta_id IS NULL
           OR v.pergunta_pesquisa_id <> p_pesquisa_id
           OR v.pergunta_status <> 'APROVADA';
        DELETE FROM tmp_pesq_vinculo v
        WHERE EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = p_carga_id AND r.linha = v.linha);

        -- Pergunta ainda sem pesquisa é ligada a esta (o resultado só aceita pergunta da mesma pesquisa).
        UPDATE pesquisa_pergunta q SET pesquisa_id = p_pesquisa_id
        WHERE q.pesquisa_id IS NULL AND q.id IN (SELECT pergunta_id FROM tmp_pesq_vinculo);
    END IF;

    SELECT count(*) INTO v_ok FROM tmp_pesq_vinculo;

    -- [DUPLICIDADE] 4. Inserção sem duplicar (fica a linha de menor número; nulos contam como iguais)
    IF v_pesq.escopo = 'ATIVACAO' THEN
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes, carga_id)
        SELECT DISTINCT ON (v.parceria_id, v.pergunta_id, v.alternativa)
               p_pesquisa_id, v.parceria_id, v.pergunta_id, 'Pergunta ' || v.ordem, v.alternativa,
               v.valor, v.unidade, v.base_respondentes, p_carga_id
        FROM tmp_pesq_vinculo v
        ORDER BY v.parceria_id, v.pergunta_id, v.alternativa, v.linha
        ON CONFLICT DO NOTHING;
    ELSE
        INSERT INTO pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa, valor, unidade, base_respondentes, carga_id)
        SELECT DISTINCT ON (v.indicador, v.alternativa)
               p_pesquisa_id, NULL, NULL, v.indicador, v.alternativa,
               v.valor, v.unidade, v.base_respondentes, p_carga_id
        FROM tmp_pesq_vinculo v
        ORDER BY v.indicador, v.alternativa, v.linha
        ON CONFLICT DO NOTHING;
    END IF;
    GET DIAGNOSTICS v_ins = ROW_COUNT;

    SELECT count(DISTINCT linha) INTO v_rej FROM carga_rejeicao WHERE carga_id = p_carga_id;

    UPDATE carga SET linhas_lidas = v_lidas, linhas_inseridas = v_ins,
                     linhas_duplicadas = v_ok - v_ins, linhas_rejeitadas = v_rej,
                     evento_id = v_pesq.evento_id, processada_em = now()
    WHERE id = p_carga_id;

    DROP TABLE IF EXISTS pg_temp.tmp_pesq_vinculo;
    RETURN QUERY SELECT v_lidas, v_ins, v_ok - v_ins, v_rej;
END $$;

-- ---------------------------------------------------------------------
-- 3. VISÕES DE CONSOLIDAÇÃO
-- ---------------------------------------------------------------------
-- [RELATORIO] Previsto x exibido por veículo, sem nunca somar unidades diferentes
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

-- [RELATORIO] Todos os números do relatório, com a base de cálculo de cada um.
-- Dois ajustes em relação à Sprint 1: o recorte da pesquisa não vira nulo quando a alternativa é vazia (o recorte é
-- NOT NULL e a geração falharia), e a base da pesquisa agrupa por parceria (duas pesquisas na mesma parceria
-- colidiam na chave de relatorio_indicador).
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
SELECT pp.parceria_id, 'PESQUISA_BASE', '', max(r.base_respondentes), string_agg(DISTINCT p.nome, '; ')
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id JOIN pesquisa p ON p.id = r.pesquisa_id
GROUP BY pp.parceria_id
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_RESULTADO', 'P' || pp.ordem || coalesce(' · ' || r.alternativa, ''), r.valor,
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

-- [RELATORIO] Faltantes e inconsistências que impedem ou enfraquecem o relatório (uso interno da RBS).
-- [PENDENTE] a confirmar com a RBS: a pesquisa só vale para a cota Master (aqui e em indicador.cotas, no V3).
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
-- 4. GERAÇÃO DO RELATÓRIO
-- ---------------------------------------------------------------------
-- [RELATORIO] Copia os números da base; o que falta fica indisponível, nunca estimado.
-- p_em é opcional: quando omitido vale now(). Serve para a amostra gravar a data simulada do cenário pós-evento
-- sem precisar alterar o histórico depois (o histórico é imutável).
CREATE OR REPLACE FUNCTION fn_gerar_relatorio(p_parceria_id BIGINT, p_usuario_id BIGINT, p_em TIMESTAMPTZ DEFAULT now())
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE v_rel BIGINT; v_versao SMALLINT; v_cota VARCHAR(20); v_em TIMESTAMPTZ := coalesce(p_em, now());
BEGIN
    -- Trava a parceria: duas gerações ao mesmo tempo esperam uma pela outra e ganham versões diferentes.
    SELECT ct.codigo INTO v_cota
    FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
    WHERE pa.id = p_parceria_id
    FOR NO KEY UPDATE OF pa;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A parceria % não existe.', p_parceria_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_parceria_inexistente';
    END IF;

    SELECT coalesce(max(versao), 0) + 1 INTO v_versao FROM relatorio WHERE parceria_id = p_parceria_id;

    INSERT INTO relatorio (parceria_id, versao, gerado_por, gerado_em) VALUES (p_parceria_id, v_versao, p_usuario_id, v_em)
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

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em)
    VALUES (p_parceria_id, 'relatorio', v_rel, 'GERACAO_RELATORIO', 'versão ' || v_versao || ' (RASCUNHO)', p_usuario_id, v_em);

    RETURN v_rel;
END $$;

-- ---------------------------------------------------------------------
-- 5. GATILHOS
-- ---------------------------------------------------------------------

-- [ITEM-1] [PERMISSAO] Revogar o e-mail autorizado tira o acesso. Ao preencher revogado_em: remove o vínculo daquele
-- usuário com a parceria e invalida os códigos de ativação ainda pendentes. Ao zerar revogado_em (reautorizar): se a
-- pessoa já tem usuário, o vínculo volta; se não tem, ela refaz o primeiro acesso com um código novo.
-- A API ainda confere o vínculo a cada requisição, para derrubar quem já estava logado.
CREATE FUNCTION tg_email_autorizado_protege() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parceria_id <> OLD.parceria_id OR lower(NEW.email) <> lower(OLD.email) THEN
        RAISE EXCEPTION 'Não dá para trocar a parceria nem o e-mail de uma autorização. Revogue esta e autorize o e-mail novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_email_autorizado_imutavel';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_email_autorizado_imutavel BEFORE UPDATE ON email_autorizado
    FOR EACH ROW EXECUTE FUNCTION tg_email_autorizado_protege();

CREATE FUNCTION tg_email_autorizado_revogacao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.revogado_em IS NULL AND NEW.revogado_em IS NOT NULL THEN
        DELETE FROM parceria_usuario pu USING usuario u
         WHERE pu.parceria_id = NEW.parceria_id AND u.id = pu.usuario_id AND lower(u.email) = lower(NEW.email);
        UPDATE codigo_ativacao SET invalidado_em = NEW.revogado_em
         WHERE email_autorizado_id = NEW.id AND usado_em IS NULL AND invalidado_em IS NULL;
    ELSIF OLD.revogado_em IS NOT NULL AND NEW.revogado_em IS NULL THEN
        INSERT INTO parceria_usuario (parceria_id, usuario_id, vinculo)
        SELECT NEW.parceria_id, u.id, NEW.vinculo FROM usuario u
         WHERE lower(u.email) = lower(NEW.email) AND u.perfil = 'ADMIN_PATROCINADOR'
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER rn_email_revogado AFTER UPDATE OF revogado_em ON email_autorizado
    FOR EACH ROW EXECUTE FUNCTION tg_email_autorizado_revogacao();

-- [ITEM-1] [PERMISSAO] Fecha a outra ponta: ninguém é vinculado a uma parceria sem e-mail autorizado e não revogado.
-- Sem isso, um erro na API poderia devolver o acesso a um e-mail revogado.
CREATE FUNCTION tg_parceria_usuario_exige_autorizacao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM email_autorizado ea JOIN usuario u ON lower(u.email) = lower(ea.email)
                    WHERE ea.parceria_id = NEW.parceria_id AND u.id = NEW.usuario_id AND ea.revogado_em IS NULL) THEN
        RAISE EXCEPTION 'O e-mail deste usuário não está autorizado nesta parceria (ou a autorização foi revogada).'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_vinculo_exige_autorizacao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_vinculo_exige_autorizacao BEFORE INSERT ON parceria_usuario
    FOR EACH ROW EXECUTE FUNCTION tg_parceria_usuario_exige_autorizacao();

-- [PERMISSAO] Parceria nova ganha as 7 abas no menu; ficam liberadas as que cota_tipo_aba indica para o tipo de cota.
-- Substitui a lista de abas por cota que estava escrita no Java.
CREATE FUNCTION tg_parceria_abas_padrao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO parceria_aba (parceria_id, aba_id, visivel, liberada)
    SELECT NEW.id, a.id, TRUE,
           EXISTS (SELECT 1 FROM cota_tipo_aba cta WHERE cta.cota_tipo_id = NEW.cota_tipo_id AND cta.aba_id = a.id)
    FROM aba a;
    RETURN NULL;
END $$;
CREATE TRIGGER rn_parceria_abas_padrao AFTER INSERT ON parceria
    FOR EACH ROW EXECUTE FUNCTION tg_parceria_abas_padrao();

-- [HISTORICO] [ITEM-5] O histórico nunca muda: UPDATE e DELETE são recusados. Só INSERT.
-- (TRUNCATE não passa por gatilho de linha; quem pode usá-lo é o dono do banco, e a V5 do plano de papéis tira isso da API.)
CREATE FUNCTION tg_historico_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'O histórico não pode ser alterado nem apagado (tentativa de % na linha %).', TG_OP, OLD.id
        USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_historico_imutavel';
END $$;
CREATE TRIGGER rn_historico_imutavel BEFORE UPDATE OR DELETE ON historico
    FOR EACH ROW EXECUTE FUNCTION tg_historico_imutavel();

-- [ITEM-6] A destinação de cortesias não passa da cota. O SELECT ... FOR UPDATE na linha da cota faz duas destinações
-- simultâneas esperarem uma pela outra; sem isso as duas leriam a mesma soma e as duas passariam.
CREATE FUNCTION tg_cortesia_destinacao_limite() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_cota INT; v_outras INT;
BEGIN
    SELECT quantidade INTO v_cota FROM cortesia_cota WHERE id = NEW.cortesia_cota_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;   -- cota inexistente: a chave estrangeira recusa em seguida
    END IF;
    SELECT coalesce(sum(quantidade), 0) INTO v_outras FROM cortesia_destinacao
     WHERE cortesia_cota_id = NEW.cortesia_cota_id AND (TG_OP = 'INSERT' OR id <> NEW.id);
    IF v_outras + NEW.quantidade > v_cota THEN
        RAISE EXCEPTION 'A destinação passa da cota: a cota tem % cortesias, % já foram destinadas e esta pede mais %.',
                        v_cota, v_outras, NEW.quantidade
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_cortesia_limite';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_cortesia_limite BEFORE INSERT OR UPDATE OF quantidade, cortesia_cota_id ON cortesia_destinacao
    FOR EACH ROW EXECUTE FUNCTION tg_cortesia_destinacao_limite();

-- [ITEM-6] O mesmo vale na outra direção: a RBS não reduz uma cota abaixo do que já foi destinado.
CREATE FUNCTION tg_cortesia_cota_reducao() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_destinadas INT;
BEGIN
    SELECT coalesce(sum(quantidade), 0) INTO v_destinadas FROM cortesia_destinacao WHERE cortesia_cota_id = NEW.id;
    IF v_destinadas > NEW.quantidade THEN
        RAISE EXCEPTION 'A cota não pode ficar com % cortesias: já há % destinadas.', NEW.quantidade, v_destinadas
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_cortesia_cota_reducao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_cortesia_cota_reducao BEFORE UPDATE OF quantidade ON cortesia_cota
    FOR EACH ROW WHEN (NEW.quantidade < OLD.quantidade) EXECUTE FUNCTION tg_cortesia_cota_reducao();

-- [ITEM-7] [RELATORIO] O relatório nasce em rascunho e só avança RASCUNHO -> VALIDADO -> DISPONIBILIZADO, sem pular
-- nem voltar. A identidade (parceria, versão, quem gerou e quando) não muda, nem as etapas já registradas.
-- Quem validou e quem disponibilizou (com data) ficam gravados em colunas próprias, protegidas por CHECK na V1.
CREATE FUNCTION tg_relatorio_ordem_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RASCUNHO' THEN
            RAISE EXCEPTION 'Todo relatório nasce como rascunho (veio como %).', NEW.status
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_nasce_rascunho';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.parceria_id <> OLD.parceria_id OR NEW.versao <> OLD.versao
       OR NEW.gerado_por <> OLD.gerado_por OR NEW.gerado_em <> OLD.gerado_em THEN
        RAISE EXCEPTION 'A parceria, a versão e a geração de um relatório não mudam.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_identidade';
    END IF;

    IF NEW.status = OLD.status THEN
        IF NEW.validado_por IS DISTINCT FROM OLD.validado_por OR NEW.validado_em IS DISTINCT FROM OLD.validado_em
           OR NEW.disponibilizado_por IS DISTINCT FROM OLD.disponibilizado_por
           OR NEW.disponibilizado_em IS DISTINCT FROM OLD.disponibilizado_em THEN
            RAISE EXCEPTION 'Quem validou ou disponibilizou o relatório, e quando, não pode ser alterado.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_etapas_gravadas';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT ((OLD.status = 'RASCUNHO' AND NEW.status = 'VALIDADO')
         OR (OLD.status = 'VALIDADO' AND NEW.status = 'DISPONIBILIZADO')) THEN
        RAISE EXCEPTION 'O relatório não pode ir de % para %. A ordem é RASCUNHO, VALIDADO, DISPONIBILIZADO.', OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_ordem_status';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_relatorio_ordem_status BEFORE INSERT OR UPDATE ON relatorio
    FOR EACH ROW EXECUTE FUNCTION tg_relatorio_ordem_status();

CREATE FUNCTION tg_relatorio_nao_apaga() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'RASCUNHO' THEN
        RAISE EXCEPTION 'Um relatório % não se apaga. Gere uma nova versão.', lower(OLD.status)
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_nao_apaga';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER rn_relatorio_nao_apaga BEFORE DELETE ON relatorio
    FOR EACH ROW EXECUTE FUNCTION tg_relatorio_nao_apaga();

-- [ITEM-7] [RELATORIO] "Cada versão é uma fotografia": depois de validado, os números do relatório não mudam mais.
CREATE FUNCTION tg_relatorio_indicador_congelado() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_status VARCHAR(16);
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT status INTO v_status FROM relatorio WHERE id = NEW.relatorio_id;
        IF FOUND AND v_status <> 'RASCUNHO' THEN
            RAISE EXCEPTION 'Os números de um relatório % não mudam. Gere uma nova versão.', lower(v_status)
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_congelado';
        END IF;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT status INTO v_status FROM relatorio WHERE id = OLD.relatorio_id;
        IF FOUND AND v_status <> 'RASCUNHO' THEN
            RAISE EXCEPTION 'Os números de um relatório % não mudam. Gere uma nova versão.', lower(v_status)
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_relatorio_congelado';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_relatorio_congelado BEFORE INSERT OR UPDATE OR DELETE ON relatorio_indicador
    FOR EACH ROW EXECUTE FUNCTION tg_relatorio_indicador_congelado();

-- [ISOLAMENTO] [ITEM-3] Foto de comprovação: preenche a parceria a partir da aplicação (a API não precisa informar) e
-- confere que a aplicação é de marca no evento e que o arquivo é uma comprovação. Que o arquivo e a aplicação são da
-- mesma parceria quem garante são as chaves compostas da V1.
CREATE FUNCTION tg_comprovacao_valida() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_parceria BIGINT; v_categoria VARCHAR(20); v_cat_arquivo VARCHAR(30);
BEGIN
    SELECT parceria_id, categoria INTO v_parceria, v_categoria FROM aproveitamento_previsto WHERE id = NEW.aproveitamento_id;
    IF FOUND THEN
        IF NEW.parceria_id IS NULL THEN NEW.parceria_id := v_parceria; END IF;
        IF v_categoria <> 'APLICACAO_MARCA' THEN
            RAISE EXCEPTION 'Foto de comprovação só vale para aplicação de marca no evento, não para mídia.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_comprovacao_so_aplicacao';
        END IF;
    END IF;
    SELECT categoria INTO v_cat_arquivo FROM arquivo WHERE id = NEW.arquivo_id;
    IF FOUND AND v_cat_arquivo <> 'COMPROVACAO' THEN
        RAISE EXCEPTION 'O arquivo da comprovação precisa ser da categoria COMPROVACAO (veio %).', v_cat_arquivo
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_comprovacao_categoria';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_comprovacao_valida BEFORE INSERT OR UPDATE ON comprovacao_marca
    FOR EACH ROW EXECUTE FUNCTION tg_comprovacao_valida();

-- [ISOLAMENTO] [ITEM-3] O comprovante de uma veiculação é arquivo da mesma parceria da linha do plano. É gatilho (e não
-- chave composta) para não pesar nas cargas de milhares de linhas, que não trazem comprovante.
CREATE FUNCTION tg_veiculacao_comprovante() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_do_plano BIGINT; v_do_arquivo BIGINT;
BEGIN
    SELECT parceria_id INTO v_do_plano FROM aproveitamento_previsto WHERE id = NEW.aproveitamento_id;
    SELECT parceria_id INTO v_do_arquivo FROM arquivo WHERE id = NEW.comprovante_arquivo_id;
    IF v_do_arquivo IS DISTINCT FROM v_do_plano THEN
        RAISE EXCEPTION 'O comprovante da veiculação precisa ser um arquivo da mesma parceria da linha do plano.'
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_veiculacao_comprovante_parceria';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_veiculacao_comprovante BEFORE INSERT OR UPDATE ON veiculacao
    FOR EACH ROW WHEN (NEW.comprovante_arquivo_id IS NOT NULL) EXECUTE FUNCTION tg_veiculacao_comprovante();

-- [ISOLAMENTO] [ITEM-3] Pergunta e pesquisa são da mesma edição, e a pesquisa de uma pergunta é a da ativação.
CREATE FUNCTION tg_pergunta_pesquisa() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_evento_pesquisa BIGINT; v_escopo VARCHAR(15); v_evento_parceria BIGINT;
BEGIN
    IF NEW.pesquisa_id IS NULL THEN RETURN NEW; END IF;
    SELECT evento_id, escopo INTO v_evento_pesquisa, v_escopo FROM pesquisa WHERE id = NEW.pesquisa_id;
    IF NOT FOUND THEN RETURN NEW; END IF;   -- pesquisa inexistente: a chave estrangeira recusa em seguida
    SELECT evento_id INTO v_evento_parceria FROM parceria WHERE id = NEW.parceria_id;
    IF v_escopo <> 'ATIVACAO' THEN
        RAISE EXCEPTION 'A pergunta personalizada de uma parceria só pode ficar numa pesquisa da ativação.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_pergunta_pesquisa';
    END IF;
    IF v_evento_pesquisa IS DISTINCT FROM v_evento_parceria THEN
        RAISE EXCEPTION 'A pergunta e a pesquisa precisam ser da mesma edição do festival.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_pergunta_mesma_edicao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_pergunta_pesquisa BEFORE INSERT OR UPDATE OF pesquisa_id, parceria_id ON pesquisa_pergunta
    FOR EACH ROW EXECUTE FUNCTION tg_pergunta_pesquisa();

-- [ISOLAMENTO] [ITEM-3] Resultado da pesquisa geral não tem parceria nem pergunta; o da ativação tem as duas, e a parceria
-- é da mesma edição da pesquisa. (Que a pergunta é da parceria e da pesquisa do resultado, as chaves compostas garantem.)
CREATE FUNCTION tg_resultado_escopo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_escopo VARCHAR(15); v_evento_pesquisa BIGINT; v_evento_parceria BIGINT;
BEGIN
    SELECT escopo, evento_id INTO v_escopo, v_evento_pesquisa FROM pesquisa WHERE id = NEW.pesquisa_id;
    IF NOT FOUND THEN RETURN NEW; END IF;   -- pesquisa inexistente: a chave estrangeira recusa em seguida
    IF v_escopo = 'GERAL' AND (NEW.parceria_id IS NOT NULL OR NEW.pergunta_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Resultado da pesquisa geral do festival não pertence a uma parceria nem a uma pergunta.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_resultado_escopo';
    END IF;
    IF v_escopo = 'ATIVACAO' AND (NEW.parceria_id IS NULL OR NEW.pergunta_id IS NULL) THEN
        RAISE EXCEPTION 'Resultado da pesquisa da ativação precisa estar ligado a uma parceria e a uma pergunta.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_resultado_escopo';
    END IF;
    IF NEW.parceria_id IS NOT NULL THEN
        SELECT evento_id INTO v_evento_parceria FROM parceria WHERE id = NEW.parceria_id;
        IF v_evento_parceria IS DISTINCT FROM v_evento_pesquisa THEN
            RAISE EXCEPTION 'O resultado e a pesquisa precisam ser da mesma edição do festival.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_resultado_mesma_edicao';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_resultado_escopo BEFORE INSERT OR UPDATE ON pesquisa_resultado
    FOR EACH ROW EXECUTE FUNCTION tg_resultado_escopo();

-- [ISOLAMENTO] [ITEM-3] A fonte da pesquisa (relatório da empresa de pesquisa) é arquivo geral da RBS, nunca de uma marca,
-- porque a pesquisa vale para a edição inteira.
CREATE FUNCTION tg_pesquisa_fonte_geral() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_parceria BIGINT;
BEGIN
    IF NEW.fonte_arquivo_id IS NULL THEN RETURN NEW; END IF;
    SELECT parceria_id INTO v_parceria FROM arquivo WHERE id = NEW.fonte_arquivo_id;
    IF FOUND AND v_parceria IS NOT NULL THEN
        RAISE EXCEPTION 'A fonte da pesquisa precisa ser um arquivo geral da RBS, não de uma parceria.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_pesquisa_fonte_geral';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_pesquisa_fonte_geral BEFORE INSERT OR UPDATE OF fonte_arquivo_id ON pesquisa
    FOR EACH ROW EXECUTE FUNCTION tg_pesquisa_fonte_geral();

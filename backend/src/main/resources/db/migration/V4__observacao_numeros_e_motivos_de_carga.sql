-- Correções da rodada de testes em materiais, relatório e cargas. Atende os itens 4, 5 e 6 da Entrega 2.
-- A V1, a V2 e a V3 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
--   1. arquivo.observacao: o recado que quem envia escreve junto com cada versão aparece na lista de versões da entrega.
--   2. Números do relatório em português: milhar com ponto no texto "como foi calculado" e percentual de entrega
--      que nunca arredonda para 100 sem estar completo.
--   3. Cargas: a área de passagem guarda o texto original da data e do número que não converteram, e as funções de
--      carga rejeitam a linha com o motivo específico (e o formato esperado) em vez do motivo genérico.

-- ---------------------------------------------------------------------
-- 1. OBSERVAÇÃO DE CADA VERSÃO DO ARQUIVO
-- ---------------------------------------------------------------------
-- [HISTORICO] Até aqui a observação do envio só ficava no histórico (ENVIO_ARQUIVO e NOVA_VERSAO, em detalhe), sem ligação
-- com a versão. A coluna liga a observação à linha do arquivo; a API continua gravando também o histórico.
ALTER TABLE arquivo ADD COLUMN observacao VARCHAR(500);

-- Recupera as observações já gravadas: o histórico do envio guarda "nome (versão N)" em valor_novo e o recado em detalhe.
UPDATE arquivo a SET observacao = left(btrim(h.detalhe), 500)
FROM historico h
WHERE a.entrega_id IS NOT NULL
  AND h.entidade = 'entrega' AND h.entidade_id = a.entrega_id
  AND h.acao IN ('ENVIO_ARQUIVO', 'NOVA_VERSAO')
  AND h.valor_novo = a.nome_original || ' (versão ' || a.versao || ')'
  AND nullif(btrim(h.detalhe), '') IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. NÚMEROS DO RELATÓRIO
-- ---------------------------------------------------------------------
-- [RELATORIO] Número no padrão brasileiro para os textos: milhar com ponto, decimal com vírgula e sem zeros sobrando
-- (459900 vira 459.900; 12.50 vira 12,5). Não depende do idioma do servidor de banco (to_char com "G" e "D" dependeria).
CREATE FUNCTION fn_numero_br(p_valor NUMERIC) RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN p_valor IS NULL THEN NULL
                ELSE CASE WHEN p_valor < 0 THEN '-' ELSE '' END
                     || translate(to_char(trunc(abs(p_valor)), 'FM999,999,999,999,999,999,990'), ',', '.')
                     || CASE WHEN abs(p_valor) <> trunc(abs(p_valor))
                             THEN ',' || split_part(trim_scale(abs(p_valor))::text, '.', 2) ELSE '' END
           END
$$;

-- [RELATORIO] Percentual de entrega de mídia: corta na primeira casa em vez de arredondar. 459.900 de 460.000 é 99,978%
-- e arredondado viraria 100,0, que diz "completo" quando faltam exibições. Cortado, vale 99,9. Quem passa de 100 continua passando.
CREATE FUNCTION fn_pct_truncado(p_valor NUMERIC) RETURNS NUMERIC
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT trunc(p_valor, 1)
$$;

-- Os outros percentuais (entregas, aplicações, cortesias) seguem arredondando, mas abaixo de 100 nunca passam de 99,9.
CREATE FUNCTION fn_pct_arredondado(p_valor NUMERIC) RETURNS NUMERIC
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN p_valor < 100 THEN least(round(p_valor, 1), 99.9) ELSE round(p_valor, 1) END
$$;

-- As visões abaixo mudam só a expressão do percentual (mesmas colunas, mesmos tipos).
CREATE OR REPLACE VIEW vw_midia_veiculo AS
SELECT ap.parceria_id, ap.veiculo, ap.unidade,
       sum(ap.quantidade_prevista)                         AS previsto,
       coalesce(sum(v.exibido), 0)                         AS exibido,
       fn_pct_truncado(100 * coalesce(sum(v.exibido), 0) / sum(ap.quantidade_prevista)) AS pct_entrega
FROM aproveitamento_previsto ap
LEFT JOIN (SELECT aproveitamento_id, sum(quantidade) AS exibido
           FROM veiculacao GROUP BY aproveitamento_id) v ON v.aproveitamento_id = ap.id
WHERE ap.categoria = 'MIDIA'
GROUP BY ap.parceria_id, ap.veiculo, ap.unidade;

CREATE OR REPLACE VIEW vw_midia_unidade AS
SELECT parceria_id, unidade, sum(previsto) AS previsto, sum(exibido) AS exibido,
       fn_pct_truncado(100 * sum(exibido) / sum(previsto)) AS pct_entrega
FROM vw_midia_veiculo GROUP BY parceria_id, unidade;

CREATE OR REPLACE VIEW vw_entregas_resumo AS
SELECT parceria_id,
       count(*)                                              AS total,
       count(*) FILTER (WHERE status = 'NAO_APLICAVEL')      AS nao_aplicaveis,
       count(*) FILTER (WHERE status = 'APROVADO')           AS aprovadas,
       count(*) FILTER (WHERE status NOT IN ('APROVADO','NAO_APLICAVEL')) AS pendentes,
       fn_pct_arredondado(100.0 * count(*) FILTER (WHERE status = 'APROVADO')
             / nullif(count(*) FILTER (WHERE status <> 'NAO_APLICAVEL'), 0)) AS pct_concluidas
FROM entrega GROUP BY parceria_id;

-- [RELATORIO] Todos os números do relatório. Mudam o texto "como foi calculado" (números no padrão brasileiro) e o
-- percentual (veja acima); a geração de relatório (fn_gerar_relatorio) continua a mesma e copia estas linhas.
CREATE OR REPLACE VIEW vw_indicadores_parceria AS
SELECT parceria_id, 'ENTREGAS_CONCLUIDAS_PCT' AS indicador_codigo, '' AS recorte, pct_concluidas AS valor,
       fn_numero_br(aprovadas) || ' aprovadas de ' || fn_numero_br(total - nao_aplicaveis) || ' aplicáveis' AS base_calculo
FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'ENTREGAS_PENDENTES', '', pendentes, fn_numero_br(total) || ' entregas cadastradas' FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'MIDIA_PREVISTO', unidade, previsto, 'soma do plano comercial' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_EXIBIDO', unidade, exibido, 'veiculações sem duplicidade' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_ENTREGA_PCT', unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto) FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_VEICULO_PCT', veiculo || ' · ' || unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto) FROM vw_midia_veiculo
UNION ALL
SELECT parceria_id, 'APLICACOES_PREVISTAS', '', previstas, 'plano comercial' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS', '', comprovadas, fn_numero_br(fotos) || ' fotos enviadas' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS_PCT', '', fn_pct_arredondado(100 * comprovadas / previstas),
       fn_numero_br(comprovadas) || ' de ' || fn_numero_br(previstas) FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'COMPROVACOES_ENVIADAS', '', fotos, 'arquivos de comprovação' FROM vw_aplicacoes
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_BASE', '', max(r.base_respondentes), string_agg(DISTINCT p.nome, '; ')
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id JOIN pesquisa p ON p.id = r.pesquisa_id
GROUP BY pp.parceria_id
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_RESULTADO', 'P' || pp.ordem || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes (' || lower(r.unidade) || ')'
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id
UNION ALL
SELECT parceria_id, 'CORTESIAS_DISPONIVEIS', '', disponiveis, 'soma por setor e dia' FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CORTESIAS_DESTINADAS_PCT', '', fn_pct_arredondado(100.0 * destinadas / nullif(disponiveis, 0)),
       fn_numero_br(destinadas) || ' de ' || fn_numero_br(disponiveis) FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CREDENCIAIS_DIA', setor || ' · ' || beneficiario, quantidade_por_dia, 'plano comercial'
FROM credencial_cota
UNION ALL
SELECT pa.id, 'FESTIVAL_NUMERO', er.indicador, er.valor,
       CASE er.qualificador WHEN 'MAIS_DE' THEN 'mais de; ' ELSE '' END || er.fonte
FROM parceria pa JOIN evento_resultado er ON er.evento_id = pa.evento_id
UNION ALL
SELECT pa.id, 'FESTIVAL_PESQUISA', r.indicador || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes'
FROM parceria pa JOIN pesquisa p ON p.evento_id = pa.evento_id AND p.escopo = 'GERAL'
JOIN pesquisa_resultado r ON r.pesquisa_id = p.id AND r.parceria_id IS NULL;

-- [RELATORIO] Faltantes: só muda o texto da mídia abaixo do previsto (números no padrão brasileiro).
CREATE OR REPLACE VIEW vw_faltantes AS
SELECT ap.parceria_id, 'MIDIA_SEM_EXIBICAO' AS tipo,
       ap.veiculo || ' · ' || ap.formato || coalesce(' · ' || ap.espaco_programa, '') AS detalhe
FROM aproveitamento_previsto ap
WHERE ap.categoria = 'MIDIA' AND NOT EXISTS (SELECT 1 FROM veiculacao v WHERE v.aproveitamento_id = ap.id)
UNION ALL
SELECT ap.parceria_id, 'MIDIA_ABAIXO_DO_PREVISTO',
       ap.veiculo || ' · ' || ap.formato || coalesce(' · ' || ap.espaco_programa, '') || ': ' ||
       fn_numero_br(sum(v.quantidade)) || ' de ' || fn_numero_br(ap.quantidade_prevista)
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
-- 3. CARGAS: MOTIVO ESPECÍFICO PARA DATA E NÚMERO ILEGÍVEIS
-- ---------------------------------------------------------------------
-- [CARGA] [VALIDACAO] A API converte a data e os números antes de gravar; o que não converte vai nulo, e a função só
-- sabia dizer "vazio ou inválido". Agora a API guarda também o texto original do que não converteu (só nesse caso), e a
-- função usa a presença dele para dar o motivo certo. Coluna vazia continua com o motivo de campo obrigatório.
ALTER TABLE stg_veiculacao
    ADD COLUMN exibido_em_texto VARCHAR(40),
    ADD COLUMN quantidade_texto VARCHAR(40);
ALTER TABLE stg_pesquisa
    ADD COLUMN pergunta_texto VARCHAR(40),
    ADD COLUMN valor_texto VARCHAR(40),
    ADD COLUMN base_respondentes_texto VARCHAR(40);

-- Mesma função da V2; muda só a primeira rejeição (campos obrigatórios e valores ilegíveis).
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

    -- [VALIDACAO] 1. Campos obrigatórios e valores ilegíveis. Cada linha recebe no máximo um motivo (o primeiro problema).
    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT s.carga_id, s.linha,
           CASE WHEN s.patrocinador IS NULL THEN 'patrocinador'
                WHEN s.veiculo IS NULL THEN 'veiculo'
                WHEN s.formato IS NULL THEN 'formato'
                WHEN s.exibido_em IS NULL THEN 'exibido_em'
                ELSE 'quantidade' END,
           CASE WHEN s.patrocinador IS NULL OR s.veiculo IS NULL OR s.formato IS NULL
                     THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.exibido_em IS NULL AND s.exibido_em_texto IS NOT NULL
                     THEN 'Data inválida. Use o formato 2026-11-20T10:15:30-03:00 (ano-mês-dia, hora e fuso).'
                WHEN s.exibido_em IS NULL THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.quantidade IS NULL AND s.quantidade_texto IS NOT NULL
                     THEN 'Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).'
                WHEN s.quantidade IS NULL THEN 'Campo obrigatório vazio ou inválido'
                ELSE 'A quantidade deve ser maior que zero.' END,
           concat_ws(' | ', s.patrocinador, s.veiculo, s.formato, s.espaco_programa,
                     coalesce(to_char(s.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'), s.exibido_em_texto),
                     s.quantidade_texto)
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

-- Mesma função da V2; mudam só as duas primeiras rejeições (valor, unidade e base ilegíveis; pergunta ilegível).
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
           CASE WHEN s.valor IS NULL AND s.valor_texto IS NOT NULL
                     THEN 'Valor inválido. Use só números, com ponto nos decimais, como 8.4 ou 64.'
                WHEN s.valor IS NULL THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.unidade IS NOT NULL AND s.unidade NOT IN ('PERCENTUAL','NOTA','CONTAGEM')
                     THEN 'Unidade inválida. Use PERCENTUAL, NOTA ou CONTAGEM.'
                WHEN s.unidade IS NULL THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.base_respondentes IS NULL AND s.base_respondentes_texto IS NOT NULL
                     THEN 'Base de respondentes inválida. Use só números inteiros, como 412.'
                WHEN s.base_respondentes IS NULL THEN 'Campo obrigatório vazio ou inválido'
                ELSE 'A base de respondentes deve ser maior que zero.' END,
           concat_ws(' | ', s.patrocinador, coalesce(s.pergunta::text, s.pergunta_texto), s.indicador, s.alternativa,
                     coalesce(s.valor::text, s.valor_texto), s.unidade,
                     coalesce(s.base_respondentes::text, s.base_respondentes_texto))
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
                    WHEN nullif(btrim(s.patrocinador), '') IS NOT NULL AND s.pergunta IS NULL AND s.pergunta_texto IS NOT NULL
                         THEN 'Pergunta inválida. Use o número da pergunta: 1, 2 ou 3.'
                    ELSE 'Campo obrigatório vazio ou inválido' END,
               concat_ws(' | ', s.patrocinador, coalesce(s.pergunta::text, s.pergunta_texto), s.indicador, s.alternativa,
                         s.valor::text, s.unidade, s.base_respondentes::text)
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

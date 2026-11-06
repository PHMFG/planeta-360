-- V23: os faltantes da pesquisa e as regras da carga de resultados (fases F5 e F6 do plano da extração). Só troca o corpo
-- de uma visão e de uma função; nenhuma V antiga é editada, nenhuma tabela, coluna ou gatilho novo.
--
-- 1. A visão vw_faltantes deixa de cobrar "as 3 perguntas": a pesquisa pode ter até o limite configurado (padrão 10, V22),
--    e o que falta é a marca não ter nenhuma pergunta cadastrada. O texto passa a dizer isso.
-- 2. A fn_carregar_pesquisa passa a ler o limite da pesquisa da carga (e não o 3 fixo da V4) e recusa, com motivo, a
--    alternativa que não está na lista de uma pergunta ESCOLHA ou SIM_NAO (BG3). Pergunta sem lista (as antigas) continua
--    aceitando qualquer alternativa, como antes.

-- ---------------------------------------------------------------------
-- 1. Os faltantes da pesquisa
-- ---------------------------------------------------------------------
-- [RELATORIO] Mesma visão da V4; muda só a regra da pesquisa. A marca Master sem nenhuma pergunta cadastrada aparece como
-- faltante, e não mais a que tem uma ou duas (o limite agora é a configuração da pesquisa, não 3).
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
SELECT pa.id, 'PESQUISA_SEM_PERGUNTAS', 'Cota Master sem perguntas cadastradas'
FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
WHERE ct.codigo = 'MASTER' AND (SELECT count(*) FROM pesquisa_pergunta q WHERE q.parceria_id = pa.id) = 0
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
-- 2. A carga de resultados da pesquisa
-- ---------------------------------------------------------------------
-- Mesma função da V4, com duas mudanças:
--  - o limite de perguntas vem de pesquisa.limite_perguntas (padrão 10), e não do 3 fixo;
--  - a linha de pergunta ESCOLHA ou SIM_NAO que tenha lista de alternativas e traga uma alternativa fora dela é rejeitada
--    com motivo (BG3), sem derrubar a carga. Pergunta sem lista (as antigas) aceita como hoje.
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
               CASE WHEN s.pergunta IS NOT NULL AND s.pergunta NOT BETWEEN 1 AND v_pesq.limite_perguntas
                         THEN 'Pergunta fora do intervalo de 1 a ' || v_pesq.limite_perguntas
                    WHEN nullif(btrim(s.patrocinador), '') IS NOT NULL AND s.pergunta IS NULL AND s.pergunta_texto IS NOT NULL
                         THEN 'Pergunta inválida. Use o número da pergunta: 1 a ' || v_pesq.limite_perguntas || '.'
                    ELSE 'Campo obrigatório vazio ou inválido' END,
               concat_ws(' | ', s.patrocinador, coalesce(s.pergunta::text, s.pergunta_texto), s.indicador, s.alternativa,
                         s.valor::text, s.unidade, s.base_respondentes::text)
        FROM stg_pesquisa s
        WHERE s.carga_id = p_carga_id
          AND (nullif(btrim(s.patrocinador), '') IS NULL OR s.pergunta IS NULL
               OR s.pergunta NOT BETWEEN 1 AND v_pesq.limite_perguntas)
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

        -- [VALIDACAO] BG3: a alternativa tem de estar na lista da pergunta fechada. Pergunta sem lista (as antigas) aceita
        -- qualquer alternativa, e a pergunta NOTA/TEXTO não usa alternativa.
        INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
        SELECT p_carga_id, v.linha, 'alternativa', 'Alternativa fora da lista da pergunta.',
               concat_ws(' | ', v.patrocinador, v.ordem::text, v.indicador, v.alternativa, v.valor::text, v.unidade, v.base_respondentes::text)
        FROM tmp_pesq_vinculo v
        JOIN pesquisa_pergunta q ON q.id = v.pergunta_id
        WHERE v.alternativa IS NOT NULL
          AND q.tipo_resposta IN ('ESCOLHA', 'SIM_NAO')
          AND q.alternativas IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(q.alternativas) a WHERE a = v.alternativa);
        DELETE FROM tmp_pesq_vinculo v
        WHERE EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = p_carga_id AND r.linha = v.linha);
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

-- V20: a base de mídia (fase F2 do plano da extração). Só acrescenta: o impacto por entrega (M1), o mês como período (M2) e a
-- visão que a consulta com filtros lê (M3). Nenhuma V antiga é editada. A amostra continua só com exibições: todas as linhas
-- que já existem ficam com periodo = 'EXIBICAO', mes_referencia e impacto nulos, e a carga da amostra passa pela função nova.

-- ---------------------------------------------------------------------
-- 1. O impacto e o período da linha (M1 e M2)
-- ---------------------------------------------------------------------
-- [MIDIA] O impacto é opcional e nunca se soma entre unidades diferentes; quem soma confere a unidade antes (a API e a visão).
ALTER TABLE veiculacao
    ADD COLUMN impacto NUMERIC(18,2) CONSTRAINT ck_veiculacao_impacto CHECK (impacto >= 0),
    -- EXIBICAO: uma exibição com data e hora (o caso de sempre). MES: o total do mês, sem hora.
    ADD COLUMN periodo VARCHAR(10) NOT NULL DEFAULT 'EXIBICAO'
        CONSTRAINT ck_veiculacao_periodo CHECK (periodo IN ('EXIBICAO', 'MES')),
    ADD COLUMN mes_referencia DATE,
    -- O mês existe se e só se a linha é mensal, e o dia é sempre 1 (o primeiro dia do mês de Brasília).
    ADD CONSTRAINT ck_veiculacao_mes_referencia CHECK (
        (periodo = 'MES') = (mes_referencia IS NOT NULL)
        AND (mes_referencia IS NULL OR EXTRACT(DAY FROM mes_referencia) = 1));
CREATE INDEX ix_veiculacao_mes ON veiculacao (aproveitamento_id, mes_referencia) WHERE periodo = 'MES';

-- [MIDIA] [VALIDACAO] O gatilho da regra do período misto (M2). Numa linha MES, o exibido_em é o primeiro dia do mês de
-- Brasília à meia-noite (quem manda é o mes_referencia, mesmo que a API mande outra hora). E as duas formas nunca convivem:
-- uma linha MES é recusada quando já há exibição da mesma linha do plano no mesmo mês, e uma exibição é recusada quando já há
-- o total mensal daquele mês. O mesmo já acontece dentro de um arquivo: a fn_carregar_veiculacoes confere antes de inserir e
-- transforma o conflito em rejeição, sem derrubar a carga.
CREATE FUNCTION tg_veiculacao_periodo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_inicio TIMESTAMPTZ; v_fim TIMESTAMPTZ; v_mes DATE;
BEGIN
    IF NEW.periodo = 'MES' AND NEW.mes_referencia IS NOT NULL THEN
        NEW.exibido_em := NEW.mes_referencia::timestamp AT TIME ZONE 'America/Sao_Paulo';
        v_inicio := NEW.exibido_em;
        v_fim := (NEW.mes_referencia + INTERVAL '1 month')::timestamp AT TIME ZONE 'America/Sao_Paulo';
        IF EXISTS (SELECT 1 FROM veiculacao v
                   WHERE v.aproveitamento_id = NEW.aproveitamento_id
                     AND v.periodo = 'EXIBICAO'
                     AND v.exibido_em >= v_inicio AND v.exibido_em < v_fim
                     AND (TG_OP = 'INSERT' OR v.id <> NEW.id)) THEN
            RAISE EXCEPTION 'Já há exibição desta linha do plano neste mês; o total mensal e as exibições não se somam. Use um ou outro.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_veiculacao_periodo';
        END IF;
    ELSE
        v_mes := date_trunc('month', NEW.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date;
        IF EXISTS (SELECT 1 FROM veiculacao v
                   WHERE v.aproveitamento_id = NEW.aproveitamento_id
                     AND v.periodo = 'MES'
                     AND v.mes_referencia = v_mes
                     AND (TG_OP = 'INSERT' OR v.id <> NEW.id)) THEN
            RAISE EXCEPTION 'Já há o total mensal desta linha do plano neste mês; as exibições e o total mensal não se somam. Use um ou outro.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_veiculacao_periodo';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_veiculacao_periodo BEFORE INSERT OR UPDATE ON veiculacao
    FOR EACH ROW EXECUTE FUNCTION tg_veiculacao_periodo();

-- ---------------------------------------------------------------------
-- 2. A área de carga: o impacto e o mês da planilha (M1 e M2)
-- ---------------------------------------------------------------------
-- [CARGA] O impacto e o mês chegam da planilha. O texto original fica quando a célula não vira número (o mesmo cuidado da
-- V4 com a data e a quantidade): é ele que dá o motivo certo da rejeição.
ALTER TABLE stg_veiculacao
    ADD COLUMN impacto NUMERIC(18,2),
    ADD COLUMN impacto_texto TEXT,
    ADD COLUMN mes TEXT;                  -- o texto AAAA-MM da planilha

-- ---------------------------------------------------------------------
-- 3. A carga de veiculação com o mês e o impacto
-- ---------------------------------------------------------------------
-- Mesma função da V4, sem mudar o formato dos totais, com quatro mudanças:
--  - a linha traz exibido_em OU mes: as duas ou nenhuma são rejeitadas, e o mes fora de AAAA-MM também (com o formato esperado);
--  - o impacto ilegível (o texto preenchido e o número nulo) é rejeitado; o impacto vazio vale como nulo;
--  - grava periodo, mes_referencia e impacto;
--  - a linha que cairia na regra do período misto vira rejeição com motivo, conferida antes de inserir.
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
                WHEN s.exibido_em IS NULL AND s.mes IS NULL THEN 'exibido_em'
                WHEN s.exibido_em IS NOT NULL AND s.mes IS NOT NULL THEN 'exibido_em'
                WHEN s.mes IS NOT NULL AND s.mes !~ '^\d{4}-(0[1-9]|1[0-2])$' THEN 'mes'
                WHEN s.quantidade IS NULL THEN 'quantidade'
                WHEN s.quantidade <= 0 THEN 'quantidade'
                ELSE 'impacto' END,
           CASE WHEN s.patrocinador IS NULL OR s.veiculo IS NULL OR s.formato IS NULL
                     THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.exibido_em IS NOT NULL AND s.mes IS NOT NULL
                     THEN 'Informe a exibição (data e hora) ou o mês, nunca os dois.'
                WHEN s.exibido_em IS NULL AND s.mes IS NULL AND s.exibido_em_texto IS NOT NULL
                     THEN 'Data inválida. Use o formato 2026-11-20T10:15:30-03:00 (ano-mês-dia, hora e fuso).'
                WHEN s.exibido_em IS NULL AND s.mes IS NULL
                     THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.mes IS NOT NULL AND s.mes !~ '^\d{4}-(0[1-9]|1[0-2])$'
                     THEN 'Mês inválido. Use o formato AAAA-MM (ano-mês), como 2026-11.'
                WHEN s.quantidade IS NULL AND s.quantidade_texto IS NOT NULL
                     THEN 'Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).'
                WHEN s.quantidade IS NULL THEN 'Campo obrigatório vazio ou inválido'
                WHEN s.quantidade <= 0 THEN 'A quantidade deve ser maior que zero.'
                ELSE 'Impacto inválido. Use só números, como 1500 ou 250.5 (ponto nos decimais).' END,
           concat_ws(' | ', s.patrocinador, s.veiculo, s.formato, s.espaco_programa,
                     coalesce(to_char(s.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'),
                              s.exibido_em_texto, s.mes),
                     s.quantidade_texto, s.impacto_texto)
    FROM stg_veiculacao s
    WHERE s.carga_id = p_carga_id
      AND (s.patrocinador IS NULL OR s.veiculo IS NULL OR s.formato IS NULL
           OR (s.exibido_em IS NULL) = (s.mes IS NULL)
           OR (s.mes IS NOT NULL AND s.mes !~ '^\d{4}-(0[1-9]|1[0-2])$')
           OR coalesce(s.quantidade, 0) <= 0
           OR (s.impacto IS NULL AND nullif(btrim(s.impacto_texto), '') IS NOT NULL));

    -- 2. Vínculo com patrocinador (nome canônico ou grafia alternativa), parceria da edição e linha do plano
    DROP TABLE IF EXISTS pg_temp.tmp_vinculo;
    CREATE TEMP TABLE tmp_vinculo ON COMMIT DROP AS
    SELECT s.linha,
           CASE WHEN s.mes IS NOT NULL THEN (s.mes || '-01')::date::timestamp AT TIME ZONE 'America/Sao_Paulo'
                ELSE s.exibido_em END AS exibido_em,
           s.quantidade, s.link, s.impacto,
           CASE WHEN s.mes IS NOT NULL THEN (s.mes || '-01')::date ELSE NULL END AS mes_referencia,
           CASE WHEN s.mes IS NOT NULL THEN 'MES' ELSE 'EXIBICAO' END AS periodo,
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
      AND (s.exibido_em IS NULL) <> (s.mes IS NULL)
      AND (s.mes IS NULL OR s.mes ~ '^\d{4}-(0[1-9]|1[0-2])$')
      AND coalesce(s.quantidade, 0) > 0
      AND (s.impacto IS NOT NULL OR nullif(btrim(s.impacto_texto), '') IS NULL);

    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT p_carga_id, linha,
           CASE WHEN parceria_id IS NULL THEN 'patrocinador' ELSE 'formato' END,
           CASE WHEN parceria_id IS NULL THEN 'Patrocinador sem parceria nesta edição'
                ELSE 'Aproveitamento não encontrado no plano comercial' END,
           concat_ws(' | ', patrocinador, veiculo, formato, espaco_programa,
                     coalesce(to_char(exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI'),
                              to_char(mes_referencia, 'MM/YYYY')))
    FROM tmp_vinculo WHERE aproveitamento_id IS NULL;

    -- [VALIDACAO] 3. O período misto: o total mensal e as exibições do mesmo mês e da mesma linha do plano nunca entram
    -- juntos. A conferência é feita aqui, na base e no próprio arquivo, para a linha virar rejeição com motivo em vez de
    -- derrubar a carga no gatilho rn_veiculacao_periodo.
    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT p_carga_id, t.linha,
           CASE WHEN t.periodo = 'MES' THEN 'periodo' ELSE 'exibido_em' END,
           CASE WHEN t.periodo = 'MES'
                     THEN 'Já há exibição desta linha do plano no mesmo mês; o total mensal e as exibições não se somam. Use um ou outro.'
                     ELSE 'Já há o total mensal desta linha do plano neste mês; as exibições e o total mensal não se somam. Use um ou outro.' END,
           concat_ws(' | ', t.patrocinador, t.veiculo, t.formato, t.espaco_programa,
                     CASE WHEN t.periodo = 'MES' THEN to_char(t.mes_referencia, 'MM/YYYY')
                          ELSE to_char(t.exibido_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') END)
    FROM tmp_vinculo t
    WHERE t.aproveitamento_id IS NOT NULL
      AND (
           (t.periodo = 'MES' AND (
                EXISTS (SELECT 1 FROM veiculacao v
                        WHERE v.aproveitamento_id = t.aproveitamento_id AND v.periodo = 'EXIBICAO'
                          AND v.exibido_em >= (t.mes_referencia::timestamp AT TIME ZONE 'America/Sao_Paulo')
                          AND v.exibido_em < ((t.mes_referencia + INTERVAL '1 month')::timestamp AT TIME ZONE 'America/Sao_Paulo'))
                OR EXISTS (SELECT 1 FROM tmp_vinculo o
                           WHERE o.aproveitamento_id = t.aproveitamento_id AND o.periodo = 'EXIBICAO'
                             AND date_trunc('month', o.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date = t.mes_referencia)))
           OR
           (t.periodo = 'EXIBICAO' AND (
                EXISTS (SELECT 1 FROM veiculacao v
                        WHERE v.aproveitamento_id = t.aproveitamento_id AND v.periodo = 'MES'
                          AND v.mes_referencia = date_trunc('month', t.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date)
                OR EXISTS (SELECT 1 FROM tmp_vinculo o
                           WHERE o.aproveitamento_id = t.aproveitamento_id AND o.periodo = 'MES'
                             AND o.mes_referencia = date_trunc('month', t.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date)))
          );

    SELECT count(*) INTO v_ok FROM tmp_vinculo t
    WHERE t.aproveitamento_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = p_carga_id AND r.linha = t.linha);

    -- [DUPLICIDADE] 4. Inserção sem contagem duplicada. A chave é aproveitamento + data/hora (sem a janela do plano).
    -- DISTINCT ON cobre a repetição dentro do próprio arquivo (fica a linha de menor número); ON CONFLICT cobre o que
    -- já está na base (reenvio). Uma linha reenviada com quantidade ou link corrigido também conta como duplicada:
    -- a primeira versão prevalece.
    INSERT INTO veiculacao (aproveitamento_id, exibido_em, quantidade, link, impacto, periodo, mes_referencia, carga_id)
    SELECT DISTINCT ON (aproveitamento_id, exibido_em)
           aproveitamento_id, exibido_em, quantidade, link, impacto, periodo, mes_referencia, p_carga_id
    FROM tmp_vinculo t
    WHERE aproveitamento_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM carga_rejeicao r WHERE r.carga_id = p_carga_id AND r.linha = t.linha)
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
-- 4. A consulta da mídia, linha a linha (M3)
-- ---------------------------------------------------------------------
-- Uma linha por linha do plano de categoria MIDIA e por mês com veiculação, no mês de Brasília. A linha do plano sem
-- nenhuma veiculação aparece uma vez, com mes nulo e exibido zero. O impacto é a soma das linhas do mês; nulo quando todas
-- são nulas. As contagens de comprovantes e de links só contam o que está preenchido. O previsto é o da linha inteira: o
-- plano não divide o previsto por mês.
CREATE VIEW vw_midia_detalhe AS
SELECT ap.parceria_id,
       ap.id AS aproveitamento_id,
       ap.veiculo,
       ap.formato,
       ap.espaco_programa,
       ap.tipo_midia,
       ap.unidade,
       ap.quantidade_prevista,
       date_trunc('month', v.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date AS mes,
       coalesce(sum(v.quantidade), 0) AS exibido,
       sum(v.impacto) AS impacto,
       count(v.id) AS exibicoes,
       count(v.comprovante_arquivo_id) AS comprovantes,
       count(v.link) AS links
FROM aproveitamento_previsto ap
LEFT JOIN veiculacao v ON v.aproveitamento_id = ap.id
WHERE ap.categoria = 'MIDIA'
GROUP BY ap.parceria_id, ap.id, ap.veiculo, ap.formato, ap.espaco_programa, ap.tipo_midia, ap.unidade,
         ap.quantidade_prevista, date_trunc('month', v.exibido_em AT TIME ZONE 'America/Sao_Paulo')::date;

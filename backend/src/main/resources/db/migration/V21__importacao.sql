-- V21: o banco da importação (fase F3 do plano da extração). Só acrescenta: as tabelas importacao e importacao_linha, as
-- colunas que ligam a linha importada ao que ela gravou, o localizador do trecho, a categoria IMPORTACAO do arquivo e três
-- gatilhos. Nenhuma V antiga é editada. A amostra não muda: nenhuma importação existe até a fase da amostra.
-- Retirar uma importação confirmada APAGA as linhas de veiculacao dela (D5): não há coluna de retirada em veiculacao, e por
-- isso as visões não mudam.

-- ---------------------------------------------------------------------
-- 1. A importação
-- ---------------------------------------------------------------------
-- [IMPORTACAO] Uma leitura em andamento. O alcance diz de quem são as linhas: a edição inteira, uma cota ou uma parceria. O
-- arquivo é sempre um arquivo do portal (texto colado também vira um .txt), e o documento é o mesmo arquivo já ingerido no
-- Atlas (E15). O destino sugerido é o que o JEV ou o modelo propôs; o destino é o que a pessoa escolheu. A situação só anda
-- pelas transições do gatilho rn_importacao_transicao.
CREATE TABLE importacao (
    id               BIGSERIAL    PRIMARY KEY,
    evento_id        BIGINT       NOT NULL REFERENCES evento(id),
    alcance          VARCHAR(10)  NOT NULL CONSTRAINT ck_importacao_alcance CHECK (alcance IN ('EDICAO', 'COTA', 'PARCERIA')),
    cota_tipo_id     SMALLINT     REFERENCES cota_tipo(id),
    parceria_id      BIGINT       REFERENCES parceria(id),
    -- A coerência do alcance é a mesma do escopo do documento (ck_documento_escopo, V11).
    CONSTRAINT ck_importacao_alcance_coerente CHECK (
        (alcance = 'EDICAO'   AND cota_tipo_id IS NULL     AND parceria_id IS NULL)
        OR (alcance = 'COTA'  AND cota_tipo_id IS NOT NULL AND parceria_id IS NULL)
        OR (alcance = 'PARCERIA' AND cota_tipo_id IS NULL  AND parceria_id IS NOT NULL)),
    origem           VARCHAR(10)  NOT NULL CONSTRAINT ck_importacao_origem CHECK (origem IN ('RBS', 'MARCA')),
    arquivo_id       BIGINT       NOT NULL REFERENCES arquivo(id),
    documento_id     BIGINT       REFERENCES documento(id),
    destino_sugerido VARCHAR(20)  CONSTRAINT ck_importacao_destino_sugerido
        CHECK (destino_sugerido IS NULL OR destino_sugerido IN ('VEICULACAO', 'COMPROVANTE', 'PESQUISA', 'NUMEROS_EDICAO',
                                                                'DADOS_ATIVACAO', 'PLANO', 'SO_CONSULTA')),
    destino          VARCHAR(20)  CONSTRAINT ck_importacao_destino
        CHECK (destino IS NULL OR destino IN ('VEICULACAO', 'COMPROVANTE', 'PESQUISA', 'NUMEROS_EDICAO',
                                              'DADOS_ATIVACAO', 'PLANO', 'SO_CONSULTA')),
    confianca        NUMERIC(4,3) CONSTRAINT ck_importacao_confianca CHECK (confianca IS NULL OR confianca BETWEEN 0 AND 1),
    situacao         VARCHAR(12)  NOT NULL DEFAULT 'LENDO'
        CONSTRAINT ck_importacao_situacao CHECK (situacao IN ('LENDO', 'EM_PREVIA', 'CONFIRMADA', 'DESCARTADA', 'RETIRADA', 'ERRO')),
    erro             TEXT,
    carga_id         BIGINT       REFERENCES carga(id),
    tokens_entrada   INT          CONSTRAINT ck_importacao_tokens_entrada CHECK (tokens_entrada IS NULL OR tokens_entrada >= 0),
    tokens_saida     INT          CONSTRAINT ck_importacao_tokens_saida CHECK (tokens_saida IS NULL OR tokens_saida >= 0),
    criada_por       BIGINT       NOT NULL REFERENCES usuario(id),
    criada_em        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmada_por   BIGINT       REFERENCES usuario(id),
    confirmada_em    TIMESTAMPTZ,
    retirada_por     BIGINT       REFERENCES usuario(id),
    retirada_em      TIMESTAMPTZ,
    CONSTRAINT ck_importacao_confirmacao CHECK ((confirmada_por IS NULL) = (confirmada_em IS NULL)),
    CONSTRAINT ck_importacao_retirada CHECK ((retirada_por IS NULL) = (retirada_em IS NULL))
);
CREATE INDEX ix_importacao_evento ON importacao (evento_id, criada_em DESC);
CREATE INDEX ix_importacao_parceria ON importacao (parceria_id) WHERE parceria_id IS NOT NULL;
CREATE INDEX ix_importacao_situacao ON importacao (situacao);

-- ---------------------------------------------------------------------
-- 2. As linhas propostas
-- ---------------------------------------------------------------------
-- [IMPORTACAO] Cada linha que a leitura propôs, no formato do destino em "dados", com a origem de cada campo em "origem":
-- {"campo": {"localizador": "Aba!B7", "trecho": "..."}}. A linha recusada fica com o motivo, nunca some (E10); a marca
-- resolvida e a linha do plano (a sugerida e a escolhida na prévia) ficam aqui para a conferência e a gravação.
CREATE TABLE importacao_linha (
    id                        BIGSERIAL    PRIMARY KEY,
    importacao_id             BIGINT       NOT NULL REFERENCES importacao(id) ON DELETE CASCADE,
    ordem                     INT          NOT NULL CONSTRAINT ck_importacao_linha_ordem CHECK (ordem >= 1),
    destino                   VARCHAR(20)  NOT NULL CONSTRAINT ck_importacao_linha_destino
        CHECK (destino IN ('VEICULACAO', 'COMPROVANTE', 'PESQUISA', 'NUMEROS_EDICAO', 'DADOS_ATIVACAO', 'PLANO', 'SO_CONSULTA')),
    dados                     JSONB        NOT NULL,
    origem                    JSONB        NOT NULL,
    situacao                  VARCHAR(12)  NOT NULL DEFAULT 'PROPOSTA'
        CONSTRAINT ck_importacao_linha_situacao CHECK (situacao IN ('PROPOSTA', 'EDITADA', 'ACEITA', 'DESCARTADA', 'RECUSADA')),
    motivo                    TEXT,
    parceria_id               BIGINT       REFERENCES parceria(id),
    aproveitamento_sugerido_id BIGINT      REFERENCES aproveitamento_previsto(id),
    aproveitamento_id         BIGINT       REFERENCES aproveitamento_previsto(id),
    semelhanca                NUMERIC(4,3) CONSTRAINT ck_importacao_linha_semelhanca CHECK (semelhanca IS NULL OR semelhanca BETWEEN 0 AND 1),
    editada_por               BIGINT       REFERENCES usuario(id),
    editada_em                TIMESTAMPTZ,
    CONSTRAINT uq_importacao_linha_ordem UNIQUE (importacao_id, ordem)
);
CREATE INDEX ix_importacao_linha_importacao ON importacao_linha (importacao_id, ordem);

-- [IMPORTACAO] A veiculação gravada aponta para a linha que a gerou (E14); a tela de dados e o Atlas abrem a origem. Sem
-- cascade: apagar a linha da importação só solta a veiculação (a confirmação é que apaga as linhas, na retirada).
ALTER TABLE veiculacao ADD COLUMN importacao_linha_id BIGINT REFERENCES importacao_linha(id) ON DELETE SET NULL;
CREATE INDEX ix_veiculacao_importacao_linha ON veiculacao (importacao_linha_id) WHERE importacao_linha_id IS NOT NULL;

-- [ATLAS] O localizador do trecho no arquivo: a célula ("Aba!B7"), o slide, o minuto ou a página de onde ele saiu (E15).
ALTER TABLE documento_trecho ADD COLUMN localizador VARCHAR(80);

-- ---------------------------------------------------------------------
-- 3. A categoria do arquivo importado
-- ---------------------------------------------------------------------
-- A lista da V16 mais IMPORTACAO (o arquivo que a importação guarda; o texto colado vira um .txt).
ALTER TABLE arquivo DROP CONSTRAINT ck_arquivo_categoria;
ALTER TABLE arquivo ADD CONSTRAINT ck_arquivo_categoria CHECK (categoria IN (
    'PLANO_COMERCIAL','MATERIAL_MARCA','MATERIAL_MIDIA','RELATORIO_VEICULACAO','COMPROVACAO','PESQUISA','CONTRATO',
    'DOCUMENTO_INGRESSOS','RELATORIO_PARCERIA','OUTRO','DOCUMENTO','FORMULARIO_INGRESSOS','IMPORTACAO'));

-- ---------------------------------------------------------------------
-- 4. As regras da importação
-- ---------------------------------------------------------------------
-- [IMPORTACAO] A situação só anda para frente: LENDO->EM_PREVIA, LENDO->ERRO, EM_PREVIA->CONFIRMADA,
-- EM_PREVIA->DESCARTADA e CONFIRMADA->RETIRADA. A edição, o alcance, a cota, a parceria, a origem, o arquivo e quem criou
-- (com a data) nunca mudam. CONFIRMADA exige quem confirmou e quando; RETIRADA, quem retirou e quando.
CREATE FUNCTION tg_importacao_transicao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.evento_id <> OLD.evento_id OR NEW.alcance <> OLD.alcance OR NEW.origem <> OLD.origem
           OR NEW.arquivo_id <> OLD.arquivo_id OR NEW.criada_por <> OLD.criada_por OR NEW.criada_em <> OLD.criada_em
           OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id
           OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id THEN
            RAISE EXCEPTION 'A edição, o alcance, a origem, o arquivo e quem criou a importação não mudam.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_transicao';
        END IF;
        IF NEW.situacao <> OLD.situacao
           AND NOT ((OLD.situacao = 'LENDO' AND NEW.situacao IN ('EM_PREVIA', 'ERRO'))
                 OR (OLD.situacao = 'EM_PREVIA' AND NEW.situacao IN ('CONFIRMADA', 'DESCARTADA'))
                 OR (OLD.situacao = 'CONFIRMADA' AND NEW.situacao = 'RETIRADA')) THEN
            RAISE EXCEPTION 'A importação não pode passar de % para %.', OLD.situacao, NEW.situacao
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_transicao';
        END IF;
    END IF;
    IF NEW.situacao = 'CONFIRMADA' AND (NEW.confirmada_por IS NULL OR NEW.confirmada_em IS NULL) THEN
        RAISE EXCEPTION 'A importação confirmada precisa de quem confirmou e de quando.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_transicao';
    END IF;
    IF NEW.situacao = 'RETIRADA' AND (NEW.retirada_por IS NULL OR NEW.retirada_em IS NULL) THEN
        RAISE EXCEPTION 'A importação retirada precisa de quem retirou e de quando.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_transicao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_importacao_transicao BEFORE INSERT OR UPDATE ON importacao
    FOR EACH ROW EXECUTE FUNCTION tg_importacao_transicao();

-- [IMPORTACAO] A linha só se cria, muda ou apaga com a importação em LENDO ou EM_PREVIA. Depois de confirmada, corrigir é
-- retirar a importação e importar de novo (D5).
CREATE FUNCTION tg_importacao_linha_previa() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_situacao VARCHAR(12);
BEGIN
    SELECT situacao INTO v_situacao FROM importacao
     WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.importacao_id ELSE NEW.importacao_id END;
    IF v_situacao IS NOT NULL AND v_situacao NOT IN ('LENDO', 'EM_PREVIA') THEN
        RAISE EXCEPTION 'A importação está % e as linhas não podem mais mudar. Retire a importação e importe de novo.', v_situacao
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_linha_previa';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_importacao_linha_previa BEFORE INSERT OR UPDATE OR DELETE ON importacao_linha
    FOR EACH ROW EXECUTE FUNCTION tg_importacao_linha_previa();

-- [IMPORTACAO] A marca da linha precisa estar no alcance da importação: a mesma edição e, quando o alcance é COTA ou
-- PARCERIA, a cota ou a parceria escolhida. Sem parceria resolvida (nula), não há o que conferir.
CREATE FUNCTION tg_importacao_linha_alcance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_importacao importacao%ROWTYPE;
BEGIN
    IF NEW.parceria_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO v_importacao FROM importacao WHERE id = NEW.importacao_id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF NOT EXISTS (SELECT 1 FROM parceria pa
                   WHERE pa.id = NEW.parceria_id
                     AND pa.evento_id = v_importacao.evento_id
                     AND (v_importacao.alcance <> 'COTA' OR pa.cota_tipo_id = v_importacao.cota_tipo_id)
                     AND (v_importacao.alcance <> 'PARCERIA' OR pa.id = v_importacao.parceria_id)) THEN
        RAISE EXCEPTION 'A marca da linha está fora do alcance da importação.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_importacao_linha_alcance';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_importacao_linha_alcance BEFORE INSERT OR UPDATE ON importacao_linha
    FOR EACH ROW EXECUTE FUNCTION tg_importacao_linha_alcance();

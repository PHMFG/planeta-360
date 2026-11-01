-- Sexta parte do Atlas: os prazos da edição, da cota e da parceria, com o documento e a página de onde cada um saiu (marco M5 do plano
-- do Atlas completo). A V1 a V12 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui. Só acrescenta: 1 tabela e
-- 2 gatilhos; nenhuma tabela, visão, função ou gatilho que já existia muda.
--
--   1. prazo: uma data que a marca precisa cumprir (ou que a produção marcou), no alcance de toda a edição, de uma cota ou de uma
--      parceria. É o que a ferramenta consultar_prazos do Atlas lê, junto do prazo das entregas.
--   2. Gatilhos: rn_prazo_coerente (a parceria, o documento e a página batem com o prazo) e rn_prazo_imutavel (só se desativa).

-- ---------------------------------------------------------------------
-- 1. prazo
-- ---------------------------------------------------------------------
-- [ATLAS] Quem cadastra é a RBS (rotas 62 a 64). O escopo diz a quem o prazo vale: GLOBAL (todas as parcerias da edição), COTA (as
-- parcerias dessa cota na edição) ou PARCERIA (uma só). O documento de origem e a página são opcionais; com eles, o Atlas cita de
-- onde o prazo veio, e o prazo some da resposta na hora em que o documento deixa de estar INDEXADO (revogado ou substituído).
-- Um prazo nunca se apaga: desativa-se (ativo passa de verdadeiro para falso e não volta), e o resto da linha não muda.
CREATE TABLE prazo (
    id                 BIGSERIAL    PRIMARY KEY,
    evento_id          BIGINT       NOT NULL REFERENCES evento(id),
    escopo             VARCHAR(10)  NOT NULL CHECK (escopo IN ('GLOBAL','COTA','PARCERIA')),
    cota_tipo_id       SMALLINT     REFERENCES cota_tipo(id),
    parceria_id        BIGINT       REFERENCES parceria(id),
    titulo             VARCHAR(200) NOT NULL CHECK (btrim(titulo) <> ''),
    data               DATE         NOT NULL CHECK (data BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
    -- Hora cheia ou com minutos, sem segundos ("18:00"). Nula quando o prazo vale o dia inteiro.
    hora               TIME(0),
    descricao          VARCHAR(1000) CHECK (descricao IS NULL OR btrim(descricao) <> ''),
    documento_id       BIGINT       REFERENCES documento(id),
    pagina             INTEGER,
    -- [PERMISSAO] Quem cadastra é da RBS: o perfil vai junto e a chave composta com usuario(id, perfil) só aceita ADMIN_RBS.
    criado_por         BIGINT       NOT NULL,
    criado_por_perfil  VARCHAR(20)  NOT NULL DEFAULT 'ADMIN_RBS' CHECK (criado_por_perfil = 'ADMIN_RBS'),
    criado_em          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ativo              BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_prazo_criador FOREIGN KEY (criado_por, criado_por_perfil) REFERENCES usuario(id, perfil),
    -- Coerência do escopo: GLOBAL sem cota nem parceria; COTA com cota e sem parceria; PARCERIA com parceria (e sem cota: a parceria
    -- já tem a cota dela).
    CONSTRAINT ck_prazo_escopo CHECK (
        (escopo = 'GLOBAL' AND cota_tipo_id IS NULL AND parceria_id IS NULL)
        OR (escopo = 'COTA' AND cota_tipo_id IS NOT NULL AND parceria_id IS NULL)
        OR (escopo = 'PARCERIA' AND cota_tipo_id IS NULL AND parceria_id IS NOT NULL)),
    -- A página é a de um documento, e a primeira é a 1.
    CONSTRAINT ck_prazo_pagina CHECK (pagina IS NULL OR (documento_id IS NOT NULL AND pagina >= 1)),
    CONSTRAINT ck_prazo_hora CHECK (hora IS NULL OR extract(second FROM hora) = 0)
);
CREATE INDEX ix_prazo_evento_data ON prazo (evento_id, data) WHERE ativo;
CREATE INDEX ix_prazo_parceria ON prazo (parceria_id) WHERE parceria_id IS NOT NULL;
CREATE INDEX ix_prazo_documento ON prazo (documento_id) WHERE documento_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. Gatilhos
-- ---------------------------------------------------------------------
-- [ATLAS] [ISOLAMENTO] O nome das regras começa com rn_ e a mensagem é a frase que a API devolve (409), como nos outros gatilhos.

-- Na criação: a parceria e o documento são da edição do prazo; a página existe no documento; e o prazo não tem alcance maior que o do
-- documento de onde veio (o documento COTA só tem prazo daquela cota, ou de uma parceria dela; o documento PARCERIA, só daquela
-- parceria). Sem isso, o título de um documento restrito chegaria a uma marca que não pode vê-lo. O documento não muda de escopo
-- depois de criado (rn_documento_congelado), então conferir uma vez basta.
CREATE FUNCTION tg_prazo_coerente() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_cota_da_parceria SMALLINT;
    v_evento_da_parceria BIGINT;
    d documento%ROWTYPE;
BEGIN
    IF NEW.parceria_id IS NOT NULL THEN
        SELECT evento_id, cota_tipo_id INTO v_evento_da_parceria, v_cota_da_parceria FROM parceria WHERE id = NEW.parceria_id;
        IF v_evento_da_parceria IS DISTINCT FROM NEW.evento_id THEN
            RAISE EXCEPTION 'A parceria do prazo tem de ser da edição do prazo.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_coerente';
        END IF;
    END IF;
    IF NEW.documento_id IS NOT NULL THEN
        SELECT * INTO d FROM documento WHERE id = NEW.documento_id;
        IF d.evento_id IS DISTINCT FROM NEW.evento_id THEN
            RAISE EXCEPTION 'O documento do prazo tem de ser da edição do prazo.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_coerente';
        END IF;
        IF NEW.pagina IS NOT NULL AND d.paginas IS NOT NULL AND NEW.pagina > d.paginas THEN
            RAISE EXCEPTION 'O documento do prazo tem % página(s); a página % não existe.', d.paginas, NEW.pagina
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_coerente';
        END IF;
        IF d.escopo = 'COTA' AND NOT (   (NEW.escopo = 'COTA' AND NEW.cota_tipo_id = d.cota_tipo_id)
                                      OR (NEW.escopo = 'PARCERIA' AND v_cota_da_parceria = d.cota_tipo_id)) THEN
            RAISE EXCEPTION 'O documento é de uma cota: o prazo dele só vale para essa cota ou para uma parceria dela.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_coerente';
        END IF;
        IF d.escopo = 'PARCERIA' AND NOT (NEW.escopo = 'PARCERIA' AND NEW.parceria_id = d.parceria_id) THEN
            RAISE EXCEPTION 'O documento é de uma parceria: o prazo dele só vale para essa parceria.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_coerente';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_prazo_coerente BEFORE INSERT ON prazo
    FOR EACH ROW EXECUTE FUNCTION tg_prazo_coerente();

-- Depois de criado, o prazo só se desativa: ativo passa de verdadeiro para falso e não volta, e nenhuma outra coluna muda. Para
-- corrigir um prazo, desativa-se e cria-se outro.
CREATE FUNCTION tg_prazo_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.evento_id IS DISTINCT FROM OLD.evento_id OR NEW.escopo IS DISTINCT FROM OLD.escopo
       OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id
       OR NEW.titulo IS DISTINCT FROM OLD.titulo OR NEW.data IS DISTINCT FROM OLD.data OR NEW.hora IS DISTINCT FROM OLD.hora
       OR NEW.descricao IS DISTINCT FROM OLD.descricao OR NEW.documento_id IS DISTINCT FROM OLD.documento_id
       OR NEW.pagina IS DISTINCT FROM OLD.pagina OR NEW.criado_por IS DISTINCT FROM OLD.criado_por
       OR NEW.criado_por_perfil IS DISTINCT FROM OLD.criado_por_perfil OR NEW.criado_em IS DISTINCT FROM OLD.criado_em THEN
        RAISE EXCEPTION 'Um prazo só muda para desativado. Para corrigir, desative este e crie outro.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_imutavel';
    END IF;
    IF NEW.ativo AND NOT OLD.ativo THEN
        RAISE EXCEPTION 'Um prazo desativado não volta a valer. Crie outro.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_prazo_imutavel';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_prazo_imutavel BEFORE UPDATE ON prazo
    FOR EACH ROW EXECUTE FUNCTION tg_prazo_imutavel();

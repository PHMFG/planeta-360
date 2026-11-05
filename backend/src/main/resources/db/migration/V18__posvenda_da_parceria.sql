-- V18: o anexo "Pós-venda da edição" do relatório de cada parceria (R8.1, fase F6 do plano final).
-- Só acrescenta uma tabela e dois gatilhos. Nenhuma V antiga é editada, e o catálogo de 17 indicadores e as 6 seções do relatório
-- não mudam: o anexo vem DEPOIS das 6 seções e a função fn_gerar_relatorio nem o enxerga.
-- (A V17 é do manual do Atlas e chega por outra branch; o Flyway aceita a lacuna enquanto ela não entra.)
-- Pré-condição para subir sobre um banco que já tem a amostra: nenhuma. A tabela nasce vazia.

-- ---------------------------------------------------------------------
-- 1. evento_posvenda: uma linha por versão publicada, por parceria
-- ---------------------------------------------------------------------
-- Cada publicação da RBS é uma linha nova e nunca muda: "corrigir" é publicar de novo (versão + 1), e a mais nova, se não foi retirada,
-- é a que a marca vê. Retirar não apaga: só preenche situacao, retirado_em e retirado_por (rn_evento_posvenda_imutavel). Por isso a chave
-- para a parceria NÃO tem ON DELETE CASCADE.
-- O texto editorial é dado, nunca instrução: não vai ao Atlas. Os links e a audiência por veículo ficam em colunas JSONB de lista, e o
-- gatilho rn_evento_posvenda_conteudo confere cada item no INSERT (são listas pequenas que sempre se gravam e se mostram juntas e nunca
-- se consultam por item, o que não pede uma tabela por lista):
--   links:      [{"tipo": "AFTERMOVIE" | "CLIPPING" | "OUTRO", "titulo": 1 a 160 caracteres, "url": http(s), até 500}], no máximo 30;
--   audiencias: [{"veiculo": 1 a 80, "metrica": 1 a 80 (ex.: ouvintes), "numero": maior que zero, "unidade": 1 a 20}], no máximo 20.
CREATE TABLE evento_posvenda (
    id            BIGSERIAL PRIMARY KEY,
    parceria_id   BIGINT   NOT NULL REFERENCES parceria(id),
    evento_id     BIGINT   NOT NULL REFERENCES evento(id),
    versao        SMALLINT NOT NULL CONSTRAINT ck_posvenda_versao CHECK (versao >= 1),
    texto         TEXT,
    links         JSONB    NOT NULL DEFAULT '[]'::jsonb,
    audiencias    JSONB    NOT NULL DEFAULT '[]'::jsonb,
    situacao      VARCHAR(10) NOT NULL DEFAULT 'PUBLICADO' CONSTRAINT ck_posvenda_situacao CHECK (situacao IN ('PUBLICADO', 'RETIRADO')),
    publicado_por BIGINT   NOT NULL REFERENCES usuario(id),
    publicado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    retirado_por  BIGINT   REFERENCES usuario(id),
    retirado_em   TIMESTAMPTZ,
    CONSTRAINT uq_posvenda_versao UNIQUE (parceria_id, versao),
    -- Sem os caracteres de controle, menos a quebra de linha e o tab. O NUL o PostgreSQL já recusa (22021, que a API trata antes com 422).
    CONSTRAINT ck_posvenda_texto CHECK (texto IS NULL OR (length(btrim(texto)) BETWEEN 1 AND 5000 AND texto !~ '[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]')),
    CONSTRAINT ck_posvenda_retirada CHECK ((situacao = 'RETIRADO') = (retirado_em IS NOT NULL) AND (retirado_em IS NULL) = (retirado_por IS NULL))
);
CREATE INDEX ix_posvenda_evento ON evento_posvenda (evento_id, parceria_id, versao DESC);

-- O conteúdo confere no INSERT: a edição é a da parceria, as duas listas são listas, o anexo não está vazio e cada item tem a forma certa.
CREATE FUNCTION tg_evento_posvenda_conteudo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_item jsonb;
    -- O mesmo formato do link de conteúdo (V16): http ou https, com host, sem "usuario@", sem espaço, controle nem bidi.
    v_url CONSTANT text := '^https?://[^[:space:][:cntrl:]‎‏‪-‮⁦-⁩@/?#]+([/?#][^[:space:][:cntrl:]‎‏‪-‮⁦-⁩]*)?$';
    v_controle CONSTANT text := '[\x01-\x08\x0b-\x1f\x7f]';
BEGIN
    IF NEW.evento_id IS DISTINCT FROM (SELECT pa.evento_id FROM parceria pa WHERE pa.id = NEW.parceria_id) THEN
        RAISE EXCEPTION 'O pós-venda precisa ser da edição da parceria.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
    END IF;
    IF jsonb_typeof(NEW.links) IS DISTINCT FROM 'array' OR jsonb_typeof(NEW.audiencias) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Os links e as audiências do pós-venda são listas.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
    END IF;
    IF jsonb_array_length(NEW.links) > 30 OR jsonb_array_length(NEW.audiencias) > 20 THEN
        RAISE EXCEPTION 'O pós-venda aceita até 30 links e 20 audiências.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
    END IF;
    IF NEW.texto IS NULL AND jsonb_array_length(NEW.links) = 0 AND jsonb_array_length(NEW.audiencias) = 0 THEN
        RAISE EXCEPTION 'O pós-venda precisa de texto, de link ou de audiência.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
    END IF;
    FOR v_item IN SELECT * FROM jsonb_array_elements(NEW.links) LOOP
        IF jsonb_typeof(v_item) IS DISTINCT FROM 'object'
           OR jsonb_typeof(v_item -> 'tipo') IS DISTINCT FROM 'string' OR (v_item ->> 'tipo') NOT IN ('AFTERMOVIE', 'CLIPPING', 'OUTRO')
           OR jsonb_typeof(v_item -> 'titulo') IS DISTINCT FROM 'string' OR length(btrim(v_item ->> 'titulo')) NOT BETWEEN 1 AND 160
           OR (v_item ->> 'titulo') ~ v_controle
           OR jsonb_typeof(v_item -> 'url') IS DISTINCT FROM 'string' OR length(v_item ->> 'url') > 500 OR (v_item ->> 'url') !~ v_url THEN
            RAISE EXCEPTION 'Cada link do pós-venda precisa de tipo, título e um endereço http ou https.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
        END IF;
    END LOOP;
    FOR v_item IN SELECT * FROM jsonb_array_elements(NEW.audiencias) LOOP
        IF jsonb_typeof(v_item) IS DISTINCT FROM 'object'
           OR jsonb_typeof(v_item -> 'veiculo') IS DISTINCT FROM 'string' OR length(btrim(v_item ->> 'veiculo')) NOT BETWEEN 1 AND 80
           OR jsonb_typeof(v_item -> 'metrica') IS DISTINCT FROM 'string' OR length(btrim(v_item ->> 'metrica')) NOT BETWEEN 1 AND 80
           OR jsonb_typeof(v_item -> 'unidade') IS DISTINCT FROM 'string' OR length(btrim(v_item ->> 'unidade')) NOT BETWEEN 1 AND 20
           OR ((v_item ->> 'veiculo') || (v_item ->> 'metrica') || (v_item ->> 'unidade')) ~ v_controle
           OR jsonb_typeof(v_item -> 'numero') IS DISTINCT FROM 'number' THEN
            RAISE EXCEPTION 'Cada audiência do pós-venda precisa de veículo, métrica, número maior que zero e unidade.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
        END IF;
        IF (v_item ->> 'numero')::numeric <= 0 OR (v_item ->> 'numero')::numeric >= 100000000000000 THEN
            RAISE EXCEPTION 'Cada audiência do pós-venda precisa de veículo, métrica, número maior que zero e unidade.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_conteudo';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_evento_posvenda_conteudo BEFORE INSERT ON evento_posvenda
    FOR EACH ROW EXECUTE FUNCTION tg_evento_posvenda_conteudo();

-- O que foi publicado não muda nem se apaga. A única mudança aceita é a retirada: PUBLICADO vira RETIRADO, com quem e quando, e mais nada.
CREATE FUNCTION tg_evento_posvenda_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.situacao = 'PUBLICADO' AND NEW.situacao = 'RETIRADO'
       AND NEW.retirado_em IS NOT NULL AND NEW.retirado_por IS NOT NULL
       AND to_jsonb(NEW) - 'situacao' - 'retirado_em' - 'retirado_por' = to_jsonb(OLD) - 'situacao' - 'retirado_em' - 'retirado_por' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'O pós-venda publicado não pode ser alterado nem apagado. Para retirar, use a retirada; para corrigir, publique uma versão nova.'
        USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_evento_posvenda_imutavel';
END $$;
CREATE TRIGGER rn_evento_posvenda_imutavel BEFORE UPDATE OR DELETE ON evento_posvenda
    FOR EACH ROW EXECUTE FUNCTION tg_evento_posvenda_imutavel();

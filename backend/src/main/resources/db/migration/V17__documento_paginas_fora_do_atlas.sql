-- V17: páginas de um documento que ficam fora do Atlas (R2.1 do plano final).
-- O manual de operações de 2026 tem 54 páginas e só a p. 4 (os contatos, com 12 telefones) trava a aprovação. Em vez de cortar o PDF,
-- a RBS marca as páginas que não entram no índice: o detector as ignora, nenhum trecho delas nasce, e o arquivo continua inteiro no
-- visualizador (rotas 59 e 60). Só acrescenta: 2 colunas, 1 gatilho novo e o corpo novo do rn_documento_congelado. Nenhuma V antiga é editada.
--
--   1. documento.paginas_ignoradas: os números das páginas (base 1) fora do Atlas. A ingestão re-extrai o arquivo na aprovação e lê esta
--      coluna; o texto dessas páginas nunca chega ao divisor, ao detector, aos embeddings nem ao modelo.
--   2. documento.suspeitas: onde o detector achou dado pessoal, só a página e o tipo (CPF, RG ou CONTATOS), nunca o valor achado.
--   3. rn_documento_paginas_ignoradas: a lista é válida (inteiros de 1 até o total de páginas, sem repetição, no máximo 20, sem
--      esvaziar o documento inteiro) e só muda enquanto o documento aguarda aprovação.
--   4. rn_documento_congelado (corpo novo): a suspeita de dado pessoal continua sem se desmarcar, EXCETO junto de uma mudança das
--      páginas fora do Atlas, num documento que continua aguardando aprovação (a rota que marca as páginas recalcula a suspeita).
--
-- Quem marcou as páginas fica no histórico (entidade documento, ação EDICAO), sem o conteúdo delas.

ALTER TABLE documento
    ADD COLUMN paginas_ignoradas SMALLINT[] NOT NULL DEFAULT '{}',
    ADD COLUMN suspeitas JSONB NOT NULL DEFAULT '[]',
    ADD CONSTRAINT ck_documento_suspeitas CHECK (jsonb_typeof(suspeitas) = 'array');

CREATE FUNCTION tg_documento_paginas_ignoradas() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_total INTEGER;
    v_distintas INTEGER;
    v_menor INTEGER;
    v_maior INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.paginas_ignoradas IS DISTINCT FROM OLD.paginas_ignoradas AND OLD.status <> 'AGUARDANDO_APROVACAO' THEN
        RAISE EXCEPTION 'As páginas fora do Atlas só mudam enquanto o documento aguarda aprovação. Para mudar depois, envie o documento de novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_paginas_ignoradas';
    END IF;
    v_total := cardinality(NEW.paginas_ignoradas);
    IF v_total = 0 THEN
        RETURN NEW;
    END IF;
    SELECT count(DISTINCT p), min(p), max(p) INTO v_distintas, v_menor, v_maior FROM unnest(NEW.paginas_ignoradas) AS p;
    IF v_total > 20 OR v_distintas <> v_total OR v_menor < 1 OR NEW.paginas IS NULL OR v_maior > NEW.paginas OR v_total >= NEW.paginas THEN
        RAISE EXCEPTION 'As páginas fora do Atlas são números de 1 até o total de páginas, sem repetir, no máximo 20, e ao menos uma página precisa ficar no Atlas.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_paginas_ignoradas';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_paginas_ignoradas BEFORE INSERT OR UPDATE OF paginas_ignoradas, paginas ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_paginas_ignoradas();

CREATE OR REPLACE FUNCTION tg_documento_congelado() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.escopo IS DISTINCT FROM OLD.escopo OR NEW.evento_id IS DISTINCT FROM OLD.evento_id
       OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id
       OR NEW.chave IS DISTINCT FROM OLD.chave OR NEW.versao IS DISTINCT FROM OLD.versao
       OR (NEW.contem_dados_pessoais IS DISTINCT FROM OLD.contem_dados_pessoais
           AND NOT (OLD.status = 'AGUARDANDO_APROVACAO' AND NEW.status = 'AGUARDANDO_APROVACAO'
                    AND NEW.paginas_ignoradas IS DISTINCT FROM OLD.paginas_ignoradas)) THEN
        RAISE EXCEPTION 'O documento fica na edição, no escopo, na cota, na parceria, na chave e na versão em que foi criado, e a suspeita de dado pessoal só muda junto das páginas fora do Atlas. Para mudar isso, envie o documento de novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_congelado';
    END IF;
    RETURN NEW;
END $$;

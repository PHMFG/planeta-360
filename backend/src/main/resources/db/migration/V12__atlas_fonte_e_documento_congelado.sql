-- Quinta parte do Atlas: de onde veio cada afirmação do chat e o documento que não muda de lugar (marco M4 do plano do Atlas completo).
-- A V1 a V11 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui. Acrescenta uma tabela, dois gatilhos e duas
-- funções de gatilho; da V11 muda só a fn_texto_de_busca (a coluna tsv é refeita com ela). Nenhuma outra tabela, visão ou gatilho muda.
--
--   1. atlas_fonte: as fontes que a resposta do Atlas citou ([T1] trecho de documento, [R1] registro do portal, [C1] conta),
--      gravadas quando o texto é aceito. Dela a conversa tira o campo "fontes" do item de texto.
--   2. documento congelado: o escopo, a edição, a cota, a parceria, a chave, a versão e a suspeita de dado pessoal de um documento já
--      criado não mudam mais (a V11 só travava o trecho).
--   3. Busca textual: o plural em -ais, -éis, -áveis, -úteis e -ães passa a casar com o singular (animal e animais, papel e papéis).

-- ---------------------------------------------------------------------
-- 1. atlas_fonte
-- ---------------------------------------------------------------------
-- [ATLAS] Uma linha por fonte citada na mensagem do assistente que o servidor aceitou. O marcador (T1, R2, C1) é o do turno em que a
-- resposta foi escrita: vale só para aquela mensagem. TRECHO aponta o trecho e o documento (e guarda a versão e a página que a
-- resposta citou, porque o documento pode ser substituído depois); REGISTRO aponta a linha do portal (entidade e id); CALCULO leva a
-- conta inteira em "dados" (script, variáveis e resultado). "dados" também guarda o título e a seção do trecho como estavam.
CREATE TABLE atlas_fonte (
    id                BIGSERIAL    PRIMARY KEY,
    mensagem_id       BIGINT       NOT NULL REFERENCES atlas_mensagem(id),
    marcador          VARCHAR(8)   NOT NULL CHECK (marcador ~ '^[TRC][1-9][0-9]{0,2}$'),
    tipo              VARCHAR(10)  NOT NULL CHECK (tipo IN ('TRECHO', 'REGISTRO', 'CALCULO')),
    trecho_id         BIGINT       REFERENCES documento_trecho(id),
    documento_id      BIGINT       REFERENCES documento(id),
    versao            INTEGER      CHECK (versao >= 1),
    pagina            INTEGER      CHECK (pagina >= 1),
    registro_entidade VARCHAR(40),
    registro_id       BIGINT,
    dados             JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(dados) = 'object'),
    criada_em         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Um marcador por mensagem.
    CONSTRAINT uq_atlas_fonte_marcador UNIQUE (mensagem_id, marcador),
    -- A letra do marcador diz o tipo, e cada tipo traz o que lhe cabe e nada do que não lhe cabe.
    CONSTRAINT ck_atlas_fonte_tipo CHECK (
        (tipo = 'TRECHO' AND marcador LIKE 'T%' AND trecho_id IS NOT NULL AND documento_id IS NOT NULL AND versao IS NOT NULL
             AND pagina IS NOT NULL AND registro_entidade IS NULL AND registro_id IS NULL)
        OR (tipo = 'REGISTRO' AND marcador LIKE 'R%' AND registro_entidade IS NOT NULL AND registro_id IS NOT NULL
             AND trecho_id IS NULL AND documento_id IS NULL AND versao IS NULL AND pagina IS NULL)
        OR (tipo = 'CALCULO' AND marcador LIKE 'C%' AND trecho_id IS NULL AND documento_id IS NULL AND versao IS NULL AND pagina IS NULL
             AND registro_entidade IS NULL AND registro_id IS NULL))
);
CREATE INDEX ix_atlas_fonte_mensagem ON atlas_fonte (mensagem_id);
CREATE INDEX ix_atlas_fonte_documento ON atlas_fonte (documento_id) WHERE documento_id IS NOT NULL;

-- Fonte só de mensagem do assistente: é a resposta dele que cita.
CREATE FUNCTION tg_atlas_fonte_mensagem() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM atlas_mensagem WHERE id = NEW.mensagem_id AND papel = 'assistant') THEN
        RAISE EXCEPTION 'Só a mensagem do assistente cita fonte.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_atlas_fonte_mensagem';
    END IF;
    -- O trecho e o documento citados são o mesmo (a versão citada é a do trecho).
    IF NEW.tipo = 'TRECHO' AND NOT EXISTS (SELECT 1 FROM documento_trecho WHERE id = NEW.trecho_id AND documento_id = NEW.documento_id
                                           AND versao = NEW.versao AND pagina_inicio <= NEW.pagina AND NEW.pagina <= pagina_fim) THEN
        RAISE EXCEPTION 'O trecho citado não é do documento, da versão e da página dados.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_atlas_fonte_mensagem';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_atlas_fonte_mensagem BEFORE INSERT ON atlas_fonte
    FOR EACH ROW EXECUTE FUNCTION tg_atlas_fonte_mensagem();

-- ---------------------------------------------------------------------
-- 2. documento congelado
-- ---------------------------------------------------------------------
-- [ISOLAMENTO] A V11 deixava um UPDATE direto trocar o escopo de um documento ativo (o anexo de uma parceria passaria a ser de todas)
-- e passar contem_dados_pessoais para falso junto com PROCESSANDO (o detector seria contornado). Nenhuma rota faz isso, mas o banco
-- é quem garante: depois de criado, o documento fica na edição, no escopo, na cota, na parceria, na chave e na versão em que nasceu, e
-- a suspeita de dado pessoal que o detector marcou no envio não se desmarca. Mudar de escopo ou tirar a suspeita é um novo envio.
CREATE FUNCTION tg_documento_congelado() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.escopo IS DISTINCT FROM OLD.escopo OR NEW.evento_id IS DISTINCT FROM OLD.evento_id
       OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id
       OR NEW.chave IS DISTINCT FROM OLD.chave OR NEW.versao IS DISTINCT FROM OLD.versao
       OR NEW.contem_dados_pessoais IS DISTINCT FROM OLD.contem_dados_pessoais THEN
        RAISE EXCEPTION 'O documento fica na edição, no escopo, na cota, na parceria, na chave e na versão em que foi criado, e a suspeita de dado pessoal não se desmarca. Para mudar isso, envie o documento de novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_congelado';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_congelado BEFORE UPDATE OF escopo, evento_id, cota_tipo_id, parceria_id, chave, versao, contem_dados_pessoais ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_congelado();

-- ---------------------------------------------------------------------
-- 3. Busca textual: plural em -ais, -éis, -áveis, -úteis e -ães
-- ---------------------------------------------------------------------
-- A V11 normalizava "-ões" e "-iais/-erais", e o resto do plural em -ais ficava de fora: "animais" virava anim e "animal" ficava animal,
-- e a busca textual (a única, no modo sem embeddings) não achava o trecho de quem perguntava no singular. Medido em 01/11/2026 com
-- 18 pares singular/plural que o kit tem (animal/animais, nacional/nacionais, papel/papéis, inflamável/inflamáveis, cão/cães...):
-- a V11 achava 7, esta função acha 17 (o 18º, "final", perde para outros trechos que a palavra também casa). O recall do gabarito não
-- mudou (as perguntas do gabarito usam as palavras do documento).
-- O que NÃO pode mudar: mais, demais, jamais, pais, cais e vais (a exceção da regra do -ais), e reis, leis, bois e dois (que o -eis
-- não toca, porque só o -éis acentuado vira -el). "sais" passa a casar com "sal", que é o singular dele.
-- A coluna gerada depende da função, e uma função IMMUTABLE não pode mudar com a coluna já gravada: tira a coluna (o índice vai com
-- ela), troca a função e refaz a coluna e o índice com a regra nova.
ALTER TABLE documento_trecho DROP COLUMN tsv;

CREATE OR REPLACE FUNCTION fn_texto_de_busca(t TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(lower(t),
        '(õ|o)es\M', 'ao', 'g'),
        '\m(?!(?:de)?mais\M|jamais\M|pais\M|cais\M|vais\M)([[:alpha:]]+)ais\M', '\1al', 'g'),
        'éis\M', 'el', 'g'),
        '([áí])veis\M', '\1vel', 'g'),
        'úteis\M', 'útil', 'g'),
        'ães\M', 'ão', 'g')
$$;

-- No PostgreSQL 18 a coluna gerada é virtual por padrão: STORED explícito, para o GIN indexar o valor gravado.
ALTER TABLE documento_trecho ADD COLUMN tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('pt_sem_acento', fn_texto_de_busca(coalesce(secao_caminho, '') || ' ' || texto))) STORED;
CREATE INDEX ix_documento_trecho_tsv ON documento_trecho USING gin (tsv);

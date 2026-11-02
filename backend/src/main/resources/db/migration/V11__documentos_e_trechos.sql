-- Quarta parte do Atlas: os documentos que a RBS sobe (manuais, planos comerciais, anexos de parceria, FAQ) e os trechos deles, com
-- embeddings, para o Atlas responder com fonte (marco M3 do plano do Atlas completo).
-- A V1 a V10 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui. Só acrescenta: uma categoria nova de arquivo
-- (DOCUMENTO), a busca textual sem acento, 2 tabelas, 7 gatilhos e 3 funções; nenhuma tabela, visão, função ou gatilho que já existia
-- muda além do CHECK da categoria do arquivo.
--
--   1. Pré-requisitos: a extensão vector (pgvector) e o papel atlas_ro precisam existir, e a extensão unaccent é criada aqui.
--   2. Busca textual: a configuração pt_sem_acento e fn_texto_de_busca (singular e plural, com e sem acento, casam).
--   3. arquivo: a categoria DOCUMENTO.
--   4. documento: o documento lógico (a chave), a versão, o escopo (global, cota ou parceria) e o ciclo de aprovação e indexação.
--   5. documento_trecho: os trechos, com o vetor de 768 dimensões, o tsvector e o escopo copiado do documento.
--   6. RLS: o papel atlas_ro só lê trecho ativo e documento indexado da edição, da cota e da parceria do contexto da busca.
--   7. Gatilhos e fn_publicar_documento: transições de status, aprovação, dados pessoais, coerência, revogação.

-- ---------------------------------------------------------------------
-- 1. Pré-requisitos
-- ---------------------------------------------------------------------
-- [AMBIENTE] O vector não é uma extensão "trusted": quem a instala é um superusuário, pelo scripts/preparar-banco-atlas.sh, que também
-- cria o papel atlas_ro. A migração só confere. O tipo vector é usado SEM prefixo de schema (vale o search_path do banco), para a mesma
-- migração rodar onde a extensão fica em outro schema (aqui, extensoes; no Render, public).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'O Atlas precisa da extensão vector (pgvector) neste banco. Rode scripts/preparar-banco-atlas.sh e suba a API de novo.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'atlas_ro') THEN
        RAISE EXCEPTION 'O Atlas precisa do papel atlas_ro neste PostgreSQL. Rode scripts/preparar-banco-atlas.sh e suba a API de novo.';
    END IF;
END $$;

-- O unaccent é "trusted": o dono do banco o cria. Em public, de propósito: a configuração de busca abaixo depende dele.
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;

-- O papel atlas_ro precisa enxergar o schema onde as tabelas e as funções moram.
GRANT USAGE ON SCHEMA public TO atlas_ro;

-- ---------------------------------------------------------------------
-- 2. Busca textual sem acento
-- ---------------------------------------------------------------------
-- O unaccent() é STABLE e não entra em coluna gerada; o to_tsvector('pt_sem_acento', ...) é IMMUTABLE e entra. Só isso não basta:
-- sem acento, o stemmer português corta "informação" em informaca e "informações" em informaco, que não casam; com acento ele erra
-- outros pares ("inserção" vira inserçã e "inserções" vira inserçõ, e "credencial" não casa com "credenciais"). Por isso o texto
-- passa antes por fn_texto_de_busca, que normaliza esses plurais. A coluna gerada e a consulta usam a MESMA função.
CREATE TEXT SEARCH CONFIGURATION pt_sem_acento (COPY = portuguese);
ALTER TEXT SEARCH CONFIGURATION pt_sem_acento
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, portuguese_stem;

CREATE FUNCTION fn_texto_de_busca(t TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT regexp_replace(regexp_replace(lower(t), '(õ|o)es\M', 'ao', 'g'), '(i|er)ais\M', '\1al', 'g')
$$;

-- ---------------------------------------------------------------------
-- 3. arquivo: a categoria DOCUMENTO
-- ---------------------------------------------------------------------
-- [ISOLAMENTO] DOCUMENTO é só do envio da rota 54 (o ArquivoService, da rota 17, não a aceita). A conferência "materiais de parceria
-- sem aba" do verificacao.sql exclui esta categoria: o anexo de uma parceria (D06, D07, D11) é um arquivo da parceria sem aba, e a
-- marca nunca o baixa pela rota 18 (o ArquivoRepositorio sabe que ele é de um documento).
ALTER TABLE arquivo DROP CONSTRAINT arquivo_categoria_check;
ALTER TABLE arquivo ADD CONSTRAINT ck_arquivo_categoria CHECK (categoria IN (
    'PLANO_COMERCIAL','MATERIAL_MARCA','MATERIAL_MIDIA','RELATORIO_VEICULACAO','COMPROVACAO','PESQUISA','CONTRATO',
    'DOCUMENTO_INGRESSOS','RELATORIO_PARCERIA','OUTRO','DOCUMENTO'));

-- ---------------------------------------------------------------------
-- 4. documento
-- ---------------------------------------------------------------------
-- [ATLAS] O documento lógico é a chave (manual-operacoes); cada envio é uma versão dele. O GRUPO do documento é (edição, escopo, cota,
-- parceria, chave): documentos de grupos diferentes nunca se substituem. Só a RBS envia e aprova; dado pessoal bloqueia a aprovação.
--  - AGUARDANDO_APROVACAO: enviado e lido; espera a RBS. Pode ser RECUSADO. Nasce ERRO se o texto não pôde ser lido (PDF só de imagem,
--    com senha, corrompido), sem aprovado_por: esse caso se corrige com um novo envio, e não reindexando.
--  - PROCESSANDO: aprovado; a ingestão (extrair, dividir, embeddings, gravar os trechos) está rodando. Sem estado parado entre os dois.
--  - INDEXADO: os trechos estão ativos e a busca os enxerga. SUBSTITUIDO: uma versão mais nova do grupo foi indexada. REVOGADO: a RBS
--    tirou do ar (vale na hora, também para o que uma conversa já buscou).
--  - ERRO: a ingestão falhou; reindexar (volta a PROCESSANDO) só vale com aprovado_por.
CREATE TABLE documento (
    id                    BIGSERIAL    PRIMARY KEY,
    titulo                VARCHAR(200) NOT NULL CHECK (btrim(titulo) <> ''),
    chave                 VARCHAR(80)  NOT NULL CHECK (chave ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    versao                INTEGER      NOT NULL CHECK (versao >= 1),
    tipo                  VARCHAR(20)  NOT NULL
                          CHECK (tipo IN ('MANUAL_OPERACOES','PLANO_COMERCIAL','ANEXO_PARCERIA','FAQ','COMUNICADO','OUTRO')),
    -- O escopo: GLOBAL (todas as parcerias da edição), COTA (as parcerias dessa cota na edição) ou PARCERIA (só uma).
    escopo                VARCHAR(10)  NOT NULL CHECK (escopo IN ('GLOBAL','COTA','PARCERIA')),
    evento_id             BIGINT       NOT NULL REFERENCES evento(id),
    cota_tipo_id          SMALLINT     REFERENCES cota_tipo(id),
    parceria_id           BIGINT       REFERENCES parceria(id),
    arquivo_id            BIGINT       NOT NULL,
    status                VARCHAR(22)  NOT NULL DEFAULT 'AGUARDANDO_APROVACAO'
                          CHECK (status IN ('AGUARDANDO_APROVACAO','RECUSADO','PROCESSANDO','INDEXADO','ERRO','SUBSTITUIDO','REVOGADO')),
    -- Suspeita de CPF, RG ou lista de contatos (o DetectorDeDadosPessoais). Com a suspeita o documento não pode ser aprovado.
    contem_dados_pessoais BOOLEAN      NOT NULL DEFAULT FALSE,
    paginas               INTEGER      CHECK (paginas >= 1),
    modelo_embedding      VARCHAR(60),
    dimensoes             INTEGER      CHECK (dimensoes >= 1),
    extracao_ms           INTEGER      CHECK (extracao_ms >= 0),
    embeddings_ms         INTEGER      CHECK (embeddings_ms >= 0),
    trechos               INTEGER      NOT NULL DEFAULT 0 CHECK (trechos >= 0),
    erro                  TEXT,
    enviado_por           BIGINT       NOT NULL REFERENCES usuario(id),
    enviado_em            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- [PERMISSAO] Quem aprovou é da RBS: o perfil vai junto e a chave composta com usuario(id, perfil) só aceita ADMIN_RBS.
    aprovado_por          BIGINT,
    aprovado_por_perfil   VARCHAR(20)  CHECK (aprovado_por_perfil = 'ADMIN_RBS'),
    aprovado_em           TIMESTAMPTZ,
    revogado_por          BIGINT       REFERENCES usuario(id),
    revogado_em           TIMESTAMPTZ,
    CONSTRAINT fk_documento_aprovador FOREIGN KEY (aprovado_por, aprovado_por_perfil) REFERENCES usuario(id, perfil),
    CONSTRAINT ck_documento_aprovacao CHECK ((aprovado_por IS NULL) = (aprovado_por_perfil IS NULL)
                                             AND (aprovado_por IS NULL) = (aprovado_em IS NULL)),
    -- Coerência do escopo: GLOBAL sem cota nem parceria; COTA com cota e sem parceria; PARCERIA com parceria (e sem cota: a parceria
    -- já tem a cota dela).
    CONSTRAINT ck_documento_escopo CHECK (
        (escopo = 'GLOBAL' AND cota_tipo_id IS NULL AND parceria_id IS NULL)
        OR (escopo = 'COTA' AND cota_tipo_id IS NOT NULL AND parceria_id IS NULL)
        OR (escopo = 'PARCERIA' AND cota_tipo_id IS NULL AND parceria_id IS NOT NULL)),
    -- O arquivo de um documento PARCERIA é da parceria. A chave composta é MATCH SIMPLE e não vale com parceria nula: nos documentos
    -- GLOBAL e COTA (arquivo geral, sem parceria) quem confere é o gatilho rn_documento_coerente.
    CONSTRAINT fk_documento_arquivo FOREIGN KEY (arquivo_id) REFERENCES arquivo(id),
    CONSTRAINT fk_documento_arquivo_parceria FOREIGN KEY (arquivo_id, parceria_id) REFERENCES arquivo(id, parceria_id),
    CONSTRAINT uq_documento_arquivo UNIQUE (arquivo_id),
    CONSTRAINT ck_documento_erro CHECK (status <> 'ERRO' OR erro IS NOT NULL),
    CONSTRAINT ck_documento_aprovado CHECK (status NOT IN ('PROCESSANDO','INDEXADO','SUBSTITUIDO') OR aprovado_por IS NOT NULL),
    CONSTRAINT ck_documento_indexado CHECK (status NOT IN ('INDEXADO','SUBSTITUIDO','REVOGADO')
                                            OR (trechos >= 1 AND modelo_embedding IS NOT NULL AND dimensoes IS NOT NULL)),
    CONSTRAINT ck_documento_revogado CHECK (status <> 'REVOGADO' OR (revogado_por IS NOT NULL AND revogado_em IS NOT NULL))
);
-- O grupo (edição, escopo, cota, parceria, chave) e a versão: sem NULLS NOT DISTINCT, dois documentos GLOBAL iguais passariam,
-- porque a cota e a parceria são nulas (e nulo nunca é igual a nulo num índice único).
CREATE UNIQUE INDEX uq_documento_grupo_versao ON documento (evento_id, escopo, cota_tipo_id, parceria_id, chave, versao) NULLS NOT DISTINCT;
CREATE INDEX ix_documento_status ON documento (status);
CREATE INDEX ix_documento_parceria ON documento (parceria_id) WHERE parceria_id IS NOT NULL;

-- Trava o grupo do documento até o fim da transação: a mesma chave de trava serve ao envio (que calcula a versão) e à publicação.
CREATE FUNCTION fn_trava_grupo_documento(p_evento BIGINT, p_escopo TEXT, p_cota SMALLINT, p_parceria BIGINT, p_chave TEXT)
RETURNS VOID LANGUAGE sql AS $$
    SELECT pg_advisory_xact_lock(hashtextextended('documento|' || p_evento || '|' || p_escopo || '|' || coalesce(p_cota::text, '')
                                                  || '|' || coalesce(p_parceria::text, '') || '|' || p_chave, 0))
$$;

-- ---------------------------------------------------------------------
-- 5. documento_trecho
-- ---------------------------------------------------------------------
-- Os trechos do documento. O escopo, a edição, a cota, a parceria e a versão são COPIADOS do documento pelo gatilho
-- rn_documento_trecho_coerente (e não mudam depois): a RLS e os filtros da busca não precisam juntar com a tabela documento.
-- ativo só vale para trecho de documento INDEXADO (rn_documento_trecho_ativo); a revogação e a substituição desligam os trechos.
-- O vetor é de 768 dimensões (gemini-embedding-2). O tipo vector é usado sem prefixo de schema.
CREATE TABLE documento_trecho (
    id            BIGSERIAL   PRIMARY KEY,
    documento_id  BIGINT      NOT NULL REFERENCES documento(id),
    escopo        VARCHAR(10) NOT NULL,
    evento_id     BIGINT      NOT NULL,
    cota_tipo_id  SMALLINT,
    parceria_id   BIGINT,
    versao        INTEGER     NOT NULL,
    ordem         INTEGER     NOT NULL CHECK (ordem >= 1),
    pagina_inicio INTEGER     NOT NULL CHECK (pagina_inicio >= 1),
    pagina_fim    INTEGER     NOT NULL,
    secao_caminho TEXT,                                  -- "3 Energia › 3.2 Tarifa"
    texto         TEXT        NOT NULL CHECK (btrim(texto) <> ''),
    tokens        INTEGER     NOT NULL CHECK (tokens >= 1),
    embedding     vector(768) NOT NULL,
    -- No PostgreSQL 18 a coluna gerada é virtual por padrão: STORED explícito, para o GIN indexar o valor gravado.
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('pt_sem_acento', fn_texto_de_busca(coalesce(secao_caminho, '') || ' ' || texto))) STORED,
    ativo         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_documento_trecho_paginas CHECK (pagina_fim >= pagina_inicio),
    CONSTRAINT uq_documento_trecho_ordem UNIQUE (documento_id, ordem)
);
CREATE INDEX ix_documento_trecho_embedding ON documento_trecho USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_documento_trecho_tsv ON documento_trecho USING gin (tsv);
CREATE INDEX ix_documento_trecho_ativo ON documento_trecho (evento_id, escopo, cota_tipo_id, parceria_id) WHERE ativo;

-- ---------------------------------------------------------------------
-- 6. RLS: o que o atlas_ro enxerga
-- ---------------------------------------------------------------------
-- A busca do Atlas roda como atlas_ro (SET LOCAL ROLE) depois de gravar o contexto da pessoa com set_config(..., true):
-- app.evento_id, app.cota_tipo_id e app.parceria_id. [SEGURANCA] O NULLIF é obrigatório: depois de um set_config(..., true) a conexão
-- guarda o parâmetro como texto vazio, e o cast direto de '' daria erro em vez de "sem contexto, não vê nada". Sem contexto (ou
-- com o contexto de uma transação anterior da mesma conexão), a comparação é com nulo e nenhuma linha passa.
-- O dono (portal) não fica sob FORCE: a ingestão e as telas da RBS continuam iguais. Quem fica sob a RLS é a busca, porque roda como
-- atlas_ro; assim nem um portal superusuário passa por cima da regra.
ALTER TABLE documento ENABLE ROW LEVEL SECURITY;
ALTER TABLE documento_trecho ENABLE ROW LEVEL SECURITY;

CREATE POLICY documento_leitura_do_atlas ON documento FOR SELECT TO atlas_ro
    USING (status = 'INDEXADO'
           AND evento_id = NULLIF(current_setting('app.evento_id', true), '')::bigint
           AND (escopo = 'GLOBAL'
                OR (escopo = 'COTA' AND cota_tipo_id = NULLIF(current_setting('app.cota_tipo_id', true), '')::smallint)
                OR (escopo = 'PARCERIA' AND parceria_id = NULLIF(current_setting('app.parceria_id', true), '')::bigint)));

CREATE POLICY documento_trecho_leitura_do_atlas ON documento_trecho FOR SELECT TO atlas_ro
    USING (ativo
           AND evento_id = NULLIF(current_setting('app.evento_id', true), '')::bigint
           AND (escopo = 'GLOBAL'
                OR (escopo = 'COTA' AND cota_tipo_id = NULLIF(current_setting('app.cota_tipo_id', true), '')::smallint)
                OR (escopo = 'PARCERIA' AND parceria_id = NULLIF(current_setting('app.parceria_id', true), '')::bigint)));

-- Só leitura, e só destas duas tabelas: o atlas_ro não lê nada do portal (parceria, entrega, dado informado...).
GRANT SELECT ON documento, documento_trecho TO atlas_ro;

-- ---------------------------------------------------------------------
-- 7. Gatilhos
-- ---------------------------------------------------------------------
-- [ATLAS] Os nomes das regras começam com rn_ e a mensagem é a frase que a API devolve (409), como nos gatilhos de V2.

-- Transições de status, só estas. Um documento novo nasce aguardando aprovação, ou com erro (texto que não pôde ser lido).
CREATE FUNCTION tg_documento_transicao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('AGUARDANDO_APROVACAO', 'ERRO') THEN
            RAISE EXCEPTION 'Um documento novo nasce aguardando a aprovação da RBS, ou com erro quando o texto não pôde ser lido.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_transicao';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT (   (OLD.status = 'AGUARDANDO_APROVACAO' AND NEW.status IN ('PROCESSANDO', 'RECUSADO'))
            OR (OLD.status = 'PROCESSANDO'          AND NEW.status IN ('INDEXADO', 'ERRO'))
            OR (OLD.status = 'ERRO'                 AND NEW.status IN ('PROCESSANDO', 'RECUSADO'))
            OR (OLD.status = 'INDEXADO'             AND NEW.status IN ('SUBSTITUIDO', 'REVOGADO'))
            OR (OLD.status = 'SUBSTITUIDO'          AND NEW.status = 'REVOGADO')) THEN
        RAISE EXCEPTION 'Este documento está % e não pode passar a %.',
            CASE OLD.status WHEN 'AGUARDANDO_APROVACAO' THEN 'aguardando aprovação' WHEN 'RECUSADO' THEN 'recusado'
                            WHEN 'PROCESSANDO' THEN 'sendo processado' WHEN 'INDEXADO' THEN 'indexado' WHEN 'ERRO' THEN 'com erro'
                            WHEN 'SUBSTITUIDO' THEN 'substituído' ELSE 'revogado' END,
            CASE NEW.status WHEN 'AGUARDANDO_APROVACAO' THEN 'aguardando aprovação' WHEN 'RECUSADO' THEN 'recusado'
                            WHEN 'PROCESSANDO' THEN 'em processamento' WHEN 'INDEXADO' THEN 'indexado' WHEN 'ERRO' THEN 'com erro'
                            WHEN 'SUBSTITUIDO' THEN 'substituído' ELSE 'revogado' END
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_transicao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_transicao BEFORE INSERT OR UPDATE OF status ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_transicao();

-- Aprovar (ou reindexar) é preencher aprovado_por e passar a PROCESSANDO: sem quem aprovou, não há processamento. Um documento que
-- nasceu com erro nunca foi aprovado e não se reindexa: corrige-se com um novo envio.
CREATE FUNCTION tg_documento_aprovacao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'PROCESSANDO' AND OLD.status IS DISTINCT FROM 'PROCESSANDO' AND NEW.aprovado_por IS NULL THEN
        IF OLD.status = 'ERRO' THEN
            RAISE EXCEPTION 'Este documento nunca foi aprovado e não pode ser reindexado. Corrija o arquivo e envie de novo.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_aprovacao';
        END IF;
        RAISE EXCEPTION 'Só a equipe da RBS aprova um documento, e é preciso dizer quem aprovou.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_aprovacao';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_aprovacao BEFORE UPDATE OF status ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_aprovacao();

-- Documento com suspeita de dado pessoal não entra em PROCESSANDO: nem por aprovação, nem por reindexação.
CREATE FUNCTION tg_documento_dados_pessoais() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'PROCESSANDO' AND OLD.status IS DISTINCT FROM 'PROCESSANDO' AND NEW.contem_dados_pessoais THEN
        RAISE EXCEPTION 'Este documento parece ter dados pessoais (CPF, RG ou uma lista de contatos) e não pode ser aprovado. Tire os dados pessoais e envie de novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_dados_pessoais';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_dados_pessoais BEFORE UPDATE OF status ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_dados_pessoais();

-- Coerência do documento com o arquivo e com a parceria. O arquivo é da categoria DOCUMENTO; nos documentos GLOBAL e COTA ele é
-- geral (sem parceria), e no PARCERIA é da mesma parceria (a chave composta só vale com parceria preenchida); a edição de um
-- documento PARCERIA é a da parceria.
CREATE FUNCTION tg_documento_coerente() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_categoria VARCHAR(30);
    v_parceria_do_arquivo BIGINT;
    v_evento_da_parceria BIGINT;
BEGIN
    SELECT categoria, parceria_id INTO v_categoria, v_parceria_do_arquivo FROM arquivo WHERE id = NEW.arquivo_id;
    IF v_categoria IS DISTINCT FROM 'DOCUMENTO' THEN
        RAISE EXCEPTION 'O arquivo de um documento precisa ser da categoria DOCUMENTO.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_coerente';
    END IF;
    IF v_parceria_do_arquivo IS DISTINCT FROM NEW.parceria_id THEN
        RAISE EXCEPTION 'O arquivo do documento tem de ser da mesma parceria do documento (e geral, sem parceria, nos documentos globais e de cota).'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_coerente';
    END IF;
    IF NEW.parceria_id IS NOT NULL THEN
        SELECT evento_id INTO v_evento_da_parceria FROM parceria WHERE id = NEW.parceria_id;
        IF v_evento_da_parceria IS DISTINCT FROM NEW.evento_id THEN
            RAISE EXCEPTION 'A edição do documento tem de ser a da parceria.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_coerente';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_coerente BEFORE INSERT OR UPDATE OF arquivo_id, escopo, evento_id, cota_tipo_id, parceria_id ON documento
    FOR EACH ROW EXECUTE FUNCTION tg_documento_coerente();

-- Revogar ou substituir desliga os trechos na hora, na mesma transação do UPDATE: a busca não os enxerga mais.
CREATE FUNCTION tg_documento_revogacao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE documento_trecho SET ativo = FALSE WHERE documento_id = NEW.id AND ativo;
    RETURN NULL;
END $$;
CREATE TRIGGER rn_documento_revogacao AFTER UPDATE OF status ON documento
    FOR EACH ROW WHEN (NEW.status IN ('REVOGADO', 'SUBSTITUIDO') AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION tg_documento_revogacao();

-- O trecho é coerente com o documento: o escopo, a edição, a cota, a parceria e a versão vêm DO DOCUMENTO (copiados na criação) e
-- não mudam; o documento do trecho também não.
CREATE FUNCTION tg_documento_trecho_coerente() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d documento%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.documento_id IS DISTINCT FROM OLD.documento_id OR NEW.escopo IS DISTINCT FROM OLD.escopo
           OR NEW.evento_id IS DISTINCT FROM OLD.evento_id OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id
           OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id OR NEW.versao IS DISTINCT FROM OLD.versao THEN
            RAISE EXCEPTION 'O trecho pertence ao documento, à edição, ao escopo e à versão em que foi criado e não muda.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_trecho_coerente';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO d FROM documento WHERE id = NEW.documento_id;
    NEW.escopo := d.escopo;
    NEW.evento_id := d.evento_id;
    NEW.cota_tipo_id := d.cota_tipo_id;
    NEW.parceria_id := d.parceria_id;
    NEW.versao := d.versao;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_trecho_coerente BEFORE INSERT OR UPDATE ON documento_trecho
    FOR EACH ROW EXECUTE FUNCTION tg_documento_trecho_coerente();

-- Só trecho de documento INDEXADO fica ativo.
CREATE FUNCTION tg_documento_trecho_ativo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ativo AND NOT EXISTS (SELECT 1 FROM documento WHERE id = NEW.documento_id AND status = 'INDEXADO') THEN
        RAISE EXCEPTION 'Só o trecho de um documento indexado pode ficar ativo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_trecho_ativo';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_documento_trecho_ativo BEFORE INSERT OR UPDATE OF ativo ON documento_trecho
    FOR EACH ROW WHEN (NEW.ativo) EXECUTE FUNCTION tg_documento_trecho_ativo();

-- ---------------------------------------------------------------------
-- fn_publicar_documento: a versão nova entra no ar e a anterior sai, na MESMA transação
-- ---------------------------------------------------------------------
-- Trava o grupo do documento, passa as versões INDEXADAS mais antigas do grupo a SUBSTITUIDO (o gatilho desliga os trechos delas),
-- e a nova a INDEXADO com os trechos ativos. Quem chama já gravou os trechos (inativos) e o modelo e as dimensões do documento.
-- Se uma versão mais nova do grupo já está indexada (a mais velha demorou mais), a que chega fica SUBSTITUIDO e inativa.
CREATE FUNCTION fn_publicar_documento(p_documento_id BIGINT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    d documento%ROWTYPE;
BEGIN
    SELECT * INTO d FROM documento WHERE id = p_documento_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'O documento % não existe.', p_documento_id;
    END IF;
    PERFORM fn_trava_grupo_documento(d.evento_id, d.escopo, d.cota_tipo_id, d.parceria_id, d.chave);
    SELECT * INTO d FROM documento WHERE id = p_documento_id FOR UPDATE;
    IF d.status <> 'PROCESSANDO' THEN
        RAISE EXCEPTION 'O documento % não está em processamento e não pode ser publicado.', p_documento_id;
    END IF;

    UPDATE documento SET status = 'SUBSTITUIDO'
     WHERE evento_id = d.evento_id AND escopo = d.escopo AND cota_tipo_id IS NOT DISTINCT FROM d.cota_tipo_id
       AND parceria_id IS NOT DISTINCT FROM d.parceria_id AND chave = d.chave AND status = 'INDEXADO' AND versao < d.versao;

    UPDATE documento SET status = 'INDEXADO', trechos = (SELECT count(*) FROM documento_trecho WHERE documento_id = d.id)
     WHERE id = d.id;

    IF EXISTS (SELECT 1 FROM documento
                WHERE evento_id = d.evento_id AND escopo = d.escopo AND cota_tipo_id IS NOT DISTINCT FROM d.cota_tipo_id
                  AND parceria_id IS NOT DISTINCT FROM d.parceria_id AND chave = d.chave AND status = 'INDEXADO' AND versao > d.versao) THEN
        UPDATE documento SET status = 'SUBSTITUIDO' WHERE id = d.id;
    ELSE
        UPDATE documento_trecho SET ativo = TRUE WHERE documento_id = d.id;
    END IF;
END $$;

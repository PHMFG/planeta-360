-- O documento que a importação da RBS cria no Atlas não é da marca. Antes, a importação de alcance EDICAO virava um documento
-- GLOBAL INDEXADO, e a RLS do atlas_ro (e a regra da rota 56) o entregava a qualquer parceria da edição: a marca achava o arquivo
-- no buscar_documentos e o abria pelo visualizador. A coluna marca o documento que só a RBS lê; a importação da própria marca
-- (rota 117) continua com so_rbs falso e escopo PARCERIA, visível só àquela parceria.
ALTER TABLE documento ADD COLUMN so_rbs BOOLEAN NOT NULL DEFAULT false;

-- Os documentos que já existem e vieram de uma importação da RBS passam a ser só da RBS. O que veio da rota 117 (origem MARCA)
-- fica como está: escopo PARCERIA, visível à parceria dele.
UPDATE documento d SET so_rbs = true
FROM importacao i
WHERE i.documento_id = d.id AND i.origem = 'RBS';

-- A política da marca (o papel atlas_ro é o da busca do Atlas): documento de importação da RBS não passa. O trecho também não,
-- mesmo que alguém consulte a tabela de trechos sem a junta com documento: o EXISTS repete a condição.
DROP POLICY documento_leitura_do_atlas ON documento;
CREATE POLICY documento_leitura_do_atlas ON documento FOR SELECT TO atlas_ro
    USING (NOT so_rbs
           AND status = 'INDEXADO'
           AND evento_id = NULLIF(current_setting('app.evento_id', true), '')::bigint
           AND (escopo = 'GLOBAL'
                OR (escopo = 'COTA' AND cota_tipo_id = NULLIF(current_setting('app.cota_tipo_id', true), '')::smallint)
                OR (escopo = 'PARCERIA' AND parceria_id = NULLIF(current_setting('app.parceria_id', true), '')::bigint)));

DROP POLICY documento_trecho_leitura_do_atlas ON documento_trecho;
CREATE POLICY documento_trecho_leitura_do_atlas ON documento_trecho FOR SELECT TO atlas_ro
    USING (ativo
           AND EXISTS (SELECT 1 FROM documento d WHERE d.id = documento_trecho.documento_id AND NOT d.so_rbs)
           AND evento_id = NULLIF(current_setting('app.evento_id', true), '')::bigint
           AND (escopo = 'GLOBAL'
                OR (escopo = 'COTA' AND cota_tipo_id = NULLIF(current_setting('app.cota_tipo_id', true), '')::smallint)
                OR (escopo = 'PARCERIA' AND parceria_id = NULLIF(current_setting('app.parceria_id', true), '')::bigint)));

-- A coluna é do momento em que o documento nasce (a importação de origem): não muda depois, como o escopo e a chave.
CREATE OR REPLACE FUNCTION tg_documento_congelado() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.escopo IS DISTINCT FROM OLD.escopo OR NEW.evento_id IS DISTINCT FROM OLD.evento_id
       OR NEW.cota_tipo_id IS DISTINCT FROM OLD.cota_tipo_id OR NEW.parceria_id IS DISTINCT FROM OLD.parceria_id
       OR NEW.chave IS DISTINCT FROM OLD.chave OR NEW.versao IS DISTINCT FROM OLD.versao
       OR NEW.so_rbs IS DISTINCT FROM OLD.so_rbs
       OR (NEW.contem_dados_pessoais IS DISTINCT FROM OLD.contem_dados_pessoais
           AND NOT (OLD.status = 'AGUARDANDO_APROVACAO' AND NEW.status = 'AGUARDANDO_APROVACAO'
                    AND NEW.paginas_ignoradas IS DISTINCT FROM OLD.paginas_ignoradas)) THEN
        RAISE EXCEPTION 'O documento fica na edição, no escopo, na cota, na parceria, na chave, na versão e no destino em que foi criado, e a suspeita de dado pessoal só muda junto das páginas fora do Atlas. Para mudar isso, envie o documento de novo.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_documento_congelado';
    END IF;
    RETURN NEW;
END $$;

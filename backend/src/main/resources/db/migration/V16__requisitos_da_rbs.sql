-- V16: o banco dos requisitos da RBS (fase F1 do plano final).
-- Só acrescenta: colunas novas, regras novas, uma tabela e dois gatilhos. Nenhuma V antiga é editada.
-- Pré-condições para subir num banco que já tem a amostra e as migrações V1 a V15 (conferidas na pilha de teste e no portal_conferencia):
--   - no máximo UMA pesquisa ATIVACAO por edição;
--   - nenhum plano comercial ou formulário de ingressos avulso repetindo (parceria, categoria, versão);
--   - nenhum e-mail de signatário malformado e nenhum contrato com arquivo de outra categoria ou de outra aba.

-- ---------------------------------------------------------------------
-- 1. O papel da entrega (R5.1 e R5.3: documento de ingressos, MIV e logo)
-- ---------------------------------------------------------------------
-- O "espaço" de um envio na aba (o documento de ingressos no Perfil, o MIV e a logo em Aplicações) é uma entrega com papel.
-- Papel nulo é a entrega comum. MIV e logo têm UMA entrega por parceria: o índice único parcial garante isso e deixa o primeiro
-- envio ser um INSERT ... ON CONFLICT DO NOTHING seguido de um SELECT ... FOR UPDATE. O documento de ingressos fica de fora do índice
-- de propósito: cada envio da marca é uma solicitação nova ("Solicitação de ingressos N"), então a parceria pode ter várias.
ALTER TABLE entrega
    ADD COLUMN papel VARCHAR(30)
        CONSTRAINT ck_entrega_papel CHECK (papel IS NULL OR papel IN ('DOCUMENTO_INGRESSOS', 'MIV', 'LOGO'));
CREATE UNIQUE INDEX uq_entrega_papel ON entrega (parceria_id, papel) WHERE papel IN ('MIV', 'LOGO');

-- ---------------------------------------------------------------------
-- 2. Categoria do arquivo: o formulário de ingressos
-- ---------------------------------------------------------------------
-- O formulário em branco que a RBS publica para a marca preencher (o documento preenchido continua DOCUMENTO_INGRESSOS).
-- É a lista da V11 mais FORMULARIO_INGRESSOS; o resto fica igual.
ALTER TABLE arquivo DROP CONSTRAINT ck_arquivo_categoria;
ALTER TABLE arquivo ADD CONSTRAINT ck_arquivo_categoria CHECK (categoria IN (
    'PLANO_COMERCIAL','MATERIAL_MARCA','MATERIAL_MIDIA','RELATORIO_VEICULACAO','COMPROVACAO','PESQUISA','CONTRATO',
    'DOCUMENTO_INGRESSOS','RELATORIO_PARCERIA','OUTRO','DOCUMENTO','FORMULARIO_INGRESSOS'));

-- ---------------------------------------------------------------------
-- 3. Versão do arquivo avulso
-- ---------------------------------------------------------------------
-- O arquivo ligado a uma entrega já tem versão única (uq_arquivo_versao). O avulso (sem entrega) não tinha: dois envios juntos
-- pegavam a mesma versão. Agora a versão é única por (parceria, categoria), mas só nas categorias que de fato têm versões: o plano
-- comercial (D6) e o formulário de ingressos. As outras categorias avulsas NÃO versionam (fotos de comprovação, relatórios de carga,
-- planilhas da agência, documentos do Atlas ficam todas na versão 1, e a amostra tem 17 fotos assim numa só parceria): um índice sobre
-- todas quebraria a V16 num banco que já tem a amostra. Parceria nula (arquivo geral da RBS) não entra: o NULL não se repete no índice.
CREATE UNIQUE INDEX uq_arquivo_versao_avulso ON arquivo (parceria_id, categoria, versao)
    WHERE entrega_id IS NULL AND categoria IN ('PLANO_COMERCIAL', 'FORMULARIO_INGRESSOS');

-- ---------------------------------------------------------------------
-- 4. Credenciamento e prazo das cortesias, por edição (R5.4 e R5.5)
-- ---------------------------------------------------------------------
-- O endereço do Open Staff é um link externo, só https. O CHECK barra, no endereço, o "usuario@" antes do host (que esconderia o
-- destino de verdade), espaço, caractere de controle e os caracteres bidi que invertem a leitura. O "@" depois do host vale (o
-- TikTok usa /@perfil). O tamanho vem do VARCHAR(500).
ALTER TABLE evento
    ADD COLUMN credenciamento_url VARCHAR(500)
        CONSTRAINT ck_evento_credenciamento_url CHECK (credenciamento_url IS NULL OR credenciamento_url ~
            '^https://[^[:space:][:cntrl:]‎‏‪-‮⁦-⁩@/?#]+([/?#][^[:space:][:cntrl:]‎‏‪-‮⁦-⁩]*)?$'),
    ADD COLUMN credenciamento_inicio DATE,
    ADD COLUMN credenciamento_fim DATE,
    ADD COLUMN cortesias_prazo DATE,
    ADD CONSTRAINT ck_evento_credenciamento_periodo
        CHECK (credenciamento_fim IS NULL OR credenciamento_inicio IS NULL OR credenciamento_fim >= credenciamento_inicio);

-- ---------------------------------------------------------------------
-- 5. Contrato: quando o signatário foi informado e o e-mail dele (R5.7)
-- ---------------------------------------------------------------------
-- O CHECK é o mesmo do e-mail do usuário (V1). Os contratos da amostra já têm e-mail válido ou nulo.
ALTER TABLE contrato
    ADD COLUMN informado_em TIMESTAMPTZ,
    ADD CONSTRAINT ck_contrato_signatario_email
        CHECK (signatario_email IS NULL OR signatario_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$');

-- O arquivo do contrato é da categoria CONTRATO e está na aba Contrato. A chave composta da V1 só prova que o arquivo é da mesma
-- parceria; o download (rota 18) confere a aba do arquivo, então um contrato apontando para outro arquivo ficaria sem download.
CREATE FUNCTION tg_contrato_arquivo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_categoria VARCHAR(30); v_aba VARCHAR(20);
BEGIN
    IF NEW.arquivo_id IS NULL THEN RETURN NEW; END IF;
    SELECT a.categoria, ab.codigo INTO v_categoria, v_aba FROM arquivo a LEFT JOIN aba ab ON ab.id = a.aba_id WHERE a.id = NEW.arquivo_id;
    IF FOUND AND (v_categoria <> 'CONTRATO' OR v_aba IS DISTINCT FROM 'CONTRATO') THEN
        RAISE EXCEPTION 'O arquivo do contrato precisa ser da categoria CONTRATO e estar na aba Contrato.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_contrato_arquivo';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_contrato_arquivo BEFORE INSERT OR UPDATE OF arquivo_id ON contrato
    FOR EACH ROW EXECUTE FUNCTION tg_contrato_arquivo();

-- ---------------------------------------------------------------------
-- 6. Pesquisa: uma da ativação por edição (R5.6)
-- ---------------------------------------------------------------------
-- O código já assumia isso (pesquisaDaAtivacao pega a de menor id). Agora o banco garante. A pesquisa GERAL pode ter várias.
CREATE UNIQUE INDEX uq_pesquisa_ativacao ON pesquisa (evento_id) WHERE escopo = 'ATIVACAO';

-- ---------------------------------------------------------------------
-- 7. Texto ou link enviado numa entrega (R5.3: mídia e conteúdo)
-- ---------------------------------------------------------------------
-- Além do arquivo, a marca envia texto e a RBS publica link de post. Cada envio é uma linha e nunca muda: "corrigir" é enviar de
-- novo, e o gatilho rn_entrega_conteudo_imutavel recusa UPDATE e DELETE (como o histórico). Pela mesma razão a chave para a entrega
-- NÃO tem ON DELETE CASCADE (a cascata tentaria apagar linhas imutáveis): uma entrega com conteúdo não pode ser apagada.
-- A chave composta (entrega, parceria) mantém a linha na parceria da entrega.
-- O texto é dado, nunca instrução: não vai ao Atlas. Não há CHECK de caractere nulo: o PostgreSQL já recusa o NUL em texto (SQLSTATE
-- 22021, que a API trata antes com 422); o CHECK abaixo barra os demais caracteres de controle, menos a quebra de linha e o tab.
-- A URL aceita http e https, sem "usuario@" antes do host, sem espaço, controle nem bidi (veja o CHECK do credenciamento).
CREATE TABLE entrega_conteudo (
    id          BIGSERIAL PRIMARY KEY,
    entrega_id  BIGINT NOT NULL,
    parceria_id BIGINT NOT NULL,
    tipo        VARCHAR(5) NOT NULL CONSTRAINT ck_conteudo_tipo CHECK (tipo IN ('TEXTO', 'LINK')),
    titulo      VARCHAR(160),
    texto       TEXT,
    url         VARCHAR(500),
    observacao  VARCHAR(500),
    enviado_por BIGINT NOT NULL REFERENCES usuario(id),
    enviado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_conteudo_entrega FOREIGN KEY (entrega_id, parceria_id) REFERENCES entrega (id, parceria_id),
    CONSTRAINT ck_conteudo_forma CHECK (
           (tipo = 'TEXTO' AND texto IS NOT NULL AND length(btrim(texto)) BETWEEN 1 AND 5000 AND url IS NULL)
        OR (tipo = 'LINK'  AND url IS NOT NULL AND texto IS NULL)),
    CONSTRAINT ck_conteudo_url CHECK (url IS NULL OR url ~
        '^https?://[^[:space:][:cntrl:]‎‏‪-‮⁦-⁩@/?#]+([/?#][^[:space:][:cntrl:]‎‏‪-‮⁦-⁩]*)?$'),
    CONSTRAINT ck_conteudo_sem_controle CHECK (
        coalesce(texto, '') || coalesce(titulo, '') || coalesce(observacao, '') !~ '[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]')
);
CREATE INDEX ix_conteudo_entrega ON entrega_conteudo (entrega_id, enviado_em DESC);

CREATE FUNCTION tg_entrega_conteudo_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'O texto ou link enviado não pode ser alterado nem apagado. Para corrigir, envie de novo.'
        USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_entrega_conteudo_imutavel';
END $$;
CREATE TRIGGER rn_entrega_conteudo_imutavel BEFORE UPDATE OR DELETE ON entrega_conteudo
    FOR EACH ROW EXECUTE FUNCTION tg_entrega_conteudo_imutavel();

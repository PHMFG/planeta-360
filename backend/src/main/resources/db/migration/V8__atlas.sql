-- Segunda parte do MVP do Atlas: as tabelas da conversa, do relatório que o Atlas monta e do cartão de perguntas.
-- A V1 a V7 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
--   1. atlas_conversa e atlas_mensagem: a conversa de cada pessoa da marca, gravada no formato de chat completions (papel
--      user, assistant ou tool; pedidos de ferramenta em tool_calls), para o plano B (sem modelo de linguagem) e o LLM
--      continuarem a mesma conversa.
--   2. atlas_relatorio: o relatório de trabalho da parceria (especificação em JSON, valores resolvidos ao finalizar).
--   3. atlas_solicitacao: o cartão de perguntas (as chaves do catálogo que o Atlas pediu e a resposta).
--   4. atlas_calculo, atlas_tool_call e atlas_decisao: os cálculos das fórmulas fixas, a trilha das ferramentas e o JEV em sombra.
--   5. rn_atlas_relatorio_congelado: relatório pronto ou descartado não muda mais.
-- Não mexe em nenhuma tabela, visão ou função que já existia, e não altera a amostra.

-- ---------------------------------------------------------------------
-- 1. CONVERSA
-- ---------------------------------------------------------------------
-- [ISOLAMENTO] A conversa é de uma pessoa (usuario_id) numa parceria. UNIQUE (id, parceria_id) existe para as chaves compostas
-- abaixo: um relatório só se liga a uma conversa da própria parceria.
-- turno_desde marca o turno em andamento (nulo = livre): a API só aceita uma mensagem por vez em cada conversa.
CREATE TABLE atlas_conversa (
    id          BIGSERIAL    PRIMARY KEY,
    parceria_id BIGINT       NOT NULL REFERENCES parceria(id),
    usuario_id  BIGINT       NOT NULL REFERENCES usuario(id),
    criada_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    turno_desde TIMESTAMPTZ,
    CONSTRAINT uq_atlas_conversa_parceria UNIQUE (id, parceria_id)
);
CREATE INDEX ix_atlas_conversa_usuario ON atlas_conversa (parceria_id, usuario_id, criada_em DESC);

-- [ATLAS] Uma linha por mensagem, na ordem em que aconteceu, no formato que o modelo usa (chat completions): user, assistant
-- (com pedidos de ferramenta em tool_calls) e tool (a resposta, ligada ao pedido por tool_call_id). Quem lê o histórico monta
-- os itens da tela a partir daqui; nada fica só na tela.
CREATE TABLE atlas_mensagem (
    id                BIGSERIAL    PRIMARY KEY,
    conversa_id       BIGINT       NOT NULL REFERENCES atlas_conversa(id),
    ordem             INTEGER      NOT NULL,
    papel             VARCHAR(10)  NOT NULL CHECK (papel IN ('user','assistant','tool')),
    conteudo          TEXT,
    tool_calls        JSONB,                 -- pedidos de ferramenta do assistente
    tool_call_id      VARCHAR(80),           -- resposta de ferramenta: o pedido que ela responde
    reasoning_content TEXT,                  -- gravado quando o modelo mandar; devolvido na retomada
    criada_em         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_atlas_mensagem_ordem UNIQUE (conversa_id, ordem)
);

-- ---------------------------------------------------------------------
-- 2. RELATÓRIO DO ATLAS
-- ---------------------------------------------------------------------
-- [ISOLAMENTO] O relatório é da parceria (todos os usuários dela o veem) e nasce numa conversa dessa mesma parceria (chave
-- composta). especificacao é o modelo-base com o texto de cada bloco redigível; snapshot são os valores resolvidos, gravados
-- na mesma operação que muda o status para PRONTO. versao_especificacao é a trava otimista de quem edita a especificação.
CREATE TABLE atlas_relatorio (
    id                   BIGSERIAL    PRIMARY KEY,
    parceria_id          BIGINT       NOT NULL REFERENCES parceria(id),
    conversa_id          BIGINT       NOT NULL,
    modelo               VARCHAR(40)  NOT NULL,
    status               VARCHAR(20)  NOT NULL
        CHECK (status IN ('MONTANDO','AGUARDANDO_RESPOSTA','PRONTO','DESCARTADO')),
    especificacao        JSONB        NOT NULL,
    versao_especificacao INTEGER      NOT NULL DEFAULT 1,
    snapshot             JSONB,
    criado_por           BIGINT       NOT NULL REFERENCES usuario(id),
    criado_em            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finalizado_em        TIMESTAMPTZ,
    CONSTRAINT uq_atlas_relatorio_parceria UNIQUE (id, parceria_id),
    CONSTRAINT fk_atlas_relatorio_conversa FOREIGN KEY (conversa_id, parceria_id) REFERENCES atlas_conversa(id, parceria_id)
);
CREATE INDEX ix_atlas_relatorio_parceria ON atlas_relatorio (parceria_id, criado_em DESC);
CREATE INDEX ix_atlas_relatorio_conversa ON atlas_relatorio (conversa_id);
-- Um relatório em montagem por conversa: o botão continua o mesmo relatório em vez de abrir outro.
CREATE UNIQUE INDEX uq_atlas_relatorio_ativo ON atlas_relatorio (conversa_id) WHERE status IN ('MONTANDO','AGUARDANDO_RESPOSTA');

-- [ATLAS] O cartão de perguntas: as chaves do catálogo que o Atlas pediu (campos) e o que a pessoa fez com o cartão. tool_call_id
-- liga o cartão ao pedido de ferramenta que o abriu, na conversa. [ISOLAMENTO] Chave composta com a parceria do relatório.
CREATE TABLE atlas_solicitacao (
    id            BIGSERIAL     PRIMARY KEY,
    relatorio_id  BIGINT        NOT NULL,
    parceria_id   BIGINT        NOT NULL REFERENCES parceria(id),
    tool_call_id  VARCHAR(80)   NOT NULL,
    mensagem      VARCHAR(400)  NOT NULL,
    campos        JSONB         NOT NULL,
    status        VARCHAR(12)   NOT NULL
        CHECK (status IN ('PENDENTE','RESPONDIDA','RECUSADA','CANCELADA')),
    criada_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    respondida_em TIMESTAMPTZ,
    CONSTRAINT fk_atlas_solicitacao_relatorio FOREIGN KEY (relatorio_id, parceria_id) REFERENCES atlas_relatorio(id, parceria_id)
);
CREATE INDEX ix_atlas_solicitacao_relatorio ON atlas_solicitacao (relatorio_id);
CREATE INDEX ix_atlas_solicitacao_tool_call ON atlas_solicitacao (tool_call_id);
-- Um cartão aberto por relatório: o segundo pedido enquanto o primeiro espera resposta é recusado.
CREATE UNIQUE INDEX uq_atlas_solicitacao_pendente ON atlas_solicitacao (relatorio_id) WHERE status = 'PENDENTE';

-- [RELATORIO] As contas das fórmulas fixas do modelo (pct de cadastros sobre visitantes...), com as variáveis e os valores que
-- entraram. Um nome por relatório. resultado nulo = indisponível (faltou dado ou o denominador era zero).
CREATE TABLE atlas_calculo (
    id           BIGSERIAL      PRIMARY KEY,
    relatorio_id BIGINT         NOT NULL REFERENCES atlas_relatorio(id),
    nome         VARCHAR(60)    NOT NULL,
    script       VARCHAR(2000)  NOT NULL,
    variaveis    JSONB          NOT NULL,
    resultado    NUMERIC(20,6),
    unidade      VARCHAR(20),
    criado_em    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_atlas_calculo_nome UNIQUE (relatorio_id, nome)
);

-- Trilha das ferramentas que rodaram: o pedido (mensagem do assistente), os argumentos, o resultado ou o erro e quanto levou.
CREATE TABLE atlas_tool_call (
    id          BIGSERIAL    PRIMARY KEY,
    mensagem_id BIGINT       NOT NULL REFERENCES atlas_mensagem(id),
    ferramenta  VARCHAR(60)  NOT NULL,
    argumentos  JSONB        NOT NULL,
    resultado   JSONB,
    erro        TEXT,
    duracao_ms  INTEGER
);
CREATE INDEX ix_atlas_tool_call_mensagem ON atlas_tool_call (mensagem_id);

-- JEV em sombra (P1): o que ele decidiria, sem agir, ao lado do que o modelo fez. A tabela já entra aqui; nada a escreve ainda.
CREATE TABLE atlas_decisao (
    id             BIGSERIAL    PRIMARY KEY,
    mensagem_id    BIGINT       NOT NULL REFERENCES atlas_mensagem(id),
    pergunta       VARCHAR(40)  NOT NULL,
    escolha        VARCHAR(40),
    confianca      NUMERIC(5,4),
    probabilidades JSONB,
    modelo         VARCHAR(40),
    decisao_llm    VARCHAR(60),
    latencia_ms    INTEGER,
    erro           TEXT
);
CREATE INDEX ix_atlas_decisao_mensagem ON atlas_decisao (mensagem_id);

-- ---------------------------------------------------------------------
-- 3. RELATÓRIO PRONTO NÃO MUDA
-- ---------------------------------------------------------------------
-- [HISTORICO] PRONTO e DESCARTADO são finais. A mesma operação que grava o snapshot muda o status para PRONTO; depois disso a
-- especificação, o snapshot e o status não mudam mais, e o relatório pronto não se apaga. O relatório em montagem muda à vontade
-- (inclusive para PRONTO ou DESCARTADO, que é a única saída dele).
CREATE FUNCTION tg_atlas_relatorio_congelado() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'PRONTO' THEN
            RAISE EXCEPTION 'Um relatório do Atlas pronto não pode ser apagado.'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_atlas_relatorio_congelado';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status IN ('PRONTO', 'DESCARTADO')
       AND (NEW.especificacao IS DISTINCT FROM OLD.especificacao
            OR NEW.snapshot IS DISTINCT FROM OLD.snapshot
            OR NEW.status IS DISTINCT FROM OLD.status) THEN
        RAISE EXCEPTION 'O relatório do Atlas % não muda mais.', lower(OLD.status)
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_atlas_relatorio_congelado';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_atlas_relatorio_congelado BEFORE UPDATE OR DELETE ON atlas_relatorio
    FOR EACH ROW EXECUTE FUNCTION tg_atlas_relatorio_congelado();

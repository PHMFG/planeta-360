-- Terceira parte do Atlas: o traço de cada turno, para o diagnóstico (marco M2 do plano do Atlas completo).
-- A V1 a V9 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui. Só acrescenta uma tabela: nenhuma tabela, visão,
-- função ou gatilho que já existia muda, e a amostra fica como está (sem traço nenhum).
--
-- [ATLAS] Cada turno (uma mensagem da pessoa, ou a resposta do cartão, até a resposta do Atlas) deixa uma linha por etapa: o que o
-- servidor fez, em que ordem, quanto levou e o resultado. É o que a RBS (e, se a propriedade estiver ligada, a própria marca) lê para
-- saber POR QUE uma resposta saiu como saiu. Só se escreve com atlas.diagnostico.ligado=true.
--
-- [SEGURANCA] A coluna dados guarda o detalhe de cada etapa JÁ SANITIZADO pela API: nunca o raciocínio cru do modelo
-- (reasoning_content), nunca uma chave, nunca o prompt de sistema nem as mensagens do histórico (a etapa LLM_IDA guarda modelo,
-- tokens, motivo do fim, latência e o número de mensagens), e todo texto cortado em 20.000 caracteres. O banco não confere isso: é
-- regra do RegistroDoTraco, coberta por teste.

-- [ISOLAMENTO] Chave composta com a conversa: o traço é da conversa e da parceria dela, e só delas.
-- turno agrupa as etapas de um turno (todas com o mesmo UUID). mensagem_id é a mensagem do assistente que a etapa produziu ou pediu
-- (nulo nas etapas que não têm uma, como a dos limites). ordem é a posição da etapa no turno, de 1 em diante.
-- etapa tem a lista completa desde já, também as etapas dos marcos seguintes (busca, JEV, disjuntor), para uma migração a menos.
-- resultado: OK, RECUSADO (uma regra disse não), ERRO, SOMBRA (o JEV decidiu sem agir) ou PULADO (não rodou ou ainda não terminou).
-- Não há gatilho de imutabilidade de propósito: o resumo do raciocínio pode terminar depois do fim do turno, e aí a linha dele é
-- atualizada (uma transação curta, sem nenhum provedor no meio).
CREATE TABLE atlas_traco (
    id          BIGSERIAL    PRIMARY KEY,
    parceria_id BIGINT       NOT NULL,
    conversa_id BIGINT       NOT NULL,
    turno       UUID         NOT NULL,
    mensagem_id BIGINT       REFERENCES atlas_mensagem(id),
    ordem       INTEGER      NOT NULL,
    etapa       VARCHAR(20)  NOT NULL,
    resultado   VARCHAR(10)  NOT NULL,
    iniciada_em TIMESTAMPTZ  NOT NULL,
    duracao_ms  INTEGER      NOT NULL,
    dados       JSONB        NOT NULL DEFAULT '{}',
    CONSTRAINT fk_atlas_traco_conversa FOREIGN KEY (conversa_id, parceria_id) REFERENCES atlas_conversa(id, parceria_id),
    CONSTRAINT ck_atlas_traco_etapa CHECK (etapa IN ('G1_LIMITES','J1_INTENCAO','J1_CARTAO','LLM_IDA','FERRAMENTA','BUSCA',
                                                      'R3_FONTES','CHECAGEM','R5_RESPOSTA','V2_COERENCIA','LACUNAS','RESUMO',
                                                      'SAIDA','DISJUNTOR','ERRO')),
    CONSTRAINT ck_atlas_traco_resultado CHECK (resultado IN ('OK','RECUSADO','ERRO','SOMBRA','PULADO')),
    CONSTRAINT ck_atlas_traco_ordem CHECK (ordem >= 1),
    CONSTRAINT ck_atlas_traco_duracao CHECK (duracao_ms >= 0),
    CONSTRAINT ck_atlas_traco_dados CHECK (jsonb_typeof(dados) = 'object'),
    CONSTRAINT uq_atlas_traco_ordem UNIQUE (turno, ordem)
);
CREATE INDEX ix_atlas_traco_conversa ON atlas_traco (conversa_id, iniciada_em);
CREATE INDEX ix_atlas_traco_parceria ON atlas_traco (parceria_id, iniciada_em DESC);
CREATE INDEX ix_atlas_traco_mensagem ON atlas_traco (mensagem_id);
CREATE INDEX ix_atlas_traco_etapa ON atlas_traco (etapa, iniciada_em DESC);

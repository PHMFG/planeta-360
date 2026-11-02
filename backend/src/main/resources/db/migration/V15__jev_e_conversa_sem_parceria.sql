-- O JEV nos pontos de decisão do Atlas e a conversa sem parceria (marco M7 do plano do Atlas completo, com a reserva do M8).
-- A V1 a V14 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui. Só acrescenta colunas, relaxa duas e acrescenta
-- regras; nenhuma tabela, visão ou função nova, e a amostra fica como está.
--
--   1. atlas_decisao (a tabela do JEV, que a V8 criou e ninguém escrevia): o ponto de decisão, o modo, o limiar, o que o servidor fez
--      (ou teria feito, em sombra), o alvo da pergunta e o turno.
--   2. atlas_mensagem: fora_do_modelo (a mensagem existe na conversa mas não vai ao modelo, nem conta no teto diário nem no relógio
--      de 7 dias) e sem_modelo (o servidor escreveu a resposta, sem gastar o modelo: continua no histórico que vai ao modelo).
--   3. A conversa sem parceria, para o Atlas da RBS (M8): atlas_conversa.parceria_id e atlas_traco.parceria_id deixam de ser
--      obrigatórios, atlas_conversa ganha evento_id, e uma regra exige a parceria OU o evento. Nenhum código usa isto ainda.

-- ---------------------------------------------------------------------
-- 1. atlas_decisao
-- ---------------------------------------------------------------------
-- [ATLAS] Uma linha por pergunta que o JEV respondeu (ou deixou de responder, com o erro). ponto é onde ele foi consultado; modo é o
-- que valia (SOMBRA só registra, ATIVO age; DESLIGADO não grava nada); limiar é o corte daquele ponto; acao é o que o servidor fez
-- ("fez X" em ATIVO, "teria feito X" em SOMBRA, em palavras fixas do servidor); alvo diz sobre o que foi a pergunta (o trecho, a frase,
-- o campo). mensagem_id é a mensagem da pessoa do turno e pergunta é o nome da pergunta, como na V8. Nada do texto da conversa vai
-- para cá: nem o estado enviado ao JEV, nem o raciocínio do modelo, nem chave.
ALTER TABLE atlas_decisao
    ADD COLUMN ponto  VARCHAR(20),
    ADD COLUMN modo   VARCHAR(10),
    ADD COLUMN limiar NUMERIC(5,4),
    ADD COLUMN acao   VARCHAR(80),
    ADD COLUMN alvo   VARCHAR(200),
    ADD COLUMN turno  UUID;
ALTER TABLE atlas_decisao
    ADD CONSTRAINT ck_atlas_decisao_ponto CHECK (ponto IS NULL OR ponto IN ('intencao','cartao','fontes','resposta','coerencia','lacunas')),
    ADD CONSTRAINT ck_atlas_decisao_modo CHECK (modo IS NULL OR modo IN ('SOMBRA','ATIVO')),
    ADD CONSTRAINT ck_atlas_decisao_limiar CHECK (limiar IS NULL OR (limiar >= 0 AND limiar <= 1));
CREATE INDEX ix_atlas_decisao_ponto ON atlas_decisao (ponto, modo);
CREATE INDEX ix_atlas_decisao_turno ON atlas_decisao (turno) WHERE turno IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. atlas_mensagem
-- ---------------------------------------------------------------------
-- [ATLAS] fora_do_modelo: a resposta fixa de um cartão aberto ("Use o cartão acima...") e a mensagem da pessoa que a provocou. Ficam na
-- conversa e na tela, mas o histórico que vai ao modelo as pula (a chamada de ferramenta do cartão ainda espera a resposta, e uma
-- mensagem no meio dela quebraria o formato), e elas não contam no teto diário nem no relógio de 7 dias do relatório parado.
-- sem_modelo: o servidor escreveu a mensagem do assistente (o botão, a recusa de fora de escopo, a resposta direta do JEV): não gasta o
-- modelo e não conta no teto, mas continua no histórico que vai ao modelo. Até aqui o teto reconhecia o botão pelo prefixo botao_ do id
-- da chamada, o que a recusa (sem chamada) não tem; as mensagens do botão que já existem ficam marcadas.
ALTER TABLE atlas_mensagem
    ADD COLUMN fora_do_modelo BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sem_modelo     BOOLEAN NOT NULL DEFAULT FALSE;
-- Fora do modelo só vale para o que a pessoa e o assistente dizem: uma mensagem de ferramenta pulada deixaria o pedido dela sem resposta.
-- Sem modelo só vale para o assistente.
ALTER TABLE atlas_mensagem
    ADD CONSTRAINT ck_atlas_mensagem_fora_do_modelo CHECK (NOT fora_do_modelo OR papel IN ('user', 'assistant')),
    ADD CONSTRAINT ck_atlas_mensagem_sem_modelo CHECK (NOT sem_modelo OR papel = 'assistant');
UPDATE atlas_mensagem SET sem_modelo = TRUE
 WHERE papel = 'assistant' AND jsonb_path_exists(tool_calls, '$[*].id ? (@ like_regex "^botao_")');

-- ---------------------------------------------------------------------
-- 3. A conversa sem parceria (reserva do M8: o Atlas da RBS)
-- ---------------------------------------------------------------------
-- [ISOLAMENTO] Uma conversa é de uma parceria (a da marca) ou de um evento (a da RBS, que não fala de uma marca só). A regra exige uma
-- das duas; nenhuma rota cria conversa sem parceria ainda, e as da marca tratam a que não tem parceria como inexistente.
ALTER TABLE atlas_conversa ALTER COLUMN parceria_id DROP NOT NULL;
ALTER TABLE atlas_conversa ADD COLUMN evento_id BIGINT;
ALTER TABLE atlas_conversa
    ADD CONSTRAINT fk_atlas_conversa_evento FOREIGN KEY (evento_id) REFERENCES evento(id),
    ADD CONSTRAINT ck_atlas_conversa_dono CHECK (parceria_id IS NOT NULL OR evento_id IS NOT NULL);
CREATE INDEX ix_atlas_conversa_evento ON atlas_conversa (evento_id, usuario_id, criada_em DESC) WHERE evento_id IS NOT NULL;

-- O traço da conversa sem parceria também não tem parceria. A chave composta fk_atlas_traco_conversa (conversa_id, parceria_id) só confere
-- quando as duas colunas têm valor; com parceria_id nulo ela não confere nada, então este gatilho fecha o buraco: um traço sem parceria só
-- pode ser de uma conversa sem parceria (nunca de uma conversa de marca, que ficaria invisível ao diagnóstico dela).
ALTER TABLE atlas_traco ALTER COLUMN parceria_id DROP NOT NULL;
CREATE FUNCTION tg_atlas_traco_parceria() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.parceria_id IS NULL
       AND EXISTS (SELECT 1 FROM atlas_conversa WHERE id = NEW.conversa_id AND parceria_id IS NOT NULL) THEN
        RAISE EXCEPTION 'O traço de uma conversa de parceria tem de levar a parceria dela.'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_atlas_traco_parceria';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_atlas_traco_parceria BEFORE INSERT OR UPDATE OF parceria_id, conversa_id ON atlas_traco
    FOR EACH ROW EXECUTE FUNCTION tg_atlas_traco_parceria();

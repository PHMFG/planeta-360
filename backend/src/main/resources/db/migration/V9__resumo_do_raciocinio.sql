-- O raciocínio do modelo de linguagem deixa de ir para a tela: a conversa mostra só um resumo curto dele, gerado no servidor.
-- A V1 a V8 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
--   1. atlas_mensagem.resumo_do_raciocinio: o resumo do reasoning_content daquela mensagem do assistente.
-- O reasoning_content continua gravado como estava (volta ao modelo nas idas seguintes, que o DeepSeek exige com ferramentas) e
-- nunca sai do servidor. O resumo é só para a tela: não entra no histórico que vai ao modelo e não conta no teto diário.
-- Só acrescenta uma coluna opcional: nenhuma tabela, visão, função ou gatilho que já existia muda, e a amostra fica como está.

-- ---------------------------------------------------------------------
-- 1. RESUMO DO RACIOCÍNIO
-- ---------------------------------------------------------------------
-- [ATLAS] Nulo = a mensagem não tem resumo (não teve raciocínio, o resumo foi recusado pela checagem do texto, o provedor falhou ou
-- demorou, ou é uma mensagem de antes da V9). Mensagem antiga com raciocínio e sem resumo fica sem item na conversa: o cru nunca
-- aparece. Só a mensagem do assistente pode ter resumo, e o texto, quando existe, não é vazio.
ALTER TABLE atlas_mensagem ADD COLUMN resumo_do_raciocinio TEXT;
ALTER TABLE atlas_mensagem ADD CONSTRAINT ck_atlas_mensagem_resumo
    CHECK (resumo_do_raciocinio IS NULL OR (papel = 'assistant' AND btrim(resumo_do_raciocinio) <> ''));

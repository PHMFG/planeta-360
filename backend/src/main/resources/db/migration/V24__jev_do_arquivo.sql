-- O JEV no destino do arquivo importado (AC3). O ponto ARQUIVO_DESTINO roda na leitura da importação, fora de uma conversa: não há
-- mensagem da pessoa para amarrar a decisão, e o ponto grava só o que teria sugerido (SOMBRA). Por isso a decisão passa a aceitar
-- mensagem_id nulo e o ponto novo entra na lista do CHECK; a etapa correspondente entra no traço, embora esta versão não a escreva.
ALTER TABLE atlas_decisao ALTER COLUMN mensagem_id DROP NOT NULL;
ALTER TABLE atlas_decisao DROP CONSTRAINT ck_atlas_decisao_ponto;
ALTER TABLE atlas_decisao
    ADD CONSTRAINT ck_atlas_decisao_ponto CHECK (ponto IS NULL OR ponto IN ('intencao','cartao','fontes','resposta','coerencia',
                                                                           'lacunas','arquivo_destino'));
ALTER TABLE atlas_traco DROP CONSTRAINT ck_atlas_traco_etapa;
ALTER TABLE atlas_traco
    ADD CONSTRAINT ck_atlas_traco_etapa CHECK (etapa IN ('G1_LIMITES','J1_INTENCAO','J1_CARTAO','LLM_IDA','FERRAMENTA','BUSCA',
                                                         'R3_FONTES','CHECAGEM','R5_RESPOSTA','V2_COERENCIA','LACUNAS','RESUMO',
                                                         'SAIDA','DISJUNTOR','ARQUIVO_DESTINO','ERRO'));

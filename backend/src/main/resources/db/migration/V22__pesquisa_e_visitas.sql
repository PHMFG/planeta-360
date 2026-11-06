-- V22: o banco da pesquisa e das visitas (fases F5 e F6 do plano da extração). Só acrescenta e relaxa uma regra: o limite de
-- perguntas por pesquisa, o tipo e as alternativas da pergunta, as respostas de texto, o perfil demográfico, o campo
-- "Pessoas na ativação" e a marca da última visita de cada pessoa a cada aba. Nenhuma V antiga é editada.
-- As perguntas que já existem continuam válidas: ficam com tipo ESCOLHA, sem alternativas, e o limite começa em 10.

-- ---------------------------------------------------------------------
-- 1. O limite de perguntas por pesquisa (P1)
-- ---------------------------------------------------------------------
-- [PESQUISA] O limite deixa de ser 3 e vira configuração da pesquisa (padrão 10, no máximo 30). A pergunta sem pesquisa
-- (rascunho da marca antes de a RBS abrir a da edição) vale o padrão de 10.
ALTER TABLE pesquisa
    ADD COLUMN limite_perguntas SMALLINT NOT NULL DEFAULT 10
        CONSTRAINT ck_pesquisa_limite_perguntas CHECK (limite_perguntas BETWEEN 1 AND 30);

-- O CHECK automático da V1 (pesquisa_pergunta_ordem_check) limitava a 3; o teto agora é o da coluna acima.
ALTER TABLE pesquisa_pergunta DROP CONSTRAINT pesquisa_pergunta_ordem_check;
ALTER TABLE pesquisa_pergunta ADD CONSTRAINT ck_pesquisa_pergunta_ordem CHECK (ordem BETWEEN 1 AND 30);

-- [PESQUISA] O gatilho confere a ordem contra o limite da pesquisa da própria pergunta; sem pesquisa, o padrão é 10.
CREATE FUNCTION tg_pesquisa_pergunta_limite() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_limite SMALLINT := 10;
BEGIN
    IF NEW.pesquisa_id IS NOT NULL THEN
        SELECT limite_perguntas INTO v_limite FROM pesquisa WHERE id = NEW.pesquisa_id;
        IF NOT FOUND THEN v_limite := 10; END IF;
    END IF;
    IF NEW.ordem > v_limite THEN
        RAISE EXCEPTION 'Esta pesquisa permite até % perguntas, e esta seria a de número %.', v_limite, NEW.ordem
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_pesquisa_pergunta_limite';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rn_pesquisa_pergunta_limite BEFORE INSERT OR UPDATE OF ordem, pesquisa_id ON pesquisa_pergunta
    FOR EACH ROW EXECUTE FUNCTION tg_pesquisa_pergunta_limite();

-- ---------------------------------------------------------------------
-- 2. O tipo de resposta da pergunta (P3)
-- ---------------------------------------------------------------------
-- ESCOLHA usa a lista de alternativas; SIM_NAO e NOTA (0 a 10) não precisam dela; TEXTO é a resposta livre (a tabela abaixo).
-- As perguntas que já existem ficam ESCOLHA, com alternativas nulas (o resultado antigo continua no pesquisa_resultado).
ALTER TABLE pesquisa_pergunta
    ADD COLUMN tipo_resposta VARCHAR(10) NOT NULL DEFAULT 'ESCOLHA'
        CONSTRAINT ck_pesquisa_pergunta_tipo CHECK (tipo_resposta IN ('ESCOLHA', 'SIM_NAO', 'NOTA', 'TEXTO')),
    ADD COLUMN alternativas JSONB
        CONSTRAINT ck_pesquisa_pergunta_alternativas CHECK (alternativas IS NULL OR jsonb_typeof(alternativas) = 'array');

-- ---------------------------------------------------------------------
-- 3. As respostas de texto livre (P4)
-- ---------------------------------------------------------------------
-- [PESQUISA] Cada resposta de uma pergunta TEXTO, já agregada como a carga faz com as alternativas: o texto e a contagem das
-- repetições. O detector de dado pessoal marca a suspeita; com suspeita, a resposta não aparece para a marca (regra da API).
CREATE TABLE pesquisa_resposta_texto (
    id                    BIGSERIAL   PRIMARY KEY,
    pesquisa_id           BIGINT      NOT NULL REFERENCES pesquisa(id),
    parceria_id           BIGINT      NOT NULL REFERENCES parceria(id),
    pergunta_id           BIGINT      NOT NULL REFERENCES pesquisa_pergunta(id),
    texto                 VARCHAR(1000) NOT NULL CONSTRAINT ck_pesquisa_resposta_texto_texto CHECK (btrim(texto) <> ''),
    contagem              INT         NOT NULL DEFAULT 1 CONSTRAINT ck_pesquisa_resposta_texto_contagem CHECK (contagem > 0),
    suspeita_dado_pessoal BOOLEAN     NOT NULL DEFAULT FALSE,
    carga_id              BIGINT      REFERENCES carga(id),
    importacao_id         BIGINT      REFERENCES importacao(id)
);
CREATE INDEX ix_pesquisa_resposta_texto_pergunta ON pesquisa_resposta_texto (pergunta_id);

-- ---------------------------------------------------------------------
-- 4. O perfil demográfico da pesquisa (P5)
-- ---------------------------------------------------------------------
-- [PESQUISA] Uma categoria de uma dimensão, com o valor (percentual) e a base de respondentes. Parceria nula é a pesquisa
-- geral do festival; com parceria, é a ativação daquela marca. A chave natural é toda a dimensão e a categoria.
CREATE TABLE pesquisa_perfil (
    id             BIGSERIAL   PRIMARY KEY,
    pesquisa_id    BIGINT      NOT NULL REFERENCES pesquisa(id),
    parceria_id    BIGINT      REFERENCES parceria(id),
    dimensao       VARCHAR(20) NOT NULL CONSTRAINT ck_pesquisa_perfil_dimensao
        CHECK (dimensao IN ('GENERO', 'FAIXA_ETARIA', 'RENDA', 'DOMICILIO', 'SETOR', 'RECORRENCIA', 'OUTRA')),
    categoria      VARCHAR(80) NOT NULL CONSTRAINT ck_pesquisa_perfil_categoria CHECK (btrim(categoria) <> ''),
    valor          NUMERIC(5,2) NOT NULL CONSTRAINT ck_pesquisa_perfil_valor CHECK (valor BETWEEN 0 AND 100),
    base_respondentes INT      NOT NULL CONSTRAINT ck_pesquisa_perfil_base CHECK (base_respondentes > 0),
    carga_id       BIGINT      REFERENCES carga(id),
    importacao_id  BIGINT      REFERENCES importacao(id),
    CONSTRAINT uq_pesquisa_perfil UNIQUE NULLS NOT DISTINCT (pesquisa_id, parceria_id, dimensao, categoria)
);
CREATE INDEX ix_pesquisa_perfil_parceria ON pesquisa_perfil (parceria_id) WHERE parceria_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 5. O público da ativação no catálogo (P6)
-- ---------------------------------------------------------------------
-- [DADOS] O campo entra depois dos quatro inteiros da ativação, com a mesma forma: inteiro a partir de 0, sem máximo.
INSERT INTO campo_catalogo (chave, aba_id, ordem, rotulo, tipo, unidade, minimo, maximo, ajuda, limite_campo) VALUES
 ('ativacao.pessoas_na_ativacao', (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 5, 'Pessoas na ativação',
  'INTEIRO', 'pessoas', 0, NULL,
  'Quantas pessoas participaram da ativação da marca no festival, segundo a contagem da agência.', NULL);
-- A planilha da agência (V14) passa para depois do campo novo, que é um número da ativação. É só a ordem do formulário.
UPDATE campo_catalogo SET ordem = 6 WHERE chave = 'ativacao.planilha_agencia';

-- ---------------------------------------------------------------------
-- 6. A última visita de cada pessoa a cada aba (S3)
-- ---------------------------------------------------------------------
-- [PESQUISA] [FRONT] A marca da última visita: a API compara o que mudou desde então para desenhar o selo "novo". A chave é
-- a pessoa, a parceria e a aba; a aba RELATORIO não está no menu, mas o relatório também tem novidade. Sem e-mail (D7).
CREATE TABLE visita_aba (
    usuario_id   BIGINT      NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    parceria_id  BIGINT      NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    aba          VARCHAR(20) NOT NULL CONSTRAINT ck_visita_aba CHECK (aba IN ('PERFIL', 'APLICACOES', 'MIDIA', 'CORTESIAS',
                                                                              'CREDENCIAMENTO', 'PESQUISA', 'CONTRATO', 'RELATORIO')),
    visto_em     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (usuario_id, parceria_id, aba)
);

-- Primeira parte do MVP do Atlas: o plano comercial por CSV, as fotos de comprovação e os números que só a marca sabe informar.
-- A V1 a V6 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
--   1. stg_plano e fn_carregar_plano: a carga PLANO (rota 26) enche aproveitamento_previsto, que até aqui só a amostra enchia.
--   2. campo_catalogo, dado_informado e vw_dado_vigente: o que a marca informa (visitantes, brindes, cadastros), com autor, data e origem.
--   3. rn_dado_informado_imutavel: um dado informado nunca muda; corrigir é gravar um valor novo.
--   4. Os quatro campos numéricos da aba Aplicações.
-- As comprovações (comprovacao_marca) já existem desde a V1 com os gatilhos rn_comprovacao_*; esta migração não as altera.

-- ---------------------------------------------------------------------
-- 1. CARGA DO PLANO COMERCIAL
-- ---------------------------------------------------------------------
-- [CARGA] Área de passagem, no mesmo molde de stg_veiculacao: as linhas do plano.csv como chegam, antes do vínculo.
-- A API converte a quantidade antes de gravar; o que não converte vai nulo, com o texto original em quantidade_texto,
-- e a função rejeita a linha dizendo o formato esperado. Os limites de texto são os de aproveitamento_previsto.
CREATE TABLE stg_plano (
    carga_id             BIGINT   NOT NULL REFERENCES carga(id) ON DELETE CASCADE,
    linha                INTEGER  NOT NULL,
    patrocinador         VARCHAR(120),
    categoria            VARCHAR(20),     -- MIDIA | APLICACAO_MARCA
    bloco                VARCHAR(80),
    veiculo              VARCHAR(80),     -- obrigatório também nas aplicações ("No evento"), como a coluna do plano
    formato              VARCHAR(160),
    espaco_programa      VARCHAR(160),
    janela               VARCHAR(80),
    tipo_midia           VARCHAR(20),     -- obrigatório em mídia (ck_aprov_tipo)
    quantidade_prevista  NUMERIC(14,2),
    quantidade_texto     VARCHAR(40),     -- o original, só do que a API não conseguiu converter
    unidade              VARCHAR(15),     -- INSERCAO | CPM | APLICACAO
    fonte_pagina         SMALLINT,        -- fora do SMALLINT a API grava nulo (a página é opcional)
    PRIMARY KEY (carga_id, linha)
);

-- [CARGA] [VALIDACAO] [DUPLICIDADE] Mesma estrutura de fn_carregar_veiculacoes (V4): trava a carga, devolve os totais gravados
-- se ela já foi processada, rejeita com um motivo por linha (o primeiro problema) tudo o que a V1 recusaria no INSERT (porque
-- uma linha que fere um CHECK derrubaria a carga inteira), liga o patrocinador à parceria da edição e insere sem duplicar.
--  - A chave natural uq_aprov_chave_natural é um índice sobre expressões, e não uma constraint: ON CONFLICT ON CONSTRAINT
--    (o molde da veiculação) falha. Aqui vai ON CONFLICT DO NOTHING sem alvo, que cobre o índice.
--  - O índice compara com lower() e sem btrim(); por isso o texto é gravado já com btrim(), para "Testeira " e "Testeira" não
--    virarem duas linhas do plano. Espaço de programa vazio já vale '' no índice, então nulo não abre brecha para repetição.
--  - O mesmo arquivo reenviado, ou uma linha repetida no próprio arquivo, conta como duplicada (fica a de menor número):
--    a primeira versão prevalece, como na veiculação. Corrigir uma linha do plano fica fora desta etapa.
--  - fonte_arquivo_id fica nulo: o CSV é arquivo geral da RBS, e a chave composta com a parceria recusaria a ligação.
CREATE FUNCTION fn_carregar_plano(p_carga_id BIGINT, p_evento_id BIGINT)
RETURNS TABLE (lidas INT, inseridas INT, duplicadas INT, rejeitadas INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_carga carga%ROWTYPE;
    v_lidas INT; v_ok INT; v_ins INT; v_rej INT;
BEGIN
    SELECT * INTO v_carga FROM carga WHERE id = p_carga_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A carga % não existe.', p_carga_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_carga_inexistente';
    END IF;
    IF v_carga.tipo <> 'PLANO_COMERCIAL' THEN
        RAISE EXCEPTION 'A carga % é do tipo %, e esta função só processa cargas do plano comercial.', p_carga_id, v_carga.tipo
            USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_carga_tipo';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM evento WHERE id = p_evento_id) THEN
        RAISE EXCEPTION 'O evento % não existe.', p_evento_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_evento_inexistente';
    END IF;
    IF v_carga.processada_em IS NOT NULL THEN
        RETURN QUERY SELECT v_carga.linhas_lidas, v_carga.linhas_inseridas, v_carga.linhas_duplicadas, v_carga.linhas_rejeitadas;
        RETURN;
    END IF;

    SELECT count(*) INTO v_lidas FROM stg_plano WHERE carga_id = p_carga_id;

    -- [VALIDACAO] 1. Texto sem as pontas e, em cada linha, o primeiro problema da lista abaixo (a ordem é a da lista). Tudo o
    -- que a V1 recusaria fica de fora aqui: categoria, unidade e tipo fora da lista, quantidade que não é positiva, campo
    -- obrigatório vazio e aplicação de marca com unidade que não seja APLICACAO (a vw_aplicacoes soma tudo como aplicação).
    DROP TABLE IF EXISTS pg_temp.tmp_plano;
    CREATE TEMP TABLE tmp_plano ON COMMIT DROP AS
    SELECT n.*, e.campo, e.motivo
    FROM (SELECT s.linha,
                 nullif(btrim(s.patrocinador), '')       AS patrocinador,
                 nullif(btrim(s.categoria), '')          AS categoria,
                 nullif(btrim(s.bloco), '')              AS bloco,
                 nullif(btrim(s.veiculo), '')            AS veiculo,
                 nullif(btrim(s.formato), '')            AS formato,
                 nullif(btrim(s.espaco_programa), '')    AS espaco_programa,
                 nullif(btrim(s.janela), '')             AS janela,
                 nullif(btrim(s.tipo_midia), '')         AS tipo_midia,
                 s.quantidade_prevista                   AS quantidade,
                 nullif(btrim(s.quantidade_texto), '')   AS quantidade_texto,
                 nullif(btrim(s.unidade), '')            AS unidade,
                 CASE WHEN s.fonte_pagina >= 1 THEN s.fonte_pagina END AS fonte_pagina
          FROM stg_plano s WHERE s.carga_id = p_carga_id) n
    LEFT JOIN LATERAL (
         SELECT v.campo, v.motivo
         FROM (VALUES
                (1,  'patrocinador',        n.patrocinador IS NULL,   'Campo obrigatório vazio ou inválido'),
                (2,  'categoria',           n.categoria IS NULL,      'Campo obrigatório vazio ou inválido'),
                (3,  'veiculo',             n.veiculo IS NULL,        'Campo obrigatório vazio ou inválido'),
                (4,  'formato',             n.formato IS NULL,        'Campo obrigatório vazio ou inválido'),
                (5,  'quantidade_prevista', n.quantidade IS NULL AND n.quantidade_texto IS NULL,
                                                                      'Campo obrigatório vazio ou inválido'),
                (6,  'unidade',             n.unidade IS NULL,        'Campo obrigatório vazio ou inválido'),
                (7,  'quantidade_prevista', n.quantidade IS NULL,
                                                                      'Quantidade inválida. Use só números, como 1 ou 250 (ponto nos decimais).'),
                (8,  'quantidade_prevista', n.quantidade <= 0,        'A quantidade deve ser maior que zero.'),
                (9,  'categoria',           n.categoria NOT IN ('MIDIA', 'APLICACAO_MARCA'),
                                                                      'Categoria inválida. Use MIDIA ou APLICACAO_MARCA.'),
                (10, 'unidade',             n.unidade NOT IN ('INSERCAO', 'CPM', 'APLICACAO'),
                                                                      'Unidade inválida. Use INSERCAO, CPM ou APLICACAO.'),
                (11, 'unidade',             n.categoria = 'APLICACAO_MARCA' AND n.unidade <> 'APLICACAO',
                                                                      'Aplicação de marca usa a unidade APLICACAO.'),
                (12, 'tipo_midia',          (n.categoria = 'MIDIA' AND n.tipo_midia IS NULL)
                                              OR n.tipo_midia NOT IN ('ASSINADA', 'PROPRIA', 'MERCHANDISING', 'BRANDED', 'CONTEUDO_DE_MARCA'),
                                                                      'Tipo de mídia inválido. Use ASSINADA, PROPRIA, MERCHANDISING, BRANDED ou CONTEUDO_DE_MARCA.')
              ) v(ordem, campo, falha, motivo)
         WHERE coalesce(v.falha, FALSE)
         ORDER BY v.ordem LIMIT 1) e ON TRUE;

    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT p_carga_id, t.linha, t.campo, t.motivo,
           concat_ws(' | ', t.patrocinador, t.categoria, t.bloco, t.veiculo, t.formato, t.espaco_programa, t.janela,
                     t.tipo_midia, coalesce(fn_numero_br(t.quantidade), t.quantidade_texto), t.unidade)
    FROM tmp_plano t WHERE t.campo IS NOT NULL;

    -- 2. Vínculo com o patrocinador (nome canônico ou grafia alternativa) e com a parceria da edição
    DROP TABLE IF EXISTS pg_temp.tmp_plano_vinculo;
    CREATE TEMP TABLE tmp_plano_vinculo ON COMMIT DROP AS
    SELECT t.linha, t.patrocinador, t.categoria, t.bloco, t.veiculo, t.formato, t.espaco_programa, t.janela, t.tipo_midia,
           t.quantidade, t.quantidade_texto, t.unidade, t.fonte_pagina, pa.id AS parceria_id
    FROM tmp_plano t
    LEFT JOIN LATERAL (
         SELECT coalesce(
                    (SELECT p.id FROM patrocinador p WHERE lower(p.nome_canonico) = lower(t.patrocinador)),
                    (SELECT a.patrocinador_id FROM patrocinador_alias a WHERE lower(a.alias) = lower(t.patrocinador))
                ) AS id) pat ON TRUE
    LEFT JOIN parceria pa ON pa.patrocinador_id = pat.id AND pa.evento_id = p_evento_id
    WHERE t.campo IS NULL;

    INSERT INTO carga_rejeicao (carga_id, linha, campo, motivo, conteudo)
    SELECT p_carga_id, v.linha, 'patrocinador', 'Patrocinador sem parceria nesta edição',
           concat_ws(' | ', v.patrocinador, v.categoria, v.bloco, v.veiculo, v.formato, v.espaco_programa, v.janela,
                     v.tipo_midia, fn_numero_br(v.quantidade), v.unidade)
    FROM tmp_plano_vinculo v WHERE v.parceria_id IS NULL;

    SELECT count(*) INTO v_ok FROM tmp_plano_vinculo WHERE parceria_id IS NOT NULL;

    -- [DUPLICIDADE] 3. Inserção sem contagem duplicada. A chave é a do índice: parceria + veículo + formato + espaço (sem
    -- categoria, unidade nem janela). DISTINCT ON cobre a repetição dentro do arquivo; ON CONFLICT cobre o que já está na base.
    -- Aplicação de marca não leva tipo de mídia (a coluna é só da mídia, como na amostra).
    INSERT INTO aproveitamento_previsto (parceria_id, categoria, bloco, veiculo, formato, espaco_programa, janela, tipo_midia,
                                         quantidade_prevista, unidade, fonte_pagina)
    SELECT DISTINCT ON (v.parceria_id, lower(v.veiculo), lower(v.formato), lower(coalesce(v.espaco_programa, '')))
           v.parceria_id, v.categoria, v.bloco, v.veiculo, v.formato, v.espaco_programa, v.janela,
           CASE WHEN v.categoria = 'MIDIA' THEN v.tipo_midia END, v.quantidade, v.unidade, v.fonte_pagina
    FROM tmp_plano_vinculo v WHERE v.parceria_id IS NOT NULL
    ORDER BY v.parceria_id, lower(v.veiculo), lower(v.formato), lower(coalesce(v.espaco_programa, '')), v.linha
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_ins = ROW_COUNT;

    SELECT count(DISTINCT linha) INTO v_rej FROM carga_rejeicao WHERE carga_id = p_carga_id;

    UPDATE carga SET linhas_lidas = v_lidas, linhas_inseridas = v_ins,
                     linhas_duplicadas = v_ok - v_ins, linhas_rejeitadas = v_rej,
                     evento_id = p_evento_id, processada_em = now()
    WHERE id = p_carga_id;

    DROP TABLE IF EXISTS pg_temp.tmp_plano_vinculo;
    DROP TABLE IF EXISTS pg_temp.tmp_plano;
    RETURN QUERY SELECT v_lidas, v_ins, v_ok - v_ins, v_rej;
END $$;

-- ---------------------------------------------------------------------
-- 2. CATÁLOGO DE CAMPOS E DADOS INFORMADOS
-- ---------------------------------------------------------------------
-- Os números que o sistema não tem e que só a marca (ou a agência dela) sabe: visitantes no espaço, brindes, cadastros.
-- O catálogo é fixo e vem da migração: ele diz, campo a campo, o rótulo, o tipo, a unidade e os limites que a API confere.
-- [VALIDACAO] limite_campo aponta para outro campo do catálogo cujo valor vigente o campo não pode passar (cadastros não
-- passam de visitantes; brindes distribuídos não passam dos disponíveis). Sem isso a taxa de um indicador passaria de 100%.
-- ordem só organiza a lista (formulário); a chave é a identidade do campo.
CREATE TABLE campo_catalogo (
    chave         VARCHAR(80)  PRIMARY KEY,                      -- ex.: ativacao.visitantes_total
    aba_id        SMALLINT     NOT NULL REFERENCES aba(id),
    ordem         SMALLINT     NOT NULL DEFAULT 100,
    rotulo        VARCHAR(120) NOT NULL,
    tipo          VARCHAR(10)  NOT NULL CHECK (tipo IN ('INTEIRO','DECIMAL','DATA','ARQUIVO')),
    unidade       VARCHAR(20),
    minimo        NUMERIC(14,2),
    maximo        NUMERIC(14,2),
    ajuda         VARCHAR(300),
    limite_campo  VARCHAR(80)  REFERENCES campo_catalogo(chave),
    CONSTRAINT ck_campo_limite_proprio CHECK (limite_campo IS DISTINCT FROM chave)
);

-- [HISTORICO] Cada valor informado é uma linha, com autor, data e origem (PORTAL = formulário da aba; ATLAS = cartão do chat).
-- Corrigir é gravar uma linha nova: a vigente é a mais recente de cada (parceria, campo) e as anteriores ficam como trilha.
-- [ISOLAMENTO] arquivo_id (campo de arquivo, da etapa seguinte) só aceita arquivo da mesma parceria, pela chave composta.
CREATE TABLE dado_informado (
    id             BIGSERIAL    PRIMARY KEY,
    parceria_id    BIGINT       NOT NULL REFERENCES parceria(id),
    campo_chave    VARCHAR(80)  NOT NULL REFERENCES campo_catalogo(chave),
    valor_numero   NUMERIC(14,2),
    valor_data     DATE,
    arquivo_id     BIGINT,
    origem         VARCHAR(10)  NOT NULL CHECK (origem IN ('PORTAL','ATLAS')),
    informado_por  BIGINT       NOT NULL REFERENCES usuario(id),
    informado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_dado_informado_arquivo_parceria FOREIGN KEY (arquivo_id, parceria_id)
        REFERENCES arquivo (id, parceria_id),
    CONSTRAINT ck_dado_informado_um_valor CHECK (num_nonnulls(valor_numero, valor_data, arquivo_id) = 1)
);
CREATE INDEX ix_dado_informado_vigente ON dado_informado (parceria_id, campo_chave, informado_em DESC, id DESC);

-- O valor vigente de cada campo de cada parceria (a linha mais recente).
CREATE VIEW vw_dado_vigente AS
SELECT DISTINCT ON (parceria_id, campo_chave)
       id, parceria_id, campo_chave, valor_numero, valor_data, arquivo_id, origem, informado_por, informado_em
FROM dado_informado
ORDER BY parceria_id, campo_chave, informado_em DESC, id DESC;

-- [HISTORICO] Um dado informado nunca muda nem se apaga: UPDATE e DELETE são recusados, como no histórico.
CREATE FUNCTION tg_dado_informado_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'O dado informado não pode ser alterado nem apagado (tentativa de % na linha %). Para corrigir, informe um valor novo.', TG_OP, OLD.id
        USING ERRCODE = 'check_violation', CONSTRAINT = 'rn_dado_informado_imutavel';
END $$;
CREATE TRIGGER rn_dado_informado_imutavel BEFORE UPDATE OR DELETE ON dado_informado
    FOR EACH ROW EXECUTE FUNCTION tg_dado_informado_imutavel();

-- ---------------------------------------------------------------------
-- 3. OS CAMPOS DA ABA APLICAÇÕES (todos os ambientes)
-- ---------------------------------------------------------------------
-- Quatro inteiros a partir de 0, sem máximo. O campo de arquivo (planilha da agência) entra numa etapa seguinte; a coluna
-- arquivo_id e o tipo ARQUIVO já ficam prontos acima. A ordem é a do formulário: o limite vem antes de quem depende dele.
INSERT INTO campo_catalogo (chave, aba_id, ordem, rotulo, tipo, unidade, minimo, maximo, ajuda, limite_campo) VALUES
 ('ativacao.visitantes_total',       (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 1, 'Visitantes no espaço',
  'INTEIRO', 'pessoas',   0, NULL,
  'Quantas pessoas passaram pelo espaço da marca durante o festival, segundo a contagem da agência.', NULL),
 ('ativacao.cadastros_consentidos',  (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 2, 'Cadastros com consentimento',
  'INTEIRO', 'cadastros', 0, NULL,
  'Quantas pessoas deixaram os dados e aceitaram o uso deles. Não pode passar do total de visitantes no espaço.',
  'ativacao.visitantes_total'),
 ('ativacao.brindes_disponiveis',    (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 3, 'Brindes disponíveis',
  'INTEIRO', 'brindes',   0, NULL,
  'Quantos brindes a marca levou para distribuir no espaço.', NULL),
 ('ativacao.brindes_distribuidos',   (SELECT id FROM aba WHERE codigo = 'APLICACOES'), 4, 'Brindes distribuídos',
  'INTEIRO', 'brindes',   0, NULL,
  'Quantos brindes foram entregues ao público. Não pode passar dos brindes disponíveis.',
  'ativacao.brindes_disponiveis');

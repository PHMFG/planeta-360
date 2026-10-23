-- =====================================================================
-- Portal do Patrocinador do Planeta Atlântida - modelo físico V1
-- Banco: PostgreSQL 16
-- Organização: 7 domínios (núcleo, acesso, abas e entregas, mídia e
-- aplicações, operação do evento, pesquisa, relatório)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. NÚCLEO DA PARCERIA
-- ---------------------------------------------------------------------
CREATE TABLE evento (
    id            BIGSERIAL PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    edicao        SMALLINT     NOT NULL CHECK (edicao BETWEEN 1996 AND 2100),
    data_inicio   DATE,
    data_fim      DATE,
    local         VARCHAR(160),
    CONSTRAINT uq_evento UNIQUE (nome, edicao),
    CONSTRAINT ck_evento_datas CHECK (data_fim IS NULL OR data_inicio IS NULL OR data_fim >= data_inicio)
);

-- Números gerais da edição (público, marcas, alcance), carregados pela RBS
CREATE TABLE evento_resultado (
    id            BIGSERIAL PRIMARY KEY,
    evento_id     BIGINT NOT NULL REFERENCES evento(id) ON DELETE CASCADE,
    indicador     VARCHAR(80)  NOT NULL,
    valor         NUMERIC(16,2) NOT NULL,
    qualificador  VARCHAR(8)   NOT NULL DEFAULT 'EXATO' CHECK (qualificador IN ('EXATO','MAIS_DE')),
    unidade       VARCHAR(20)  NOT NULL,
    fonte         VARCHAR(200) NOT NULL,
    CONSTRAINT uq_evento_resultado UNIQUE (evento_id, indicador)
);

CREATE TABLE patrocinador (
    id              BIGSERIAL PRIMARY KEY,
    nome_canonico   VARCHAR(120) NOT NULL,
    segmento        VARCHAR(80),
    criado_em       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_patrocinador_nome ON patrocinador (lower(nome_canonico));

-- Grafias alternativas encontradas nas fontes (ex.: "Coca Cola" x "Coca-Cola")
CREATE TABLE patrocinador_alias (
    id               BIGSERIAL PRIMARY KEY,
    patrocinador_id  BIGINT NOT NULL REFERENCES patrocinador(id) ON DELETE CASCADE,
    alias            VARCHAR(120) NOT NULL
);
CREATE UNIQUE INDEX uq_patrocinador_alias ON patrocinador_alias (lower(alias));

CREATE TABLE cota_tipo (
    id      SMALLINT PRIMARY KEY,
    codigo  VARCHAR(20)  NOT NULL UNIQUE CHECK (codigo IN ('MASTER','SUBCOTA_G','SUBCOTA')),
    nome    VARCHAR(60)  NOT NULL,
    nivel   SMALLINT     NOT NULL CHECK (nivel BETWEEN 1 AND 3)   -- 3 = módulo mais completo
);

-- A parceria é a unidade de isolamento: tudo o que o patrocinador vê pende dela
CREATE TABLE parceria (
    id                  BIGSERIAL PRIMARY KEY,
    evento_id           BIGINT   NOT NULL REFERENCES evento(id),
    patrocinador_id     BIGINT   NOT NULL REFERENCES patrocinador(id),
    cota_tipo_id        SMALLINT NOT NULL REFERENCES cota_tipo(id),
    nome_produto        VARCHAR(120),             -- nome comercial do plano (ex.: "Cota Apresenta")
    valor_investimento  NUMERIC(14,2) CHECK (valor_investimento >= 0),
    base_valor          VARCHAR(15) CHECK (base_valor IN ('BRUTO_TABELA','LIQUIDO')),
    situacao            VARCHAR(20) NOT NULL DEFAULT 'ATIVA'
                        CHECK (situacao IN ('EM_NEGOCIACAO','ATIVA','ENCERRADA','CANCELADA')),
    criado_em           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_parceria UNIQUE (evento_id, patrocinador_id),
    CONSTRAINT ck_parceria_valor CHECK (valor_investimento IS NULL OR base_valor IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- 2. ACESSO
-- ---------------------------------------------------------------------
CREATE TABLE usuario (
    id          BIGSERIAL PRIMARY KEY,
    nome        VARCHAR(120) NOT NULL,
    email       VARCHAR(254) NOT NULL CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    senha_hash  VARCHAR(255),                       -- nulo até o primeiro acesso
    perfil      VARCHAR(20)  NOT NULL CHECK (perfil IN ('ADMIN_RBS','ADMIN_PATROCINADOR')),
    ativo       BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_usuario_email ON usuario (lower(email));

-- Cadastro prévio exigido pelo TAP: só e-mails autorizados criam acesso
CREATE TABLE email_autorizado (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    email           VARCHAR(254) NOT NULL CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    vinculo         VARCHAR(10)  NOT NULL CHECK (vinculo IN ('MARCA','AGENCIA')),
    autorizado_por  BIGINT NOT NULL REFERENCES usuario(id),
    autorizado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_email_autorizado ON email_autorizado (parceria_id, lower(email));

-- Vínculo do usuário do patrocinador (marca ou agência) com a parceria
CREATE TABLE parceria_usuario (
    parceria_id  BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    usuario_id   BIGINT NOT NULL REFERENCES usuario(id)  ON DELETE CASCADE,
    vinculo      VARCHAR(10) NOT NULL CHECK (vinculo IN ('MARCA','AGENCIA')),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (parceria_id, usuario_id)
);
CREATE INDEX ix_parceria_usuario_usuario ON parceria_usuario (usuario_id);

-- ---------------------------------------------------------------------
-- 3. ABAS, ENTREGAS, ARQUIVOS E HISTÓRICO
-- ---------------------------------------------------------------------
CREATE TABLE aba (
    id          SMALLINT PRIMARY KEY,
    codigo      VARCHAR(30) NOT NULL UNIQUE,
    nome        VARCHAR(60) NOT NULL,
    ordem       SMALLINT    NOT NULL UNIQUE,
    prioridade  VARCHAR(10) NOT NULL CHECK (prioridade IN ('ESSENCIAL','DESEJAVEL'))
);

-- Liberação e visibilidade de cada aba por parceria (o menu tem 7 abas; a RBS controla o que abre)
CREATE TABLE parceria_aba (
    parceria_id  BIGINT   NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    aba_id       SMALLINT NOT NULL REFERENCES aba(id),
    visivel      BOOLEAN  NOT NULL DEFAULT TRUE,
    liberada     BOOLEAN  NOT NULL DEFAULT FALSE,   -- liberada para preenchimento pelo patrocinador
    PRIMARY KEY (parceria_id, aba_id)
);

CREATE TABLE entrega (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT   NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    aba_id          SMALLINT NOT NULL REFERENCES aba(id),
    titulo          VARCHAR(160) NOT NULL,
    descricao       TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'NAO_INICIADO'
                    CHECK (status IN ('NAO_INICIADO','AGUARDANDO_ENVIO','ENVIADO','EM_ANALISE',
                                      'AJUSTE_SOLICITADO','APROVADO','NAO_APLICAVEL')),
    prazo           DATE,
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_entrega_parceria ON entrega (parceria_id, aba_id);

-- Repositório próprio de arquivos (sem dependência do SharePoint)
CREATE TABLE arquivo (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT   REFERENCES parceria(id) ON DELETE CASCADE,  -- nulo = arquivo geral da RBS
    aba_id          SMALLINT REFERENCES aba(id),
    entrega_id      BIGINT   REFERENCES entrega(id) ON DELETE SET NULL,
    categoria       VARCHAR(30) NOT NULL
                    CHECK (categoria IN ('PLANO_COMERCIAL','MATERIAL_MARCA','MATERIAL_MIDIA','RELATORIO_VEICULACAO',
                                         'COMPROVACAO','PESQUISA','CONTRATO','DOCUMENTO_INGRESSOS',
                                         'RELATORIO_PARCERIA','OUTRO')),
    nome_original   VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(120) NOT NULL,
    tamanho_bytes   BIGINT NOT NULL CHECK (tamanho_bytes > 0),
    caminho         VARCHAR(500) NOT NULL UNIQUE,
    hash_sha256     CHAR(64),
    versao          SMALLINT NOT NULL DEFAULT 1 CHECK (versao >= 1),
    enviado_por     BIGINT NOT NULL REFERENCES usuario(id),
    enviado_em      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_arquivo_parceria ON arquivo (parceria_id, aba_id);
CREATE UNIQUE INDEX uq_arquivo_versao ON arquivo (entrega_id, versao) WHERE entrega_id IS NOT NULL;

-- Trilha de auditoria: quem fez o quê, quando e de onde
CREATE TABLE historico (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT REFERENCES parceria(id) ON DELETE CASCADE,
    entidade        VARCHAR(40) NOT NULL,        -- ex.: 'entrega', 'arquivo', 'parceria_aba'
    entidade_id     BIGINT      NOT NULL,
    acao            VARCHAR(30) NOT NULL CHECK (acao IN ('CRIACAO','ENVIO_ARQUIVO','NOVA_VERSAO','ALTERACAO_STATUS',
                                                         'EDICAO','LIBERACAO_ABA','EXCLUSAO','GERACAO_RELATORIO','VALIDACAO')),
    valor_anterior  TEXT,
    valor_novo      TEXT,
    usuario_id      BIGINT NOT NULL REFERENCES usuario(id),
    ocorrido_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip              INET
);
CREATE INDEX ix_historico_parceria ON historico (parceria_id, ocorrido_em DESC);
CREATE INDEX ix_historico_entidade ON historico (entidade, entidade_id);

-- ---------------------------------------------------------------------
-- 4. MÍDIA E APLICAÇÕES DE MARCA: PREVISTO x REALIZADO
-- ---------------------------------------------------------------------
-- Cargas de planilhas (mídia, pesquisa, comprovações): base da consolidação
CREATE TABLE carga (
    id                  BIGSERIAL PRIMARY KEY,
    tipo                VARCHAR(20) NOT NULL CHECK (tipo IN ('MIDIA','PESQUISA','COMPROVACAO','PLANO_COMERCIAL')),
    arquivo_id          BIGINT REFERENCES arquivo(id),
    executada_por       BIGINT NOT NULL REFERENCES usuario(id),
    executada_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    linhas_lidas        INTEGER NOT NULL DEFAULT 0,
    linhas_inseridas    INTEGER NOT NULL DEFAULT 0,
    linhas_duplicadas   INTEGER NOT NULL DEFAULT 0,
    linhas_rejeitadas   INTEGER NOT NULL DEFAULT 0
);

-- Linhas que não entraram na base, com o motivo (faltantes e inconsistências)
CREATE TABLE carga_rejeicao (
    id        BIGSERIAL PRIMARY KEY,
    carga_id  BIGINT  NOT NULL REFERENCES carga(id) ON DELETE CASCADE,
    linha     INTEGER NOT NULL,
    campo     VARCHAR(60),
    motivo    VARCHAR(200) NOT NULL,
    conteudo  TEXT
);

-- Catálogo do que a cota comprou (uma linha por veículo + formato + espaço + janela)
CREATE TABLE aproveitamento_previsto (
    id                  BIGSERIAL PRIMARY KEY,
    parceria_id         BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    categoria           VARCHAR(20) NOT NULL CHECK (categoria IN ('MIDIA','APLICACAO_MARCA')),
    bloco               VARCHAR(80),                 -- agrupador do plano (ex.: "Transmissão oficial")
    veiculo             VARCHAR(80) NOT NULL,        -- ex.: "Rede Atlântida RS", "atl.la", "No evento"
    formato             VARCHAR(160) NOT NULL,       -- ex.: "Citação 5\"", "Blindagem"
    espaco_programa     VARCHAR(160),                -- coluna "Determinação" do plano
    janela              VARCHAR(80),                 -- coluna "Período"
    tipo_midia          VARCHAR(20) CHECK (tipo_midia IN ('ASSINADA','PROPRIA','MERCHANDISING','BRANDED','CONTEUDO_DE_MARCA')),
    quantidade_prevista NUMERIC(14,2) NOT NULL CHECK (quantidade_prevista > 0),
    unidade             VARCHAR(15) NOT NULL CHECK (unidade IN ('INSERCAO','CPM','APLICACAO')),
    fonte_arquivo_id    BIGINT REFERENCES arquivo(id),
    fonte_pagina        SMALLINT,
    CONSTRAINT ck_aprov_tipo CHECK (categoria = 'APLICACAO_MARCA' OR tipo_midia IS NOT NULL)
);
CREATE INDEX ix_aprov_parceria ON aproveitamento_previsto (parceria_id, categoria);
-- Chave natural: o relatório de veiculação chega sem IDs internos
CREATE UNIQUE INDEX uq_aprov_chave_natural ON aproveitamento_previsto
    (parceria_id, lower(veiculo), lower(formato), lower(coalesce(espaco_programa, '')));

-- Área de carga: linhas como chegam do relatório de veiculação, antes do vínculo
CREATE TABLE stg_veiculacao (
    carga_id         BIGINT  NOT NULL REFERENCES carga(id) ON DELETE CASCADE,
    linha            INTEGER NOT NULL,
    patrocinador     VARCHAR(120),
    veiculo          VARCHAR(80),
    formato          VARCHAR(160),
    espaco_programa  VARCHAR(160),
    exibido_em       TIMESTAMPTZ,
    quantidade       NUMERIC(14,2),
    link             VARCHAR(500),
    PRIMARY KEY (carga_id, linha)
);

-- Cada exibição comprovada (relatórios de veiculação enviados pelas emissoras e pela RBS)
CREATE TABLE veiculacao (
    id                      BIGSERIAL PRIMARY KEY,
    aproveitamento_id       BIGINT NOT NULL REFERENCES aproveitamento_previsto(id) ON DELETE CASCADE,
    exibido_em              TIMESTAMPTZ NOT NULL,
    quantidade              NUMERIC(14,2) NOT NULL DEFAULT 1 CHECK (quantidade > 0),
    link                    VARCHAR(500),
    comprovante_arquivo_id  BIGINT REFERENCES arquivo(id),
    carga_id                BIGINT REFERENCES carga(id),
    -- chave natural: a mesma inserção não pode ser contada duas vezes
    CONSTRAINT uq_veiculacao UNIQUE (aproveitamento_id, exibido_em)
);

-- Fotos e evidências das aplicações de marca no evento
CREATE TABLE comprovacao_marca (
    id                 BIGSERIAL PRIMARY KEY,
    aproveitamento_id  BIGINT NOT NULL REFERENCES aproveitamento_previsto(id) ON DELETE CASCADE,
    arquivo_id         BIGINT NOT NULL REFERENCES arquivo(id),
    descricao          VARCHAR(200),
    registrado_por     BIGINT NOT NULL REFERENCES usuario(id),
    registrado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_comprovacao UNIQUE (aproveitamento_id, arquivo_id)
);

-- ---------------------------------------------------------------------
-- 5. OPERAÇÃO DO EVENTO: CORTESIAS, CREDENCIAMENTO, CONTRATO
-- ---------------------------------------------------------------------
CREATE TABLE cortesia_cota (
    id            BIGSERIAL PRIMARY KEY,
    parceria_id   BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    setor         VARCHAR(15) NOT NULL CHECK (setor IN ('ARENA','CAMAROTE','LOUNGE','PREMIUM')),
    dia           VARCHAR(10) NOT NULL CHECK (dia IN ('SEXTA','SABADO','PASSAPORTE')),
    quantidade    SMALLINT NOT NULL CHECK (quantidade > 0),
    com_shuttle   BOOLEAN NOT NULL DEFAULT FALSE,
    acesso        VARCHAR(80),
    CONSTRAINT uq_cortesia_cota UNIQUE (parceria_id, setor, dia)
);

CREATE TABLE cortesia_destinacao (
    id                  BIGSERIAL PRIMARY KEY,
    cortesia_cota_id    BIGINT NOT NULL REFERENCES cortesia_cota(id) ON DELETE CASCADE,
    destinatario_nome   VARCHAR(120) NOT NULL,
    destinatario_email  VARCHAR(254),
    quantidade          SMALLINT NOT NULL CHECK (quantidade > 0),
    registrado_por      BIGINT NOT NULL REFERENCES usuario(id),
    registrado_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE credencial_cota (
    id                  BIGSERIAL PRIMARY KEY,
    parceria_id         BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    setor               VARCHAR(20) NOT NULL CHECK (setor IN ('ARENA','CAMAROTE','BAIXO_CAMAROTE','PREMIUM',
                                                              'SALA_IMPRENSA','SALA_CONTEUDO')),
    beneficiario        VARCHAR(20) NOT NULL CHECK (beneficiario IN ('MARCA','AGENCIA','EQUIPE_CONTEUDO','GERAL')),
    quantidade_por_dia  SMALLINT NOT NULL CHECK (quantidade_por_dia > 0),
    CONSTRAINT uq_credencial_cota UNIQUE (parceria_id, setor, beneficiario)
);

CREATE TABLE contrato (
    id                 BIGSERIAL PRIMARY KEY,
    parceria_id        BIGINT NOT NULL UNIQUE REFERENCES parceria(id) ON DELETE CASCADE,
    signatario_nome    VARCHAR(120),
    signatario_email   VARCHAR(254),
    signatario_cargo   VARCHAR(80),
    informado_por      BIGINT REFERENCES usuario(id),
    arquivo_id         BIGINT REFERENCES arquivo(id),   -- contrato final assinado (só histórico)
    assinado_em        DATE
);

-- ---------------------------------------------------------------------
-- 6. PESQUISA
-- ---------------------------------------------------------------------
CREATE TABLE pesquisa (
    id                 BIGSERIAL PRIMARY KEY,
    evento_id          BIGINT NOT NULL REFERENCES evento(id),
    nome               VARCHAR(120) NOT NULL,
    escopo             VARCHAR(15) NOT NULL CHECK (escopo IN ('GERAL','ATIVACAO')),
    metodologia        VARCHAR(160),
    base_respondentes  INTEGER CHECK (base_respondentes > 0),
    nivel_confianca    NUMERIC(5,2) CHECK (nivel_confianca > 0 AND nivel_confianca < 100),
    fonte_arquivo_id   BIGINT REFERENCES arquivo(id),
    CONSTRAINT uq_pesquisa UNIQUE (evento_id, nome)
);

CREATE TABLE pesquisa_pergunta (
    id            BIGSERIAL PRIMARY KEY,
    parceria_id   BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    pesquisa_id   BIGINT REFERENCES pesquisa(id),
    ordem         SMALLINT NOT NULL CHECK (ordem BETWEEN 1 AND 3),
    texto         VARCHAR(400) NOT NULL,
    status        VARCHAR(15) NOT NULL DEFAULT 'RASCUNHO' CHECK (status IN ('RASCUNHO','ENVIADA','APROVADA')),
    enviada_por   BIGINT REFERENCES usuario(id),
    enviada_em    TIMESTAMPTZ,
    CONSTRAINT uq_pergunta UNIQUE (parceria_id, ordem)
);

-- Resultado agregado: percentual, nota ou contagem, sempre com a base que o gerou
CREATE TABLE pesquisa_resultado (
    id                 BIGSERIAL PRIMARY KEY,
    pesquisa_id        BIGINT NOT NULL REFERENCES pesquisa(id) ON DELETE CASCADE,
    parceria_id        BIGINT REFERENCES parceria(id) ON DELETE CASCADE,      -- nulo = indicador geral do festival
    pergunta_id        BIGINT REFERENCES pesquisa_pergunta(id) ON DELETE CASCADE,
    indicador          VARCHAR(120) NOT NULL,
    alternativa        VARCHAR(120),
    valor              NUMERIC(10,2) NOT NULL,
    unidade            VARCHAR(12) NOT NULL CHECK (unidade IN ('PERCENTUAL','NOTA','CONTAGEM')),
    base_respondentes  INTEGER NOT NULL CHECK (base_respondentes > 0),
    carga_id           BIGINT REFERENCES carga(id),
    CONSTRAINT ck_percentual CHECK (unidade <> 'PERCENTUAL' OR valor BETWEEN 0 AND 100)
);
CREATE UNIQUE INDEX uq_pesquisa_resultado
    ON pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa) NULLS NOT DISTINCT;

-- ---------------------------------------------------------------------
-- 7. RELATÓRIO
-- ---------------------------------------------------------------------
-- Catálogo de indicadores: regra de cálculo e origem documentadas na própria base
CREATE TABLE indicador (
    codigo        VARCHAR(40) PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    secao         VARCHAR(60)  NOT NULL,
    regra         TEXT NOT NULL,
    unidade       VARCHAR(20)  NOT NULL,
    fonte_tabelas VARCHAR(200) NOT NULL,
    cotas         VARCHAR(20)[],               -- nulo = vale para todas as cotas
    requer_previsto VARCHAR(20) CHECK (requer_previsto IN ('MIDIA','APLICACAO_MARCA'))  -- só se a cota comprou esse tipo
);

CREATE TABLE relatorio (
    id            BIGSERIAL PRIMARY KEY,
    parceria_id   BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    versao        SMALLINT NOT NULL CHECK (versao >= 1),
    status        VARCHAR(16) NOT NULL DEFAULT 'RASCUNHO' CHECK (status IN ('RASCUNHO','VALIDADO','DISPONIBILIZADO')),
    gerado_por    BIGINT NOT NULL REFERENCES usuario(id),
    gerado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    validado_por  BIGINT REFERENCES usuario(id),
    validado_em   TIMESTAMPTZ,
    arquivo_id    BIGINT REFERENCES arquivo(id),
    CONSTRAINT uq_relatorio UNIQUE (parceria_id, versao),
    CONSTRAINT ck_relatorio_validacao CHECK (status = 'RASCUNHO' OR (validado_por IS NOT NULL AND validado_em IS NOT NULL))
);

-- Fotografia dos números de cada versão, com a origem de cada valor
CREATE TABLE relatorio_indicador (
    relatorio_id      BIGINT NOT NULL REFERENCES relatorio(id) ON DELETE CASCADE,
    indicador_codigo  VARCHAR(40) NOT NULL REFERENCES indicador(codigo),
    recorte           VARCHAR(80) NOT NULL DEFAULT '',   -- ex.: unidade, veículo, pergunta
    valor             NUMERIC(16,2),                      -- nulo = indisponível (nunca estimado)
    base_calculo      VARCHAR(120),
    disponivel        BOOLEAN NOT NULL,
    PRIMARY KEY (relatorio_id, indicador_codigo, recorte),
    CONSTRAINT ck_disponivel CHECK (disponivel = (valor IS NOT NULL))
);

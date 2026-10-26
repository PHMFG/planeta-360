-- Cria as 33 tabelas do portal: as 29 da modelagem da Sprint 1, as duas áreas de carga (stg_veiculacao, stg_pesquisa)
-- e as duas novas (cota_tipo_aba, codigo_ativacao), já com as correções de estrutura. Atende o item 8 da Entrega 2.

-- Exige PostgreSQL 15 ou mais novo (NULLS NOT DISTINCT e ON DELETE SET NULL com lista de colunas).
-- Como o Flyway roda este arquivo numa transação só, não há BEGIN/COMMIT, meta-comandos do psql nem extensões.
--
-- Convenção de nomes das regras: ck_ (CHECK), uq_ (único), fk_ (chave estrangeira composta com nome próprio).
-- Os gatilhos de regra de negócio, que ficam em V2__regras.sql, usam o prefixo rn_.

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

-- [ISOLAMENTO] A parceria é a unidade de isolamento: tudo o que o patrocinador vê pende dela.
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
    -- Uma cota por marca por edição: já garantido aqui (uma marca não tem Master e Subcota na mesma edição).
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
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- [ITEM-8] Permite que parceria_usuario aponte para (id, perfil) e só aceite o perfil certo.
    CONSTRAINT uq_usuario_id_perfil UNIQUE (id, perfil)
);
CREATE UNIQUE INDEX uq_usuario_email ON usuario (lower(email));

-- [PERMISSAO] Cadastro prévio exigido pelo TAP: só e-mails autorizados criam acesso.
-- [ITEM-1] Revogar não apaga a linha: grava revogado_em/revogado_por (a trilha fica) e o gatilho
-- rn_email_revogado (V2) tira o vínculo com a parceria. Reautorizar é zerar revogado_em.
CREATE TABLE email_autorizado (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    email           VARCHAR(254) NOT NULL CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    vinculo         VARCHAR(10)  NOT NULL CHECK (vinculo IN ('MARCA','AGENCIA')),
    autorizado_por  BIGINT NOT NULL REFERENCES usuario(id),
    autorizado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revogado_por    BIGINT REFERENCES usuario(id),
    revogado_em     TIMESTAMPTZ,
    CONSTRAINT ck_revogacao_completa CHECK ((revogado_em IS NULL) = (revogado_por IS NULL))
);
CREATE UNIQUE INDEX uq_email_autorizado ON email_autorizado (parceria_id, lower(email));

-- Vínculo do usuário do patrocinador (marca ou agência) com a parceria.
-- [ITEM-8] O perfil faz parte da chave estrangeira: só ADMIN_PATROCINADOR pode ser vinculado, e o perfil
-- de um usuário vinculado não pode mais ser trocado (o banco recusa as duas coisas, sem depender da API).
CREATE TABLE parceria_usuario (
    parceria_id  BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    usuario_id   BIGINT NOT NULL,
    perfil       VARCHAR(20) NOT NULL DEFAULT 'ADMIN_PATROCINADOR'
                 CONSTRAINT ck_parceria_usuario_perfil CHECK (perfil = 'ADMIN_PATROCINADOR'),
    vinculo      VARCHAR(10) NOT NULL CHECK (vinculo IN ('MARCA','AGENCIA')),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (parceria_id, usuario_id),
    CONSTRAINT fk_parceria_usuario_so_admin_patrocinador
        FOREIGN KEY (usuario_id, perfil) REFERENCES usuario (id, perfil) ON DELETE CASCADE
);
CREATE INDEX ix_parceria_usuario_usuario ON parceria_usuario (usuario_id);

-- [PERMISSAO] Primeiro acesso: o código que a RBS repassa ao patrocinador prova que ele recebeu a autorização.
-- [PENDENTE] Na V1 não há e-mail automático; o código aparece uma vez para a RBS ao autorizar o e-mail.
-- Só o hash (SHA-256 em hexadecimal) é guardado; o código em si nunca fica no banco. Tem validade e uso único.
-- Só pode haver um código vigente por autorização: para reenviar, a API invalida o anterior (invalidado_em) antes.
CREATE TABLE codigo_ativacao (
    id                   BIGSERIAL PRIMARY KEY,
    email_autorizado_id  BIGINT NOT NULL REFERENCES email_autorizado(id) ON DELETE CASCADE,
    codigo_hash          CHAR(64) NOT NULL UNIQUE CHECK (codigo_hash ~ '^[0-9a-f]{64}$'),
    criado_em            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_em            TIMESTAMPTZ NOT NULL,
    usado_em             TIMESTAMPTZ,
    invalidado_em        TIMESTAMPTZ,        -- revogação do e-mail ou reenvio de um código novo
    tentativas           SMALLINT NOT NULL DEFAULT 0 CHECK (tentativas >= 0),   -- erros de digitação, para travar força bruta
    CONSTRAINT ck_codigo_validade CHECK (expira_em > criado_em),
    CONSTRAINT ck_codigo_desfecho CHECK (usado_em IS NULL OR invalidado_em IS NULL)
);
CREATE UNIQUE INDEX uq_codigo_vigente ON codigo_ativacao (email_autorizado_id)
    WHERE usado_em IS NULL AND invalidado_em IS NULL;

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

-- [PERMISSAO] Abas liberadas por padrão para cada tipo de cota. Quando uma parceria nasce, o gatilho
-- rn_parceria_abas_padrao (V2) copia estas linhas para parceria_aba; depois a RBS ajusta parceria a parceria.
CREATE TABLE cota_tipo_aba (
    cota_tipo_id  SMALLINT NOT NULL REFERENCES cota_tipo(id),
    aba_id        SMALLINT NOT NULL REFERENCES aba(id),
    PRIMARY KEY (cota_tipo_id, aba_id)
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
    atualizado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- [ISOLAMENTO] [ITEM-3] Alvos das chaves compostas de arquivo: o arquivo só aponta para uma entrega
    -- se a parceria e a aba dele forem as mesmas da entrega.
    CONSTRAINT uq_entrega_parceria UNIQUE (id, parceria_id),
    CONSTRAINT uq_entrega_aba UNIQUE (id, aba_id)
);
CREATE INDEX ix_entrega_parceria ON entrega (parceria_id, aba_id);

-- [UPLOAD] Repositório próprio de arquivos (sem dependência do SharePoint).
-- [ATLAS-DEPOIS] As colunas de cifra (cifrado, chave_versao) e as tabelas do Atlas entram em migração própria,
-- quando esses módulos forem ligados; nada aqui precisa mudar para recebê-las.
CREATE TABLE arquivo (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT   REFERENCES parceria(id) ON DELETE CASCADE,  -- nulo = arquivo geral da RBS
    aba_id          SMALLINT REFERENCES aba(id),
    entrega_id      BIGINT,
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
    enviado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- [ISOLAMENTO] [ITEM-3] Arquivo e entrega são sempre da mesma parceria (e da mesma aba).
    -- A chave composta é MATCH SIMPLE: com parceria_id nulo ela deixaria de valer, por isso o CHECK logo abaixo.
    -- O SET NULL lista só entrega_id: sem a lista, apagar a entrega zeraria também parceria_id e o arquivo
    -- viraria "geral da RBS", visível para quem não devia.
    CONSTRAINT fk_arquivo_entrega_parceria FOREIGN KEY (entrega_id, parceria_id)
        REFERENCES entrega (id, parceria_id) ON DELETE SET NULL (entrega_id),
    CONSTRAINT fk_arquivo_entrega_aba FOREIGN KEY (entrega_id, aba_id)
        REFERENCES entrega (id, aba_id) ON DELETE SET NULL (entrega_id),
    CONSTRAINT ck_arquivo_entrega_completo CHECK (entrega_id IS NULL OR (parceria_id IS NOT NULL AND aba_id IS NOT NULL)),
    -- Alvo das chaves compostas das tabelas que guardam um arquivo da parceria (comprovação, contrato, relatório, plano).
    CONSTRAINT uq_arquivo_parceria UNIQUE (id, parceria_id)
);
CREATE INDEX ix_arquivo_parceria ON arquivo (parceria_id, aba_id);
CREATE UNIQUE INDEX uq_arquivo_versao ON arquivo (entrega_id, versao) WHERE entrega_id IS NOT NULL;

-- [HISTORICO] Trilha de auditoria: quem fez o quê, quando e de onde. Imutável: o gatilho rn_historico_imutavel
-- (V2) recusa UPDATE e DELETE.
-- [ITEM-5] Passa a registrar também acesso (login, falha, logout), download, autorização e revogação de e-mail, a ativação do
-- acesso (primeiro acesso com o código, ATIVACAO) e o envio de planilha pela RBS (CARGA).
--  - usuario_id e entidade_id só podem ser nulos em LOGIN_FALHOU: quem erra o e-mail não corresponde a nenhum
--    usuário. Nesse caso o e-mail digitado vai em "detalhe".
--  - parceria_id não tem ON DELETE CASCADE de propósito: uma parceria com histórico não pode ser apagada
--    (encerra-se pela situacao), senão a cascata tentaria apagar linhas imutáveis.
CREATE TABLE historico (
    id              BIGSERIAL PRIMARY KEY,
    parceria_id     BIGINT REFERENCES parceria(id),
    entidade        VARCHAR(40) NOT NULL,        -- ex.: 'entrega', 'arquivo', 'usuario', 'email_autorizado'
    entidade_id     BIGINT,
    acao            VARCHAR(30) NOT NULL,
    valor_anterior  TEXT,
    valor_novo      TEXT,
    detalhe         TEXT,                        -- contexto livre (ex.: e-mail tentado no LOGIN_FALHOU)
    usuario_id      BIGINT REFERENCES usuario(id),
    ocorrido_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip              INET,
    user_agent      TEXT,
    CONSTRAINT ck_historico_acao CHECK (acao IN (
        'CRIACAO','ENVIO_ARQUIVO','NOVA_VERSAO','ALTERACAO_STATUS','EDICAO','LIBERACAO_ABA','EXCLUSAO',
        'GERACAO_RELATORIO','VALIDACAO','DISPONIBILIZACAO',
        'LOGIN','LOGIN_FALHOU','LOGOUT','DOWNLOAD','AUTORIZACAO','REVOGACAO',
        'ATIVACAO','CARGA')),
    CONSTRAINT ck_historico_usuario CHECK (usuario_id IS NOT NULL OR (acao = 'LOGIN_FALHOU' AND detalhe IS NOT NULL)),
    CONSTRAINT ck_historico_entidade CHECK (entidade_id IS NOT NULL OR acao = 'LOGIN_FALHOU')
);
CREATE INDEX ix_historico_parceria ON historico (parceria_id, ocorrido_em DESC);
CREATE INDEX ix_historico_entidade ON historico (entidade, entidade_id);
CREATE INDEX ix_historico_usuario ON historico (usuario_id, ocorrido_em DESC);

-- ---------------------------------------------------------------------
-- 4. MÍDIA E APLICAÇÕES DE MARCA: PREVISTO x REALIZADO
-- ---------------------------------------------------------------------
-- [CARGA] Cargas de planilhas (mídia, pesquisa, comprovações): base da consolidação.
-- evento_id e processada_em são preenchidos pela função de carga; processada_em impede processar a mesma carga duas vezes.
CREATE TABLE carga (
    id                  BIGSERIAL PRIMARY KEY,
    tipo                VARCHAR(20) NOT NULL CHECK (tipo IN ('MIDIA','PESQUISA','COMPROVACAO','PLANO_COMERCIAL')),
    arquivo_id          BIGINT REFERENCES arquivo(id),
    evento_id           BIGINT REFERENCES evento(id),
    executada_por       BIGINT NOT NULL REFERENCES usuario(id),
    executada_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processada_em       TIMESTAMPTZ,
    linhas_lidas        INTEGER NOT NULL DEFAULT 0,
    linhas_inseridas    INTEGER NOT NULL DEFAULT 0,
    linhas_duplicadas   INTEGER NOT NULL DEFAULT 0,
    linhas_rejeitadas   INTEGER NOT NULL DEFAULT 0
);

-- [VALIDACAO] Linhas que não entraram na base, com o motivo (faltantes e inconsistências)
CREATE TABLE carga_rejeicao (
    id        BIGSERIAL PRIMARY KEY,
    carga_id  BIGINT  NOT NULL REFERENCES carga(id) ON DELETE CASCADE,
    linha     INTEGER NOT NULL,
    campo     VARCHAR(60),
    motivo    VARCHAR(200) NOT NULL,
    conteudo  TEXT
);
CREATE INDEX ix_carga_rejeicao_carga ON carga_rejeicao (carga_id, linha);

-- Catálogo do que a cota comprou (uma linha por veículo + formato + espaço)
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
    fonte_arquivo_id    BIGINT,
    fonte_pagina        SMALLINT,
    CONSTRAINT ck_aprov_tipo CHECK (categoria = 'APLICACAO_MARCA' OR tipo_midia IS NOT NULL),
    CONSTRAINT uq_aprov_parceria UNIQUE (id, parceria_id),
    -- [ISOLAMENTO] [ITEM-3] O PDF do plano comercial citado como fonte é da mesma parceria.
    CONSTRAINT fk_aprov_fonte_parceria FOREIGN KEY (fonte_arquivo_id, parceria_id)
        REFERENCES arquivo (id, parceria_id)
);
CREATE INDEX ix_aprov_parceria ON aproveitamento_previsto (parceria_id, categoria);
-- [DUPLICIDADE] Chave natural: o relatório de veiculação chega sem IDs internos. A janela (coluna "Período"
-- do plano) NÃO entra na chave; já era assim na modelagem e continua (decisão a confirmar no docs/banco.md).
CREATE UNIQUE INDEX uq_aprov_chave_natural ON aproveitamento_previsto
    (parceria_id, lower(veiculo), lower(formato), lower(coalesce(espaco_programa, '')));

-- [CARGA] Área de carga: linhas como chegam do relatório de veiculação, antes do vínculo.
-- Colunas na mesma ordem do CSV de docs/exemplos/veiculacao.csv; a API grava "carga_id" e "linha".
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

-- [CARGA] Área de carga dos resultados agregados da pesquisa, no formato de docs/exemplos/pesquisa.csv.
-- Valor, unidade e base chegam já convertidos pela API; o que não converter fica nulo e a função rejeita a linha.
CREATE TABLE stg_pesquisa (
    carga_id           BIGINT  NOT NULL REFERENCES carga(id) ON DELETE CASCADE,
    linha              INTEGER NOT NULL,
    patrocinador       VARCHAR(120),      -- vazio na pesquisa geral do festival
    pergunta           SMALLINT,          -- 1 a 3; vazio na pesquisa geral
    indicador          VARCHAR(120),      -- obrigatório na pesquisa geral; na da ativação o indicador é a pergunta
    alternativa        VARCHAR(120),
    valor              NUMERIC(10,2),
    unidade            VARCHAR(12),
    base_respondentes  INTEGER,
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
    -- [DUPLICIDADE] Chave natural: a mesma inserção não pode ser contada duas vezes.
    CONSTRAINT uq_veiculacao UNIQUE (aproveitamento_id, exibido_em)
);
CREATE INDEX ix_veiculacao_carga ON veiculacao (carga_id);

-- Fotos e evidências das aplicações de marca no evento.
-- [ITEM-3] parceria_id é novo: sem ele o banco não conseguiria garantir que a foto é da mesma parceria da aplicação.
-- O gatilho rn_comprovacao_valida (V2) preenche parceria_id a partir da aplicação, então a API não precisa informá-lo.
CREATE TABLE comprovacao_marca (
    id                 BIGSERIAL PRIMARY KEY,
    aproveitamento_id  BIGINT NOT NULL,
    parceria_id        BIGINT NOT NULL,
    arquivo_id         BIGINT NOT NULL,
    descricao          VARCHAR(200),
    registrado_por     BIGINT NOT NULL REFERENCES usuario(id),
    registrado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_comprovacao UNIQUE (aproveitamento_id, arquivo_id),
    CONSTRAINT fk_comprovacao_aplicacao FOREIGN KEY (aproveitamento_id, parceria_id)
        REFERENCES aproveitamento_previsto (id, parceria_id) ON DELETE CASCADE,
    CONSTRAINT fk_comprovacao_arquivo_parceria FOREIGN KEY (arquivo_id, parceria_id)
        REFERENCES arquivo (id, parceria_id)
);
CREATE INDEX ix_comprovacao_arquivo ON comprovacao_marca (arquivo_id);

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

-- [ITEM-6] A soma das destinações de cada cota não passa da quantidade da cota: gatilho rn_cortesia_limite (V2).
CREATE TABLE cortesia_destinacao (
    id                  BIGSERIAL PRIMARY KEY,
    cortesia_cota_id    BIGINT NOT NULL REFERENCES cortesia_cota(id) ON DELETE CASCADE,
    destinatario_nome   VARCHAR(120) NOT NULL,
    destinatario_email  VARCHAR(254),
    quantidade          SMALLINT NOT NULL CHECK (quantidade > 0),
    registrado_por      BIGINT NOT NULL REFERENCES usuario(id),
    registrado_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cortesia_destinacao_cota ON cortesia_destinacao (cortesia_cota_id);

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
    -- Um signatário por contrato: já garantido (uma linha por parceria, com uma só pessoa).
    parceria_id        BIGINT NOT NULL UNIQUE REFERENCES parceria(id) ON DELETE CASCADE,
    signatario_nome    VARCHAR(120),
    signatario_email   VARCHAR(254),
    signatario_cargo   VARCHAR(80),
    informado_por      BIGINT REFERENCES usuario(id),
    arquivo_id         BIGINT,                            -- contrato final assinado (só histórico)
    assinado_em        DATE,
    -- [ISOLAMENTO] [ITEM-3] O contrato assinado é um arquivo da mesma parceria.
    CONSTRAINT fk_contrato_arquivo_parceria FOREIGN KEY (arquivo_id, parceria_id)
        REFERENCES arquivo (id, parceria_id)
);

-- ---------------------------------------------------------------------
-- 6. PESQUISA
-- ---------------------------------------------------------------------
-- fonte_arquivo_id deve ser arquivo geral da RBS (parceria nula), porque a pesquisa vale para a edição inteira:
-- regra no gatilho rn_pesquisa_fonte_geral (V2), já que a chave composta não consegue comparar com "nulo".
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
    CONSTRAINT uq_pergunta UNIQUE (parceria_id, ordem),
    -- [ISOLAMENTO] [ITEM-3] Alvos das chaves compostas de pesquisa_resultado.
    CONSTRAINT uq_pergunta_parceria UNIQUE (id, parceria_id),
    CONSTRAINT uq_pergunta_pesquisa UNIQUE (id, pesquisa_id)
);

-- Resultado agregado: percentual, nota ou contagem, sempre com a base que o gerou
CREATE TABLE pesquisa_resultado (
    id                 BIGSERIAL PRIMARY KEY,
    pesquisa_id        BIGINT NOT NULL REFERENCES pesquisa(id) ON DELETE CASCADE,
    parceria_id        BIGINT REFERENCES parceria(id) ON DELETE CASCADE,      -- nulo = indicador geral do festival
    pergunta_id        BIGINT,
    indicador          VARCHAR(120) NOT NULL,
    alternativa        VARCHAR(120),
    valor              NUMERIC(10,2) NOT NULL,
    unidade            VARCHAR(12) NOT NULL CHECK (unidade IN ('PERCENTUAL','NOTA','CONTAGEM')),
    base_respondentes  INTEGER NOT NULL CHECK (base_respondentes > 0),
    carga_id           BIGINT REFERENCES carga(id),
    CONSTRAINT ck_percentual CHECK (unidade <> 'PERCENTUAL' OR valor BETWEEN 0 AND 100),
    -- [ISOLAMENTO] [ITEM-3] O resultado pertence à parceria e à pesquisa da própria pergunta. Pelo mesmo cuidado
    -- do arquivo (chave composta não vale com parceria nula), o CHECK exige parceria sempre que há pergunta.
    CONSTRAINT ck_resultado_pergunta_parceria CHECK (pergunta_id IS NULL OR parceria_id IS NOT NULL),
    CONSTRAINT fk_resultado_pergunta_parceria FOREIGN KEY (pergunta_id, parceria_id)
        REFERENCES pesquisa_pergunta (id, parceria_id) ON DELETE CASCADE,
    CONSTRAINT fk_resultado_pergunta_pesquisa FOREIGN KEY (pergunta_id, pesquisa_id)
        REFERENCES pesquisa_pergunta (id, pesquisa_id) ON DELETE CASCADE
);
-- [DUPLICIDADE] Reenviar o mesmo resultado não cria uma segunda linha (nulos contam como iguais).
CREATE UNIQUE INDEX uq_pesquisa_resultado
    ON pesquisa_resultado (pesquisa_id, parceria_id, pergunta_id, indicador, alternativa) NULLS NOT DISTINCT;
CREATE INDEX ix_resultado_pergunta ON pesquisa_resultado (pergunta_id);

-- ---------------------------------------------------------------------
-- 7. RELATÓRIO
-- ---------------------------------------------------------------------
-- [RELATORIO] Catálogo de indicadores: regra de cálculo e origem documentadas na própria base
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

-- [ITEM-7] O relatório só segue RASCUNHO -> VALIDADO -> DISPONIBILIZADO (gatilho rn_relatorio_ordem_status, V2).
-- Cada etapa guarda quem fez e quando; os CHECKs abaixo impedem etapa preenchida fora do status certo.
CREATE TABLE relatorio (
    id                  BIGSERIAL PRIMARY KEY,
    parceria_id         BIGINT NOT NULL REFERENCES parceria(id) ON DELETE CASCADE,
    versao              SMALLINT NOT NULL CHECK (versao >= 1),
    status              VARCHAR(16) NOT NULL DEFAULT 'RASCUNHO' CHECK (status IN ('RASCUNHO','VALIDADO','DISPONIBILIZADO')),
    gerado_por          BIGINT NOT NULL REFERENCES usuario(id),
    gerado_em           TIMESTAMPTZ NOT NULL DEFAULT now(),
    validado_por        BIGINT REFERENCES usuario(id),
    validado_em         TIMESTAMPTZ,
    disponibilizado_por BIGINT REFERENCES usuario(id),
    disponibilizado_em  TIMESTAMPTZ,
    arquivo_id          BIGINT,
    CONSTRAINT uq_relatorio UNIQUE (parceria_id, versao),
    CONSTRAINT ck_relatorio_validacao CHECK (
        (status = 'RASCUNHO' AND validado_por IS NULL AND validado_em IS NULL)
        OR (status <> 'RASCUNHO' AND validado_por IS NOT NULL AND validado_em IS NOT NULL)),
    CONSTRAINT ck_relatorio_disponibilizacao CHECK (
        (status = 'DISPONIBILIZADO' AND disponibilizado_por IS NOT NULL AND disponibilizado_em IS NOT NULL)
        OR (status <> 'DISPONIBILIZADO' AND disponibilizado_por IS NULL AND disponibilizado_em IS NULL)),
    -- [ISOLAMENTO] [ITEM-3] O arquivo do relatório (PDF extraído) é da mesma parceria.
    CONSTRAINT fk_relatorio_arquivo_parceria FOREIGN KEY (arquivo_id, parceria_id)
        REFERENCES arquivo (id, parceria_id)
);

-- [RELATORIO] Fotografia dos números de cada versão, com a origem de cada valor.
-- [ITEM-2] recorte e base_calculo são TEXT: os rótulos montados pela geração (veículo + unidade, pergunta +
-- alternativa, fonte do festival) passavam de 80 e 120 caracteres e derrubavam o relatório inteiro.
CREATE TABLE relatorio_indicador (
    relatorio_id      BIGINT NOT NULL REFERENCES relatorio(id) ON DELETE CASCADE,
    indicador_codigo  VARCHAR(40) NOT NULL REFERENCES indicador(codigo),
    recorte           TEXT NOT NULL DEFAULT '',          -- ex.: unidade, veículo, pergunta
    valor             NUMERIC(16,2),                      -- nulo = indisponível (nunca estimado)
    base_calculo      TEXT,
    disponivel        BOOLEAN NOT NULL,
    PRIMARY KEY (relatorio_id, indicador_codigo, recorte),
    CONSTRAINT ck_disponivel CHECK (disponivel = (valor IS NOT NULL))
);

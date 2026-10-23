-- Dados que o portal precisa em qualquer ambiente, inclusive produção: tipos de cota, as 7 abas, as abas liberadas por
-- cota, o catálogo de 17 indicadores do relatório e os eventos Planeta Atlântida 2026 e 2027. Atende o item 8 da Entrega 2.

-- O evento é dado base (e não amostra) porque parceria.evento_id é obrigatório: sem evento não se cadastra parceria.

-- ---------------------------------------------------------------------
-- Tipos de cota
-- ---------------------------------------------------------------------
INSERT INTO cota_tipo (id, codigo, nome, nivel) VALUES
 (1, 'MASTER',    'Master',    3),
 (2, 'SUBCOTA_G', 'Subcota G', 2),
 (3, 'SUBCOTA',   'Subcota',   1);

-- ---------------------------------------------------------------------
-- As 7 abas do menu
-- ---------------------------------------------------------------------
INSERT INTO aba (id, codigo, nome, ordem, prioridade) VALUES
 (1, 'PERFIL',         'Perfil do cliente',            1, 'ESSENCIAL'),
 (2, 'APLICACOES',     'Aplicações de marca no evento', 2, 'DESEJAVEL'),
 (3, 'MIDIA',          'Mídia e conteúdo',             3, 'ESSENCIAL'),
 (4, 'CORTESIAS',      'Cortesias',                    4, 'DESEJAVEL'),
 (5, 'CREDENCIAMENTO', 'Credenciamento',               5, 'DESEJAVEL'),
 (6, 'PESQUISA',       'Pesquisa da ativação',         6, 'ESSENCIAL'),
 (7, 'CONTRATO',       'Contrato',                     7, 'DESEJAVEL');

-- ---------------------------------------------------------------------
-- [PERMISSAO] [PENDENTE] a confirmar com a RBS: abas liberadas por tipo de cota.
-- Decisão do grupo enquanto a RBS não responde: todas as abas para todas as cotas, exceto PESQUISA, só para a Master.
-- (A Matriz de permissões do grupo, a amostra da Sprint 1 e o código antigo discordavam entre si; esta é a única
-- fonte a partir de agora. Para mudar, basta alterar estas linhas: o gatilho rn_parceria_abas_padrao as copia para
-- cada parceria nova. Parceria já criada se ajusta pelo parceria_aba.)
-- ---------------------------------------------------------------------
INSERT INTO cota_tipo_aba (cota_tipo_id, aba_id)
SELECT ct.id, a.id
FROM cota_tipo ct CROSS JOIN aba a
WHERE a.codigo <> 'PESQUISA' OR ct.codigo = 'MASTER';

-- ---------------------------------------------------------------------
-- [RELATORIO] Catálogo de indicadores: regra de cálculo e origem de cada número do relatório.
-- cotas = nulo vale para todas; os dois indicadores de pesquisa valem só para a Master.
-- [PENDENTE] a confirmar com a RBS: a pesquisa só para a cota Master (a mesma decisão de cota_tipo_aba acima; se mudar,
-- ajustar também a coluna "cotas" destes dois indicadores e a visão vw_faltantes).
-- ---------------------------------------------------------------------
INSERT INTO indicador (codigo, nome, secao, regra, unidade, fonte_tabelas, cotas, requer_previsto) VALUES
 ('ENTREGAS_CONCLUIDAS_PCT','Entregas concluídas','Cumprimento das entregas','Entregas com status Aprovado ÷ (total − Não aplicável) × 100','%','entrega',NULL,NULL),
 ('ENTREGAS_PENDENTES','Entregas pendentes','Cumprimento das entregas','Contagem de entregas fora de Aprovado e Não aplicável','entregas','entrega',NULL,NULL),
 ('MIDIA_PREVISTO','Mídia prevista','Mídia: previsto x exibido','Soma de quantidade_prevista por unidade (nunca soma inserções com CPM)','por unidade','aproveitamento_previsto',NULL,'MIDIA'),
 ('MIDIA_EXIBIDO','Mídia exibida','Mídia: previsto x exibido','Soma de veiculacao.quantidade por unidade; cada exibição conta uma vez (chave aproveitamento + data/hora)','por unidade','veiculacao',NULL,'MIDIA'),
 ('MIDIA_ENTREGA_PCT','Entrega de mídia','Mídia: previsto x exibido','Exibido ÷ previsto × 100, por unidade','%','aproveitamento_previsto, veiculacao',NULL,'MIDIA'),
 ('MIDIA_VEICULO_PCT','Entrega por veículo','Mídia: previsto x exibido','Exibido ÷ previsto × 100, por veículo e unidade','%','aproveitamento_previsto, veiculacao',NULL,'MIDIA'),
 ('APLICACOES_PREVISTAS','Aplicações de marca previstas','Ativação no evento','Soma das aplicações do plano comercial','aplicações','aproveitamento_previsto',NULL,'APLICACAO_MARCA'),
 ('APLICACOES_COMPROVADAS','Aplicações comprovadas','Ativação no evento','Por linha do plano, mínimo entre previsto e fotos enviadas; depois soma','aplicações','aproveitamento_previsto, comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('APLICACOES_COMPROVADAS_PCT','Aplicações comprovadas (%)','Ativação no evento','Aplicações comprovadas ÷ previstas × 100','%','aproveitamento_previsto, comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('COMPROVACOES_ENVIADAS','Fotos de comprovação','Ativação no evento','Contagem de arquivos de comprovação','arquivos','comprovacao_marca',NULL,'APLICACAO_MARCA'),
 ('PESQUISA_BASE','Base da pesquisa da ativação','Pesquisa da ativação','Maior base de respondentes registrada nos resultados','respondentes','pesquisa_resultado','{MASTER}',NULL),
 ('PESQUISA_RESULTADO','Resultado por pergunta','Pesquisa da ativação','Valor por alternativa, sempre com a base de respondentes','% ou nota','pesquisa_resultado, pesquisa_pergunta','{MASTER}',NULL),
 ('CORTESIAS_DISPONIVEIS','Cortesias disponíveis','Cortesias e credenciamento','Soma por setor e dia','ingressos','cortesia_cota',NULL,NULL),
 ('CORTESIAS_DESTINADAS_PCT','Cortesias destinadas','Cortesias e credenciamento','Destinadas ÷ disponíveis × 100','%','cortesia_cota, cortesia_destinacao',NULL,NULL),
 ('CREDENCIAIS_DIA','Credenciais de serviço por dia','Cortesias e credenciamento','Quantidade do plano por setor e beneficiário','credenciais','credencial_cota',NULL,NULL),
 ('FESTIVAL_NUMERO','O festival em números','O festival em números','Valor carregado pela RBS para a edição','variável','evento_resultado',NULL,NULL),
 ('FESTIVAL_PESQUISA','Pesquisa geral do festival','O festival em números','Resultado da pesquisa geral, com base de respondentes','%','pesquisa, pesquisa_resultado',NULL,NULL);

-- ---------------------------------------------------------------------
-- Eventos. Os números de cada edição (evento_resultado) são carregados pela RBS; a edição 2026 de demonstração
-- está na amostra de desenvolvimento.
-- ---------------------------------------------------------------------
INSERT INTO evento (nome, edicao, data_inicio, data_fim, local) VALUES
 ('Planeta Atlântida', 2026, '2026-01-30', '2026-01-31', 'Saba, Atlântida (RS)'),
 ('Planeta Atlântida', 2027, NULL, NULL, 'Saba, Atlântida (RS)');   -- [PENDENTE] datas de 2027 a confirmar com a RBS

-- Correção da segunda rodada de testes nos percentuais. Atende os itens 5 e 6 da Entrega 2.
-- A V1 a V5 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
-- Um critério só para todo percentual calculado pelo banco: cortar na primeira casa, nunca arredondar.

-- ---------------------------------------------------------------------
-- UM CRITÉRIO SÓ PARA OS PERCENTUAIS
-- ---------------------------------------------------------------------
-- [RELATORIO] A V4 cortava só o percentual de entrega da mídia; entregas, aplicações e cortesias ainda arredondavam
-- (17 de 19 aparecia como 89,5 ao lado de 99,2 cortado). Agora todos cortam na primeira casa, para nenhum número parecer
-- maior do que é: 17 de 19 vale 89,4. Passar de 100 continua valendo (exibido acima do previsto, por exemplo 197,2).
-- As visões abaixo mudam só a função do percentual (mesmas colunas, mesmos tipos).
CREATE OR REPLACE VIEW vw_entregas_resumo AS
SELECT parceria_id,
       count(*)                                              AS total,
       count(*) FILTER (WHERE status = 'NAO_APLICAVEL')      AS nao_aplicaveis,
       count(*) FILTER (WHERE status = 'APROVADO')           AS aprovadas,
       count(*) FILTER (WHERE status NOT IN ('APROVADO','NAO_APLICAVEL')) AS pendentes,
       fn_pct_truncado(100.0 * count(*) FILTER (WHERE status = 'APROVADO')
             / nullif(count(*) FILTER (WHERE status <> 'NAO_APLICAVEL'), 0)) AS pct_concluidas
FROM entrega GROUP BY parceria_id;

CREATE OR REPLACE VIEW vw_indicadores_parceria AS
SELECT parceria_id, 'ENTREGAS_CONCLUIDAS_PCT' AS indicador_codigo, '' AS recorte, pct_concluidas AS valor,
       fn_numero_br(aprovadas) || ' aprovadas de ' || fn_numero_br(total - nao_aplicaveis) || ' aplicáveis' AS base_calculo
FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'ENTREGAS_PENDENTES', '', pendentes, fn_numero_br(total) || ' entregas cadastradas' FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'MIDIA_PREVISTO', unidade, previsto, 'soma do plano comercial' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_EXIBIDO', unidade, exibido, 'veiculações sem duplicidade' FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_ENTREGA_PCT', unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto) FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_VEICULO_PCT', veiculo || ' · ' || unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto) FROM vw_midia_veiculo
UNION ALL
SELECT parceria_id, 'APLICACOES_PREVISTAS', '', previstas, 'plano comercial' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS', '', comprovadas, fn_numero_br(fotos) || ' fotos enviadas' FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS_PCT', '', fn_pct_truncado(100 * comprovadas / previstas),
       fn_numero_br(comprovadas) || ' de ' || fn_numero_br(previstas) FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'COMPROVACOES_ENVIADAS', '', fotos, 'arquivos de comprovação' FROM vw_aplicacoes
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_BASE', '', max(r.base_respondentes), string_agg(DISTINCT p.nome, '; ')
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id JOIN pesquisa p ON p.id = r.pesquisa_id
GROUP BY pp.parceria_id
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_RESULTADO', 'P' || pp.ordem || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes (' || lower(r.unidade) || ')'
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id
UNION ALL
SELECT parceria_id, 'CORTESIAS_DISPONIVEIS', '', disponiveis, 'soma por setor e dia' FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CORTESIAS_DESTINADAS_PCT', '', fn_pct_truncado(100.0 * destinadas / nullif(disponiveis, 0)),
       fn_numero_br(destinadas) || ' de ' || fn_numero_br(disponiveis) FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CREDENCIAIS_DIA', setor || ' · ' || beneficiario, quantidade_por_dia, 'plano comercial'
FROM credencial_cota
UNION ALL
SELECT pa.id, 'FESTIVAL_NUMERO', er.indicador, er.valor,
       CASE er.qualificador WHEN 'MAIS_DE' THEN 'mais de; ' ELSE '' END || er.fonte
FROM parceria pa JOIN evento_resultado er ON er.evento_id = pa.evento_id
UNION ALL
SELECT pa.id, 'FESTIVAL_PESQUISA', r.indicador || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes'
FROM parceria pa JOIN pesquisa p ON p.evento_id = pa.evento_id AND p.escopo = 'GERAL'
JOIN pesquisa_resultado r ON r.pesquisa_id = p.id AND r.parceria_id IS NULL;

-- Com as duas visões trocadas, nada mais usa o arredondamento: sai de cena para o critério não voltar por engano.
DROP FUNCTION fn_pct_arredondado(NUMERIC);

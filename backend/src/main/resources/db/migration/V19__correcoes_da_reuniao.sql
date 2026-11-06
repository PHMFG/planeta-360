-- V19: o banco dos pedidos da reunião de 05/11 que são correção (fase F1 do plano da extração).
-- Só acrescenta: a unidade do recorte no relatório (BG2) e o atendimento da parceria (U7). Nenhuma V antiga é editada.
-- A amostra não muda: as linhas de relatório que já existem ficam com a unidade nula, e nenhuma parceria tem atendimento.

-- ---------------------------------------------------------------------
-- 1. A unidade do recorte no relatório (BG2)
-- ---------------------------------------------------------------------
-- [RELATORIO] O catálogo (indicador.unidade) diz a unidade do indicador, mas o recorte pode ter outra: uma linha de
-- evento_resultado traz a própria unidade ("pessoas", "marcas", "kg"...). A coluna guarda a do recorte quando ela não é a
-- do catálogo; nula, vale a do catálogo. A API lê a coluna a partir da trilha do relatório (F1).
ALTER TABLE relatorio_indicador ADD COLUMN unidade TEXT;

-- A visão da V6, sem mudar nada do que existe, mais a coluna unidade no fim: só o FESTIVAL_NUMERO leva a unidade que a
-- RBS digitou na linha de evento_resultado; todos os outros indicadores ficam com nulo (a unidade do catálogo).
CREATE OR REPLACE VIEW vw_indicadores_parceria AS
SELECT parceria_id, 'ENTREGAS_CONCLUIDAS_PCT' AS indicador_codigo, '' AS recorte, pct_concluidas AS valor,
       fn_numero_br(aprovadas) || ' aprovadas de ' || fn_numero_br(total - nao_aplicaveis) || ' aplicáveis' AS base_calculo,
       NULL::text AS unidade
FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'ENTREGAS_PENDENTES', '', pendentes, fn_numero_br(total) || ' entregas cadastradas', NULL
FROM vw_entregas_resumo
UNION ALL
SELECT parceria_id, 'MIDIA_PREVISTO', unidade, previsto, 'soma do plano comercial', NULL FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_EXIBIDO', unidade, exibido, 'veiculações sem duplicidade', NULL FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_ENTREGA_PCT', unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto), NULL FROM vw_midia_unidade
UNION ALL
SELECT parceria_id, 'MIDIA_VEICULO_PCT', veiculo || ' · ' || unidade, pct_entrega, fn_numero_br(exibido) || ' de ' || fn_numero_br(previsto), NULL FROM vw_midia_veiculo
UNION ALL
SELECT parceria_id, 'APLICACOES_PREVISTAS', '', previstas, 'plano comercial', NULL FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS', '', comprovadas, fn_numero_br(fotos) || ' fotos enviadas', NULL FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'APLICACOES_COMPROVADAS_PCT', '', fn_pct_truncado(100 * comprovadas / previstas),
       fn_numero_br(comprovadas) || ' de ' || fn_numero_br(previstas), NULL FROM vw_aplicacoes
UNION ALL
SELECT parceria_id, 'COMPROVACOES_ENVIADAS', '', fotos, 'arquivos de comprovação', NULL FROM vw_aplicacoes
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_BASE', '', max(r.base_respondentes), string_agg(DISTINCT p.nome, '; '), NULL
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id JOIN pesquisa p ON p.id = r.pesquisa_id
GROUP BY pp.parceria_id
UNION ALL
SELECT pp.parceria_id, 'PESQUISA_RESULTADO', 'P' || pp.ordem || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes (' || lower(r.unidade) || ')', NULL
FROM pesquisa_resultado r JOIN pesquisa_pergunta pp ON pp.id = r.pergunta_id
UNION ALL
SELECT parceria_id, 'CORTESIAS_DISPONIVEIS', '', disponiveis, 'soma por setor e dia', NULL FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CORTESIAS_DESTINADAS_PCT', '', fn_pct_truncado(100.0 * destinadas / nullif(disponiveis, 0)),
       fn_numero_br(destinadas) || ' de ' || fn_numero_br(disponiveis), NULL FROM vw_cortesias
UNION ALL
SELECT parceria_id, 'CREDENCIAIS_DIA', setor || ' · ' || beneficiario, quantidade_por_dia, 'plano comercial', NULL
FROM credencial_cota
UNION ALL
SELECT pa.id, 'FESTIVAL_NUMERO', er.indicador, er.valor,
       CASE er.qualificador WHEN 'MAIS_DE' THEN 'mais de; ' ELSE '' END || er.fonte, er.unidade
FROM parceria pa JOIN evento_resultado er ON er.evento_id = pa.evento_id
UNION ALL
SELECT pa.id, 'FESTIVAL_PESQUISA', r.indicador || coalesce(' · ' || r.alternativa, ''), r.valor,
       'base de ' || fn_numero_br(r.base_respondentes) || ' respondentes', NULL
FROM parceria pa JOIN pesquisa p ON p.evento_id = pa.evento_id AND p.escopo = 'GERAL'
JOIN pesquisa_resultado r ON r.pesquisa_id = p.id AND r.parceria_id IS NULL;

-- A função da V5, sem mudar nada do que ela faz, copiando também a unidade da visão. O segundo INSERT (os indicadores sem
-- linha) continua sem unidade, porque ali vale a do catálogo.
CREATE OR REPLACE FUNCTION fn_gerar_relatorio(p_parceria_id BIGINT, p_usuario_id BIGINT, p_em TIMESTAMPTZ DEFAULT now())
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE v_rel BIGINT; v_versao SMALLINT; v_cota VARCHAR(20); v_em TIMESTAMPTZ := coalesce(p_em, now());
BEGIN
    -- Trava a parceria: duas gerações ao mesmo tempo esperam uma pela outra e ganham versões diferentes.
    SELECT ct.codigo INTO v_cota
    FROM parceria pa JOIN cota_tipo ct ON ct.id = pa.cota_tipo_id
    WHERE pa.id = p_parceria_id
    FOR NO KEY UPDATE OF pa;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'A parceria % não existe.', p_parceria_id
            USING ERRCODE = 'foreign_key_violation', CONSTRAINT = 'rn_parceria_inexistente';
    END IF;

    SELECT coalesce(max(versao), 0) + 1 INTO v_versao FROM relatorio WHERE parceria_id = p_parceria_id;

    INSERT INTO relatorio (parceria_id, versao, gerado_por, gerado_em) VALUES (p_parceria_id, v_versao, p_usuario_id, v_em)
    RETURNING id INTO v_rel;

    INSERT INTO relatorio_indicador (relatorio_id, indicador_codigo, recorte, valor, base_calculo, disponivel, unidade)
    SELECT v_rel, indicador_codigo, recorte, valor, base_calculo, valor IS NOT NULL, unidade
    FROM vw_indicadores_parceria WHERE parceria_id = p_parceria_id;

    -- Todo indicador do catálogo sem linha: registrado como indisponível, com o motivo, nunca estimado
    INSERT INTO relatorio_indicador (relatorio_id, indicador_codigo, recorte, valor, base_calculo, disponivel)
    SELECT v_rel, i.codigo, '', NULL,
           CASE WHEN i.cotas IS NOT NULL AND NOT (v_cota = ANY (i.cotas))
                    THEN 'não se aplica a esta cota'
                WHEN i.requer_previsto IS NOT NULL AND NOT EXISTS (SELECT 1 FROM aproveitamento_previsto ap
                                                                   WHERE ap.parceria_id = p_parceria_id
                                                                     AND ap.categoria = i.requer_previsto)
                    THEN CASE i.requer_previsto WHEN 'MIDIA' THEN 'sem mídia no plano comercial'
                                                ELSE 'sem aplicações de marca no plano comercial' END
                ELSE 'sem registro na base' END,
           FALSE
    FROM indicador i
    WHERE NOT EXISTS (SELECT 1 FROM relatorio_indicador ri WHERE ri.relatorio_id = v_rel AND ri.indicador_codigo = i.codigo);

    INSERT INTO historico (parceria_id, entidade, entidade_id, acao, valor_novo, usuario_id, ocorrido_em)
    VALUES (p_parceria_id, 'relatorio', v_rel, 'GERACAO_RELATORIO', 'versão ' || v_versao || ' (RASCUNHO)', p_usuario_id, v_em);

    RETURN v_rel;
END $$;

-- ---------------------------------------------------------------------
-- 2. O atendimento da parceria (U7)
-- ---------------------------------------------------------------------
-- [PERMISSAO] Quem fala com a marca. A RBS edita no Perfil e o FAQ da marca mostra o nome e o e-mail no fim. O CHECK de
-- formato é o mesmo do e-mail do signatário (V16); ninguém é obrigado a preencher os dois.
ALTER TABLE parceria
    ADD COLUMN atendimento_nome VARCHAR(120),
    ADD COLUMN atendimento_email VARCHAR(254),
    ADD CONSTRAINT ck_parceria_atendimento_email
        CHECK (atendimento_email IS NULL OR atendimento_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$');

-- Correção da segunda rodada de testes no relatório. Atende o item 6 da Entrega 2.
-- A V1 a V4 já rodaram em outros bancos e não se editam: tudo o que muda mora aqui.
-- O relatório sempre traz as 6 seções e os 17 indicadores do catálogo (V3): o que não tem dado sai indisponível.

-- ---------------------------------------------------------------------
-- RELATÓRIO COMPLETO
-- ---------------------------------------------------------------------
-- [RELATORIO] Até aqui a geração só registrava como indisponível o indicador que "valia" para a cota e para o plano comercial
-- (indicador.cotas e indicador.requer_previsto). Uma parceria sem previsto (cadastrada ao vivo) perdia as seções de mídia e
-- de ativação, e uma cota sem pesquisa perdia a pesquisa, sem aviso nenhum. Agora todo indicador do catálogo que não ganhou
-- linha das visões entra como indisponível (disponivel = FALSE, valor nulo: nunca zero e nunca estimado), uma linha só e
-- sem recorte. Os indicadores que vêm por recorte (unidade, veículo, pergunta, setor) mantêm uma linha por recorte quando
-- há dado; sem nenhum, ficam com essa linha única sem recorte. O texto de base_calculo diz por que não há número.
-- Mesma função da V2; muda só o segundo INSERT.
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

    INSERT INTO relatorio_indicador (relatorio_id, indicador_codigo, recorte, valor, base_calculo, disponivel)
    SELECT v_rel, indicador_codigo, recorte, valor, base_calculo, valor IS NOT NULL
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

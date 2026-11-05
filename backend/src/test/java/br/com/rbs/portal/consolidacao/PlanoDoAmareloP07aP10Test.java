package br.com.rbs.portal.consolidacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.rbs.portal.TesteDeIntegracao;
import br.com.rbs.portal.acesso.SessaoDeApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * [R1.4] O exemplo {@code docs/exemplos/plano-amarelo-p07-p10.csv} traz as páginas 7 a 10 do plano do Amarelo (mídia e conteúdo,
 * redes sociais, display, conteúdo integrado e transmissão oficial), que ficam fora da amostra para não mudar as contagens dela. A RBS
 * as carrega ao vivo pela tela Cargas, Plano comercial; aqui a carga roda pela mesma rota, dentro de uma transação desfeita no fim.
 */
@TesteDeIntegracao
@Transactional
class PlanoDoAmareloP07aP10Test extends ApoioDeCargas {

    @Test
    void csvDasPaginas7a10EntraSemRejeicao() {
        long amarelo = SessaoDeApi.parceriaDe2027(jdbc, "Cliente Amarelo");
        long antes = contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ?", amarelo);
        SessaoDeApi rbs = entrar(RBS);

        MvcTestResult resposta = enviar(rbs, "plano-amarelo-p07-p10.csv", exemplo("plano-amarelo-p07-p10.csv"), "PLANO", evento(2027), null);

        assertThat(resposta).hasStatus(HttpStatus.CREATED);
        assertThat(inteiro(resposta, "$.linhasLidas")).isEqualTo(36);
        assertThat(inteiro(resposta, "$.linhasInseridas")).isEqualTo(36);
        assertThat(inteiro(resposta, "$.linhasDuplicadas")).isZero();
        assertThat(inteiro(resposta, "$.linhasRejeitadas")).isZero();
        assertThat(contar("SELECT count(*) FROM carga_rejeicao WHERE carga_id = ?", longo(resposta, "$.id"))).isZero();
        assertThat(contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ?", amarelo)).isEqualTo(antes + 36);
        assertThat(contar("SELECT count(DISTINCT fonte_pagina) FROM aproveitamento_previsto WHERE parceria_id = ? AND fonte_pagina BETWEEN 7 AND 10",
                amarelo)).isEqualTo(4);
        assertThat(contar("SELECT count(*) FROM aproveitamento_previsto WHERE parceria_id = ? AND unidade = 'CPM' AND fonte_pagina = 8",
                amarelo)).as("as impressões de display entram na unidade CPM").isGreaterThanOrEqualTo(5);
    }
}

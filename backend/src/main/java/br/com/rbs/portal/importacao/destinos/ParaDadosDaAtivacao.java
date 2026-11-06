package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeCampo;
import br.com.rbs.portal.dados.DadoInformadoService;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.OrigemDoDado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.CamposDaAtivacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * O destino DADOS_ATIVACAO: os números que a agência conta (visitantes, cadastros, brindes) viram {@code dado_informado}.
 * [CARGA] Grava pelo mesmo serviço das rotas 40 e 41, com quem confirmou como autor e a origem ATLAS; o valor anterior fica
 * no histórico. Só vale numa importação de uma parceria (o alcance PARCERIA). Campo fora do catálogo, campo de arquivo ou
 * valor que não é número viram linha RECUSADA com o motivo.
 */
@Component
public class ParaDadosDaAtivacao implements DestinoDeGravacao {

    private final DadoInformadoService dadoInformado;
    private final CamposDaAtivacao campos;

    public ParaDadosDaAtivacao(DadoInformadoService dadoInformado, CamposDaAtivacao campos) {
        this.dadoInformado = dadoInformado;
        this.campos = campos;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.DADOS_ATIVACAO;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        if (alcance.parceriaId() == null) {
            problemas.add("Os dados da ativação são de uma importação de parceria.");
        }
        String referencia = texto(linha.dados().get("campo_chave"));
        if (referencia == null || referencia.isBlank()) {
            problemas.add("Falta o campo da linha.");
        } else {
            Optional<LinhaDeCampo> campo = campos.resolver(referencia);
            if (campo.isEmpty()) {
                problemas.add("O campo " + referencia + " não está no catálogo da ativação.");
            } else if ("ARQUIVO".equals(campo.get().tipo())) {
                problemas.add("O campo " + campo.get().rotulo() + " recebe um arquivo e não um número.");
            }
        }
        Object valor = linha.dados().get("valor");
        if (valor == null || texto(valor).isBlank()) {
            problemas.add("Falta o valor da linha.");
        } else if (MapeadorTabular.decimal(texto(valor)) == null) {
            problemas.add("O valor da linha precisa ser um número.");
        }
        return problemas;
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        Long parceriaId = contexto.alcance().parceriaId();
        if (parceriaId == null) {
            throw new DadosInvalidosException(java.util.Map.of("parceria",
                    "Os dados da ativação são de uma importação de parceria."));
        }
        int inseridas = 0;
        List<Rejeicao> rejeicoes = new ArrayList<>();
        for (LinhaAceita linha : linhas) {
            String referencia = texto(linha.proposta().dados().get("campo_chave"));
            Optional<LinhaDeCampo> campo = campos.resolver(referencia);
            if (campo.isEmpty()) {
                rejeicoes.add(new Rejeicao(linha.ordem(), "O campo " + referencia + " não está no catálogo da ativação."));
                continue;
            }
            BigDecimal valor = MapeadorTabular.decimal(texto(linha.proposta().dados().get("valor")));
            try {
                dadoInformado.gravar(usuario, parceriaId,
                        List.of(new ValorRequisicao(campo.get().chave(), valor)), OrigemDoDado.ATLAS, null);
                inseridas++;
            } catch (br.com.rbs.portal.comum.SemPermissaoException | br.com.rbs.portal.comum.NaoEncontradoException bloqueio) {
                // Aba fechada ou parceria que sumiu não é uma linha recusada: a resposta inteira é 403 ou 404.
                throw bloqueio;
            } catch (RuntimeException recusado) {
                rejeicoes.add(new Rejeicao(linha.ordem(), motivo(recusado)));
            }
        }
        return new ResultadoDaGravacao(null, linhas.size(), inseridas, 0, rejeicoes.size(), rejeicoes);
    }

    private static String motivo(RuntimeException recusado) {
        if (recusado instanceof DadosInvalidosException invalido && !invalido.campos().isEmpty()) {
            return invalido.campos().values().iterator().next();
        }
        String mensagem = recusado.getMessage();
        return mensagem == null || mensagem.isBlank() ? "Não consegui gravar este valor." : mensagem;
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}

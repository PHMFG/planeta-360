package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.Parceria;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.VeiculacaoDaData;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.CasadorDoPlano;
import br.com.rbs.portal.importacao.extracao.ConferenciaDaExtracao;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * O destino COMPROVANTE: a imagem (ou o PDF) de um print de veiculação vira o comprovante da exibição. [IMPORTACAO] A linha precisa
 * da marca, do veículo, do formato e da data; o código casa a marca com a parceria da edição, procura a exibição daquela data pelo
 * veículo e formato (ou pela linha do plano que o {@link CasadorDoPlano} achar) e grava o arquivo como {@code COMPROVACAO} da
 * parceria, ligando-o em {@code veiculacao.comprovante_arquivo_id}. Sem exibição que case, a linha é recusada com a frase própria.
 */
@Component
public class ParaComprovante implements DestinoDeGravacao {

    private static final DateTimeFormatter DIA_MES_ANO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ImportacaoRepositorio repositorio;
    private final CasadorDoPlano casador;
    private final Armazenamento armazenamento;

    public ParaComprovante(ImportacaoRepositorio repositorio, CasadorDoPlano casador, Armazenamento armazenamento) {
        this.repositorio = repositorio;
        this.casador = casador;
        this.armazenamento = armazenamento;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.COMPROVANTE;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        for (String campo : List.of("patrocinador", "veiculo", "formato", "data")) {
            Object valor = linha.dados().get(campo);
            if (valor == null || String.valueOf(valor).isBlank()) {
                problemas.add("Falta " + campo + " do comprovante.");
            }
        }
        if (!problemas.isEmpty()) {
            return problemas;
        }
        Parceria parceria = parceria(alcance.eventoId(), texto(linha.dados().get("patrocinador")));
        if (parceria == null) {
            problemas.add("Marca não encontrada nesta edição.");
            return problemas;
        }
        LocalDate data = data(linha);
        if (data == null) {
            problemas.add("A data do comprovante não é válida.");
            return problemas;
        }
        if (veiculacao(parceria, data, texto(linha.dados().get("veiculo")), texto(linha.dados().get("formato"))).isEmpty()) {
            problemas.add(semVeiculacao(texto(linha.dados().get("veiculo")), data));
        }
        return problemas;
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        int inseridas = 0;
        List<Rejeicao> rejeicoes = new ArrayList<>();
        byte[] conteudo = conteudo(contexto);
        for (LinhaAceita linha : linhas) {
            try {
                Parceria parceria = linha.parceriaId() != null ? repositorio.parceria(linha.parceriaId()).orElse(null)
                        : parceria(contexto.eventoId(), texto(linha.proposta().dados().get("patrocinador")));
                if (parceria == null) {
                    rejeicoes.add(new Rejeicao(linha.ordem(), "Marca não encontrada nesta edição."));
                    continue;
                }
                LocalDate data = data(linha.proposta());
                if (data == null) {
                    rejeicoes.add(new Rejeicao(linha.ordem(), "A data do comprovante não é válida."));
                    continue;
                }
                Optional<Long> veiculacao = veiculacao(parceria, data, texto(linha.proposta().dados().get("veiculo")),
                        texto(linha.proposta().dados().get("formato")));
                if (veiculacao.isEmpty()) {
                    rejeicoes.add(new Rejeicao(linha.ordem(), semVeiculacao(
                            texto(linha.proposta().dados().get("veiculo")), data)));
                    continue;
                }
                var arquivo = armazenamento.gravarComObservacao(conteudo, contexto.nomeArquivo(), "COMPROVACAO", usuario.id(),
                        parceria.id(), "importacao:" + contexto.importacaoId(), "MIDIA");
                repositorio.gravarComprovante(veiculacao.get(), arquivo.id());
                inseridas++;
            } catch (RuntimeException recusado) {
                rejeicoes.add(new Rejeicao(linha.ordem(), recusado.getMessage()));
            }
        }
        return new ResultadoDaGravacao(null, linhas.size(), inseridas, 0, rejeicoes.size(), rejeicoes);
    }

    /** A frase do comprovante sem exibição: o veículo como veio, a data em dd/MM/aaaa e o que fazer. */
    private static String semVeiculacao(String veiculo, LocalDate data) {
        return "Nenhuma veiculação de " + veiculo + " desta marca em " + data.format(DIA_MES_ANO)
                + ": registre a veiculação antes de anexar o comprovante.";
    }

    // ---------------------------------------------------------------------
    // O casamento e o arquivo
    // ---------------------------------------------------------------------

    private Parceria parceria(long eventoId, String marca) {
        return repositorio.parceriaDaMarca(eventoId, marca).orElse(null);
    }

    private static LocalDate data(LinhaProposta linha) {
        Object valor = linha.dados().get("data") != null ? linha.dados().get("data") : linha.dados().get("exibido_em");
        return valor == null ? null : ConferenciaDaExtracao.dataDe(String.valueOf(valor));
    }

    /**
     * A exibição daquela data: primeiro a que tem o mesmo veículo e formato da linha, sem caixa nem acento; depois a linha do plano
     * que o casador achar. É a mesma ordem que o E11 usa para a veiculação.
     */
    private Optional<Long> veiculacao(Parceria parceria, LocalDate data, String veiculo, String formato) {
        String veiculoNormalizado = CasadorDoPlano.normalizar(veiculo);
        String formatoNormalizado = CasadorDoPlano.normalizar(formato);
        for (VeiculacaoDaData candidata : repositorio.veiculacoesDaParceriaNaData(parceria.id(), data)) {
            if (veiculoNormalizado.equals(CasadorDoPlano.normalizar(candidata.veiculo()))
                    && formatoNormalizado.equals(CasadorDoPlano.normalizar(candidata.formato()))) {
                return Optional.of(candidata.id());
            }
        }
        CasadorDoPlano.Resultado plano = casador.casar(parceria.id(), veiculo, formato, null);
        if (plano.aproveitamentoId() == null) {
            return Optional.empty();
        }
        return repositorio.veiculacaoDoPlanoNaData(parceria.id(), plano.aproveitamentoId(), data);
    }

    private byte[] conteudo(ContextoDaGravacao contexto) {
        String caminho = repositorio.caminhoDoArquivo(contexto.arquivoId())
                .orElseThrow(() -> new IllegalStateException("O arquivo da importação não está mais no armazenamento."));
        try {
            return Files.readAllBytes(armazenamento.localizar(caminho)
                    .orElseThrow(() -> new IllegalStateException("O arquivo da importação não está mais no armazenamento.")));
        } catch (IOException erro) {
            throw new IllegalStateException("Não consegui ler o arquivo da importação.", erro);
        }
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}

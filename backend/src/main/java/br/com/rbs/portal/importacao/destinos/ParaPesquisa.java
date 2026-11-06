package br.com.rbs.portal.importacao.destinos;

import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.documentos.DetectorDeDadosPessoais;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Linha;
import br.com.rbs.portal.documentos.ExtratorDeTexto.Pagina;
import br.com.rbs.portal.importacao.AlcanceDeUmaImportacao;
import br.com.rbs.portal.importacao.DestinoDeGravacao;
import br.com.rbs.portal.importacao.DestinoDaImportacao;
import br.com.rbs.portal.importacao.ImportacaoRepositorio;
import br.com.rbs.portal.importacao.ImportacaoRepositorio.PerguntaDaImportacao;
import br.com.rbs.portal.importacao.LinhaProposta;
import br.com.rbs.portal.importacao.extracao.MapeadorTabular;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * O destino PESQUISA: a planilha da empresa de pesquisa vira resultado, perfil demográfico e respostas de texto. [CARGA] O
 * resultado passa pela área de passagem e pela {@code fn_carregar_pesquisa} (o mesmo caminho da rota 26, com o escopo
 * ATIVACAO); o perfil troca a categoria anterior da mesma pesquisa, parceria, dimensão e categoria; o texto livre passa pelo
 * detector de dado pessoal antes de ser gravado. Sem a pesquisa da edição aberta, a confirmação dá 422 com a frase da carga.
 */
@Component
public class ParaPesquisa implements DestinoDeGravacao {

    static final String RESULTADO = "RESULTADO";
    static final String PERFIL = "PERFIL";
    static final String TEXTO = "TEXTO";
    private static final Set<String> TIPOS = Set.of(RESULTADO, PERFIL, TEXTO);
    private static final Set<String> DIMENSOES = Set.of("GENERO", "FAIXA_ETARIA", "RENDA", "DOMICILIO", "SETOR", "RECORRENCIA",
            "OUTRA");
    private static final Set<String> UNIDADES = Set.of("PERCENTUAL", "NOTA", "CONTAGEM");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ImportacaoRepositorio repositorio;
    private final DetectorDeDadosPessoais detector;

    public ParaPesquisa(ImportacaoRepositorio repositorio, DetectorDeDadosPessoais detector) {
        this.repositorio = repositorio;
        this.detector = detector;
    }

    @Override
    public DestinoDaImportacao destino() {
        return DestinoDaImportacao.PESQUISA;
    }

    @Override
    public List<String> problemas(LinhaProposta linha, AlcanceDeUmaImportacao alcance) {
        List<String> problemas = new ArrayList<>();
        Map<String, Object> dados = linha.dados();
        String tipo = texto(dados.get("tipo"));
        if (tipo == null || tipo.isBlank()) {
            problemas.add("Falta o tipo da linha (RESULTADO, PERFIL ou TEXTO).");
            return problemas;
        }
        tipo = tipo.strip().toUpperCase(Locale.ROOT);
        if (!TIPOS.contains(tipo)) {
            problemas.add("Tipo de linha inválido. Use RESULTADO, PERFIL ou TEXTO.");
            return problemas;
        }
        String marca = texto(dados.get("patrocinador"));
        boolean daMarca = marca != null && !marca.isBlank();
        switch (tipo) {
            case RESULTADO -> conferirResultado(linha, alcance, marca, daMarca, problemas);
            case PERFIL -> conferirPerfil(dados, alcance, daMarca, problemas);
            case TEXTO -> conferirTexto(linha, alcance, daMarca, problemas);
            default -> {
            }
        }
        return problemas;
    }

    private void conferirResultado(LinhaProposta linha, AlcanceDeUmaImportacao alcance, String marca, boolean daMarca,
                                   List<String> problemas) {
        Map<String, Object> dados = linha.dados();
        if (!daMarca) {
            problemas.add("Falta o patrocinador da resposta.");
            return;
        }
        Integer ordem = inteiro(dados.get("pergunta"));
        if (ordem == null) {
            problemas.add("A pergunta precisa ser o número dela na pesquisa.");
            return;
        }
        BigDecimal valor = numero(dados.get("valor"));
        if (valor == null) {
            problemas.add("Falta o valor da resposta.");
        }
        String unidade = texto(dados.get("unidade"));
        if (unidade == null || !UNIDADES.contains(unidade.strip().toUpperCase(Locale.ROOT))) {
            problemas.add("A unidade da resposta precisa ser PERCENTUAL, NOTA ou CONTAGEM.");
        }
        if (!baseValida(dados.get("base_respondentes"))) {
            problemas.add("A base de respondentes precisa ser um número inteiro maior que zero.");
        }
        Long pesquisa = repositorio.pesquisaDaEdicao(alcance.eventoId(), "ATIVACAO").orElse(null);
        Optional<PerguntaDaImportacao> pergunta = pergunta(pesquisa, alcance, marca, ordem);
        if (pesquisa != null) {
            if (pergunta.isEmpty()) {
                problemas.add("A pergunta " + ordem + " não está cadastrada para esta marca nesta pesquisa.");
            } else {
                conferirPergunta(pergunta.get(), ordem, dados, problemas);
            }
        }
    }

    private void conferirPerfil(Map<String, Object> dados, AlcanceDeUmaImportacao alcance, boolean daMarca,
                                List<String> problemas) {
        String dimensao = texto(dados.get("dimensao"));
        if (dimensao == null || !DIMENSOES.contains(dimensao.strip().toUpperCase(Locale.ROOT))) {
            problemas.add("A dimensão do perfil não é conhecida.");
        }
        if (texto(dados.get("categoria")) == null || texto(dados.get("categoria")).isBlank()) {
            problemas.add("Falta a categoria do perfil.");
        }
        BigDecimal valor = numero(dados.get("valor"));
        if (valor == null || valor.signum() < 0 || valor.compareTo(new BigDecimal("100")) > 0) {
            problemas.add("O percentual do perfil precisa estar entre 0 e 100.");
        }
        if (!baseValida(dados.get("base_respondentes"))) {
            problemas.add("A base de respondentes precisa ser um número inteiro maior que zero.");
        }
        // A pesquisa só é exigida na confirmação; aqui, sem ela, não há pergunta para conferir.
        if (daMarca) {
            repositorio.pesquisaDaEdicao(alcance.eventoId(), "ATIVACAO");
        } else {
            repositorio.pesquisaDaEdicao(alcance.eventoId(), "GERAL");
        }
    }

    private void conferirTexto(LinhaProposta linha, AlcanceDeUmaImportacao alcance, boolean daMarca, List<String> problemas) {
        Map<String, Object> dados = linha.dados();
        if (!daMarca) {
            problemas.add("Falta o patrocinador da resposta.");
            return;
        }
        Integer ordem = inteiro(dados.get("pergunta"));
        if (ordem == null) {
            problemas.add("A pergunta precisa ser o número dela na pesquisa.");
            return;
        }
        String resposta = texto(dados.get("alternativa"));
        if (resposta == null || resposta.isBlank()) {
            problemas.add("Falta o texto da resposta.");
        } else if (resposta.length() > 1000) {
            problemas.add("O texto da resposta tem no máximo 1.000 caracteres.");
        }
        BigDecimal contagem = numero(dados.get("valor"));
        if (contagem == null || contagem.signum() <= 0 || contagem.stripTrailingZeros().scale() > 0) {
            problemas.add("A contagem da resposta precisa ser um número inteiro maior que zero.");
        }
        Long pesquisa = repositorio.pesquisaDaEdicao(alcance.eventoId(), "ATIVACAO").orElse(null);
        if (pesquisa != null) {
            String marca = texto(dados.get("patrocinador"));
            Optional<PerguntaDaImportacao> pergunta = pergunta(pesquisa, alcance, marca, ordem);
            if (pergunta.isEmpty()) {
                problemas.add("A pergunta " + ordem + " não está cadastrada para esta marca nesta pesquisa.");
            } else if (!"APROVADA".equals(pergunta.get().status())) {
                problemas.add("A pergunta " + ordem + " ainda não foi aprovada pela RBS.");
            }
        }
    }

    private Optional<PerguntaDaImportacao> pergunta(Long pesquisaId, AlcanceDeUmaImportacao alcance, String marca, int ordem) {
        if (pesquisaId == null || marca == null || marca.isBlank()) {
            return Optional.empty();
        }
        return repositorio.parceriaDaMarca(alcance.eventoId(), marca)
                .flatMap(parceria -> repositorio.perguntaDaMarca(pesquisaId, parceria.id(), ordem));
    }

    private static void conferirPergunta(PerguntaDaImportacao pergunta, int ordem, Map<String, Object> dados,
                                         List<String> problemas) {
        if (!"APROVADA".equals(pergunta.status())) {
            problemas.add("A pergunta " + ordem + " ainda não foi aprovada pela RBS.");
            return;
        }
        String alternativa = texto(dados.get("alternativa"));
        if (alternativa == null || alternativa.isBlank()) {
            return;
        }
        List<String> lista = alternativas(pergunta.alternativas());
        if (lista.isEmpty()) {
            return;
        }
        if (lista.stream().noneMatch(a -> a.strip().equalsIgnoreCase(alternativa.strip()))) {
            problemas.add("A alternativa \"" + alternativa + "\" não está na lista da pergunta " + ordem + ".");
        }
    }

    @Override
    public ResultadoDaGravacao gravar(UsuarioAutenticado usuario, ContextoDaGravacao contexto, List<LinhaAceita> linhas) {
        boolean precisaAtivacao = linhas.stream().anyMatch(l -> usaAtivacao(l.proposta()));
        boolean precisaGeral = linhas.stream().anyMatch(l -> usaGeral(l.proposta()));
        Long ativacao = precisaAtivacao ? pesquisa(contexto.eventoId(), "ATIVACAO") : null;
        Long geral = precisaGeral ? pesquisa(contexto.eventoId(), "GERAL") : null;
        if (precisaAtivacao && ativacao == null) {
            throw semPesquisa(contexto.eventoId(), "ATIVACAO");
        }
        if (precisaGeral && geral == null) {
            throw semPesquisa(contexto.eventoId(), "GERAL");
        }

        List<Rejeicao> rejeicoes = new ArrayList<>();
        Long cargaId = null;
        int lidas = 0;
        int inseridas = 0;
        int duplicadas = 0;
        List<LinhaAceita> resultados = linhas.stream().filter(l -> tipo(l.proposta()).equals(RESULTADO)).toList();
        if (!resultados.isEmpty()) {
            cargaId = repositorio.criarCarga("PESQUISA", contexto.arquivoId(), contexto.eventoId(), usuario.id());
            for (LinhaAceita linha : resultados) {
                Map<String, Object> dados = linha.proposta().dados();
                repositorio.gravarStgPesquisa(cargaId, linha.ordem(), texto(dados.get("patrocinador")),
                        inteiro(dados.get("pergunta")), texto(dados.get("indicador")), texto(dados.get("alternativa")),
                        numero(dados.get("valor")), texto(dados.get("unidade")), inteiro(dados.get("base_respondentes")));
                lidas++;
            }
            repositorio.processarPesquisa(cargaId, ativacao);
            var totais = repositorio.totaisDaCarga(cargaId);
            inseridas += totais.map(ImportacaoRepositorio.TotaisDaCarga::inseridas).orElse(0);
            duplicadas += totais.map(ImportacaoRepositorio.TotaisDaCarga::duplicadas).orElse(0);
            rejeicoes.addAll(repositorio.rejeicoesDaCarga(cargaId).stream()
                    .map(r -> new Rejeicao(r.ordem(), r.motivo())).toList());
        }

        for (LinhaAceita linha : linhas) {
            Map<String, Object> dados = linha.proposta().dados();
            String tipo = tipo(linha.proposta());
            try {
                if (PERFIL.equals(tipo)) {
                    Long pesquisa = linha.parceriaId() == null ? geral : ativacao;
                    if (pesquisa == null) {
                        throw semPesquisa(contexto.eventoId(), linha.parceriaId() == null ? "GERAL" : "ATIVACAO");
                    }
                    String dimensao = texto(dados.get("dimensao")).strip().toUpperCase(Locale.ROOT);
                    String categoria = texto(dados.get("categoria")).strip();
                    repositorio.apagarPerfil(pesquisa, linha.parceriaId(), dimensao, categoria);
                    repositorio.inserirPerfil(pesquisa, linha.parceriaId(), dimensao, categoria, numero(dados.get("valor")),
                            inteiro(dados.get("base_respondentes")), contexto.importacaoId());
                    inseridas++;
                    lidas++;
                } else if (TEXTO.equals(tipo)) {
                    if (linha.parceriaId() == null) {
                        throw new DadosInvalidosException(Map.of("patrocinador", "Falta o patrocinador da resposta."));
                    }
                    int ordem = inteiro(dados.get("pergunta"));
                    PerguntaDaImportacao pergunta = repositorio.perguntaDaMarca(ativacao, linha.parceriaId(), ordem)
                            .orElseThrow(() -> new DadosInvalidosException(
                                    Map.of("pergunta", "A pergunta " + ordem + " não está cadastrada para esta marca nesta pesquisa.")));
                    String resposta = texto(dados.get("alternativa"));
                    int contagem = inteiro(dados.get("valor"));
                    boolean suspeita = !detector.analisarConversa(List.of(
                            new Pagina(1, List.of(new Linha(resposta, 0, List.of(), false))))).isEmpty();
                    repositorio.inserirRespostaTexto(ativacao, linha.parceriaId(), pergunta.id(), resposta, contagem, suspeita,
                            contexto.importacaoId());
                    inseridas++;
                    lidas++;
                }
            } catch (RuntimeException recusado) {
                rejeicoes.add(new Rejeicao(linha.ordem(), motivo(recusado)));
            }
        }
        return new ResultadoDaGravacao(cargaId, lidas, inseridas, duplicadas, rejeicoes.size(), rejeicoes);
    }

    private static boolean usaAtivacao(LinhaProposta proposta) {
        String tipo = tipo(proposta);
        if (RESULTADO.equals(tipo) || TEXTO.equals(tipo)) {
            return true;
        }
        return PERFIL.equals(tipo) && texto(proposta.dados().get("patrocinador")) != null
                && !texto(proposta.dados().get("patrocinador")).isBlank();
    }

    private static boolean usaGeral(LinhaProposta proposta) {
        return PERFIL.equals(tipo(proposta)) && (texto(proposta.dados().get("patrocinador")) == null
                || texto(proposta.dados().get("patrocinador")).isBlank());
    }

    private static String tipo(LinhaProposta proposta) {
        String tipo = texto(proposta.dados().get("tipo"));
        return tipo == null ? "" : tipo.strip().toUpperCase(Locale.ROOT);
    }

    private Long pesquisa(long eventoId, String escopo) {
        return repositorio.pesquisaDaEdicao(eventoId, escopo).orElse(null);
    }

    private DadosInvalidosException semPesquisa(long eventoId, String escopo) {
        int edicao = repositorio.evento(eventoId).map(ImportacaoRepositorio.Evento::edicao).orElse(0);
        String nome = "ATIVACAO".equals(escopo) ? "ativação" : "festival (geral)";
        String frase = "A edição " + edicao + " ainda não tem a pesquisa de " + nome
                + " aberta. Abra-a em Pesquisa, no menu da RBS.";
        return new DadosInvalidosException(Map.of("pesquisa", frase));
    }

    private static String motivo(RuntimeException recusado) {
        if (recusado instanceof DadosInvalidosException invalido && !invalido.campos().isEmpty()) {
            return invalido.campos().values().iterator().next();
        }
        String mensagem = recusado.getMessage();
        return mensagem == null || mensagem.isBlank() ? "Não consegui gravar esta linha." : mensagem;
    }

    @SuppressWarnings("unchecked")
    private static List<String> alternativas(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        try {
            List<Object> itens = JSON.readValue(json, List.class);
            return itens.stream().map(String::valueOf).toList();
        } catch (RuntimeException invalido) {
            return List.of();
        }
    }

    private static boolean baseValida(Object valor) {
        Integer base = inteiro(valor);
        return base != null && base > 0;
    }

    private static Integer inteiro(Object valor) {
        BigDecimal numero = numero(valor);
        if (numero == null || numero.stripTrailingZeros().scale() > 0) {
            return null;
        }
        try {
            return numero.intValueExact();
        } catch (ArithmeticException grande) {
            return null;
        }
    }

    private static BigDecimal numero(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof BigDecimal decimal) {
            return decimal;
        }
        if (valor instanceof Number numero) {
            return new BigDecimal(numero.toString());
        }
        return MapeadorTabular.decimal(String.valueOf(valor));
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}

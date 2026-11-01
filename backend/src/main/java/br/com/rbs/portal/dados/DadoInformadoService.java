package br.com.rbs.portal.dados;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.Armazenamento;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.Datas;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.NaoEncontradoException;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeCampo;
import br.com.rbs.portal.dados.DadoInformadoRepositorio.LinhaDeVigente;
import br.com.rbs.portal.dados.DadosDtos.ArquivoDoDadoDto;
import br.com.rbs.portal.dados.DadosDtos.CampoComValorDto;
import br.com.rbs.portal.dados.DadosDtos.CampoDto;
import br.com.rbs.portal.dados.DadosDtos.DadosDaAbaDto;
import br.com.rbs.portal.dados.DadosDtos.ValorRequisicao;
import br.com.rbs.portal.dados.DadosDtos.VigenteDto;
import br.com.rbs.portal.materiais.Arquivo;
import br.com.rbs.portal.materiais.MateriaisDtos.UsuarioResumoDto;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * O catálogo de campos (rota 39) e os dados que a marca informa (rotas 40 e 41): os números que o sistema não tem e só ela
 * (ou a agência) sabe, como visitantes no espaço e brindes distribuídos. Este é o ÚNICO caminho de gravação: o formulário da
 * aba (origem PORTAL) e o cartão de perguntas do Atlas (origem ATLAS) passam por {@link #gravar}, com a mesma validação e a
 * mesma tabela; assim o portal funciona com o Atlas desligado e o Atlas não guarda dado no texto da conversa.
 * [VALIDACAO] Confere chave do catálogo, tipo, mínimo, máximo e os limites entre campos (cadastros não passam de visitantes,
 * brindes distribuídos não passam dos disponíveis), nos dois sentidos. [HISTORICO] Corrigir é gravar uma linha nova (a anterior
 * fica como trilha, a vigente é a mais recente); valor igual ao vigente não grava nada; cada valor gravado registra CRIACAO.
 * [ISOLAMENTO] [PERMISSAO] Parceria de outro patrocinador dá 404; a marca só lê e grava com a aba do campo aberta (403).
 * O campo de arquivo (a planilha da agência, M6) tem o próprio caminho de gravação, {@link #enviarArquivo} (rota 66): o JSON das
 * rotas 41 e do cartão do Atlas continua recusando-o.
 */
@Service
public class DadoInformadoService {

    /** O maior valor que cabe em dado_informado.valor_numero, NUMERIC(14,2). */
    private static final BigDecimal TETO = new BigDecimal("999999999999.99");
    private static final DecimalFormatSymbols SIMBOLOS_BR = simbolosBr();

    private final DadoInformadoRepositorio repositorio;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;
    private final Armazenamento armazenamento;

    public DadoInformadoService(DadoInformadoRepositorio repositorio, VerificadorDeAcesso verificador, HistoricoService historico,
                                Armazenamento armazenamento) {
        this.repositorio = repositorio;
        this.verificador = verificador;
        this.historico = historico;
        this.armazenamento = armazenamento;
    }

    // ---------------------------------------------------------------------
    // Rota 39: o catálogo
    // ---------------------------------------------------------------------

    /** Rota 39. Qualquer pessoa logada (o catálogo é fixo e não tem dado de ninguém). Sem {@code aba}, todas as abas. */
    @Transactional(readOnly = true)
    public List<CampoDto> listarCampos(String aba) {
        return repositorio.campos(aba).stream().map(DadoInformadoService::campo).toList();
    }

    // ---------------------------------------------------------------------
    // Rota 40: os valores vigentes
    // ---------------------------------------------------------------------

    /**
     * Rota 40. Ordem das respostas: 404 (parceria que não existe ou é de outra), 403 (aba fechada para a marca; a RBS passa).
     * Uma só leitura (REPEATABLE READ): os campos e os valores são do mesmo instante.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DadosDaAbaDto ver(UsuarioAutenticado usuario, long parceriaId, String aba) {
        verificador.exigirParceria(usuario, parceriaId);
        verificador.exigirAbaLiberada(usuario, parceriaId, aba);
        return corpo(parceriaId, aba);
    }

    // ---------------------------------------------------------------------
    // Rota 41 (e o cartão do Atlas): gravar
    // ---------------------------------------------------------------------

    /**
     * Rotas 41 e cartão do Atlas. Grava de 1 a 20 valores de uma aba só e devolve o mesmo corpo da rota 40 (a aba é a dos campos
     * enviados). {@code origem} é PORTAL (formulário da aba) ou ATLAS (cartão). Ordem das respostas: 404 (parceria), 403 (aba
     * dos campos fechada para a marca) e 422 com {@code campos} chaveado pela CHAVE do campo: chave desconhecida ou repetida,
     * campos de abas diferentes (em {@code valores}), campo de arquivo, valor que não é do tipo, abaixo do mínimo, acima do
     * máximo, acima do limite_campo (vigente, ou o valor novo do mesmo pedido) e o inverso, baixar um campo-limite abaixo de um
     * dependente vigente. Tudo ou nada: com um erro, nada é gravado. Valor igual ao vigente não grava linha nova. Os pedidos da
     * mesma parceria esperam um pelo outro (trava na linha da parceria), para o limite ser conferido sobre o vigente de verdade.
     * [PERMISSAO] Quem chama pelo Atlas passa o usuário da sessão: esta conferência (parceria e aba) vale igual para os dois.
     */
    @Transactional
    public DadosDaAbaDto gravar(UsuarioAutenticado usuario, long parceriaId, List<ValorRequisicao> valores, OrigemDoDado origem,
                                HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);

        Map<String, LinhaDeCampo> catalogo = porChave(repositorio.campos(null));
        Map<String, String> erros = new LinkedHashMap<>();
        Map<String, ValorRequisicao> pedidos = new LinkedHashMap<>();
        for (ValorRequisicao pedido : valores) {
            if (pedidos.putIfAbsent(pedido.chave(), pedido) != null) {
                erros.putIfAbsent(pedido.chave(), "Este campo aparece mais de uma vez no pedido.");
            } else if (!catalogo.containsKey(pedido.chave())) {
                erros.put(pedido.chave(), "Campo desconhecido.");
            }
        }
        Set<String> abas = new LinkedHashSet<>();
        pedidos.keySet().stream().filter(catalogo::containsKey).forEach(chave -> abas.add(catalogo.get(chave).aba()));
        if (abas.size() > 1) {
            erros.put("valores", "Envie os campos de uma aba só.");
        }
        // [PERMISSAO] A aba de cada campo tem de estar aberta para a marca, e isso vem antes de dizer o que há de errado nos valores.
        for (String aba : abas) {
            verificador.exigirAbaLiberada(usuario, parceriaId, aba);
        }

        // [VALIDACAO] Os erros vão todos numa resposta só: os de chave e de aba, acima, e os de valor e de limite, aqui.
        repositorio.travarParceria(parceriaId);
        Map<String, LinhaDeVigente> atuais = vigentesPorChave(parceriaId);
        Map<String, Object> novos = new LinkedHashMap<>();
        for (ValorRequisicao pedido : pedidos.values()) {
            if (!catalogo.containsKey(pedido.chave())) {
                continue;   // já tem o erro "Campo desconhecido."
            }
            Object convertido = converter(catalogo.get(pedido.chave()), pedido.valor(), erros);
            if (convertido != null) {
                novos.put(pedido.chave(), convertido);
            }
        }
        conferirLimites(catalogo, pedidos, novos, atuais, erros);
        if (!erros.isEmpty()) {
            throw new DadosInvalidosException(erros);
        }

        String nomeDaOrigem = origem == OrigemDoDado.ATLAS ? "Atlas" : "portal";
        novos.forEach((chave, valor) -> {
            LinhaDeVigente atual = atuais.get(chave);
            if (igual(atual, valor)) {
                return;
            }
            LinhaDeCampo campo = catalogo.get(chave);
            long id = valor instanceof LocalDate data
                    ? repositorio.inserirData(parceriaId, chave, data, origem.name(), usuario.id())
                    : repositorio.inserirNumero(parceriaId, chave, (BigDecimal) valor, origem.name(), usuario.id());
            historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "dado_informado", id, descrever(atual),
                    descrever(valor), campo.rotulo() + " (" + nomeDaOrigem + ")", requisicao);
        });
        return corpo(parceriaId, abas.iterator().next());
    }

    // ---------------------------------------------------------------------
    // Rota 66: o campo de arquivo
    // ---------------------------------------------------------------------

    /**
     * Rota 66. Grava o arquivo de um campo {@code ARQUIVO} (hoje {@code ativacao.planilha_agencia}, a planilha da agência) como valor
     * novo da parceria, com origem PORTAL, e devolve o valor vigente (o que acabou de entrar). Ordem das respostas: 404 (parceria que
     * não existe ou é de outra; campo que não está no catálogo), 403 (aba do campo fechada para a marca), 422 (campo que não é de
     * arquivo; parte {@code arquivo} ausente ou vazia), 413 e 415 (tamanho, extensão ou conteúdo, conferidos pelo Armazenamento: só
     * {@code .xlsx}, {@code .xls} e {@code .csv}). O arquivo fica na parceria e na aba do campo, na categoria OUTRO, e sai pela rota 18
     * com as mesmas regras de acesso. [HISTORICO] Cada envio é uma linha nova em {@code dado_informado} (a anterior fica guardada) e
     * grava CRIACAO, na mesma transação do arquivo: se algo falhar, nem o registro nem o arquivo em disco ficam.
     */
    @Transactional
    public VigenteDto enviarArquivo(UsuarioAutenticado usuario, long parceriaId, String chave, MultipartFile arquivo,
                                    HttpServletRequest requisicao) {
        verificador.exigirParceria(usuario, parceriaId);
        LinhaDeCampo campo = porChave(repositorio.campos(null)).get(chave);
        if (campo == null) {
            throw new NaoEncontradoException("Não encontramos este campo.");
        }
        verificador.exigirAbaLiberada(usuario, parceriaId, campo.aba());
        if (!"ARQUIVO".equals(campo.tipo())) {
            throw new DadosInvalidosException(Map.of(chave, "Este campo não recebe arquivo. Informe o número pelo formulário."));
        }
        if (arquivo == null || arquivo.isEmpty()) {
            throw new DadosInvalidosException(Map.of("arquivo", "Envie o arquivo na parte \"arquivo\" do formulário."));
        }

        repositorio.travarParceria(parceriaId);
        LinhaDeVigente anterior = vigentesPorChave(parceriaId).get(chave);
        Arquivo gravado = armazenamento.gravarPlanilha(bytesDe(arquivo), arquivo.getOriginalFilename(), usuario.id(), parceriaId, campo.aba());
        long id = repositorio.inserirArquivo(parceriaId, chave, gravado.id(), OrigemDoDado.PORTAL.name(), usuario.id());
        historico.registrar(AcaoHistorico.CRIACAO, usuario, parceriaId, "dado_informado", id, descrever(anterior), gravado.nomeOriginal(),
                campo.rotulo() + " (portal)", requisicao);
        return vigente(vigentesPorChave(parceriaId).get(chave));
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    // ---------------------------------------------------------------------
    // Para o Atlas
    // ---------------------------------------------------------------------

    /**
     * O valor vigente de cada campo que a parceria já informou: chave do campo para o valor, quem informou, quando e a origem
     * (PORTAL ou ATLAS), na ordem do catálogo. Campo sem valor não aparece no mapa. [PERMISSAO] Este método NÃO confere
     * permissão: quem chama (o Atlas, ao montar o relatório da parceria da sessão) precisa já ter conferido a parceria e a aba
     * com o VerificadorDeAcesso, com o perfil de quem pede. Nunca o chame com um id de parceria que veio de fora sem conferir.
     */
    @Transactional(readOnly = true)
    public Map<String, VigenteDto> vigentes(long parceriaId) {
        Map<String, LinhaDeVigente> atuais = vigentesPorChave(parceriaId);
        Map<String, VigenteDto> mapa = new LinkedHashMap<>();
        for (LinhaDeCampo campo : repositorio.campos(null)) {
            LinhaDeVigente atual = atuais.get(campo.chave());
            if (atual != null) {
                mapa.put(campo.chave(), vigente(atual));
            }
        }
        return mapa;
    }

    // ---------------------------------------------------------------------
    // Validação do valor
    // ---------------------------------------------------------------------

    /**
     * O valor do JSON no tipo do campo: BigDecimal (INTEIRO e DECIMAL) ou LocalDate (DATA). Se não serve, registra a frase em
     * {@code erros} (chave = a do campo) e devolve nulo. Só número JSON vale como número: texto como "1200" não.
     */
    private static Object converter(LinhaDeCampo campo, Object bruto, Map<String, String> erros) {
        String chave = campo.chave();
        switch (campo.tipo()) {
            case "ARQUIVO" -> {
                // o arquivo entra pela rota 66 (multipart); o JSON da rota 41 e o cartão do Atlas não o aceitam
                erros.putIfAbsent(chave, "Este campo recebe um arquivo, que ainda não é aceito por aqui.");
                return null;
            }
            case "DATA" -> {
                try {
                    if (bruto instanceof String texto) {
                        return LocalDate.parse(texto.trim());
                    }
                } catch (DateTimeParseException invalida) {
                    // cai na frase abaixo
                }
                erros.putIfAbsent(chave, "Informe a data no formato AAAA-MM-DD.");
                return null;
            }
            default -> {
                boolean inteiro = "INTEIRO".equals(campo.tipo());
                BigDecimal numero = numero(bruto);
                if (bruto == null) {
                    erros.putIfAbsent(chave, "Informe o valor.");
                } else if (numero == null || (inteiro && numero.stripTrailingZeros().scale() > 0)) {
                    erros.putIfAbsent(chave, inteiro ? "Informe um número inteiro." : "Informe um número.");
                } else if (!inteiro && numero.stripTrailingZeros().scale() > 2) {
                    erros.putIfAbsent(chave, "Use no máximo 2 casas decimais.");
                } else if (numero.abs().compareTo(TETO) > 0) {
                    erros.putIfAbsent(chave, "O valor é grande demais.");
                } else if (campo.minimo() != null && numero.compareTo(campo.minimo()) < 0) {
                    erros.putIfAbsent(chave, "O valor mínimo é " + formatar(campo.minimo()) + ".");
                } else if (campo.maximo() != null && numero.compareTo(campo.maximo()) > 0) {
                    erros.putIfAbsent(chave, "O valor máximo é " + formatar(campo.maximo()) + ".");
                } else {
                    return numero;
                }
                return null;
            }
        }
    }

    /** Um número JSON como BigDecimal (1200 e 1200.0 são o mesmo inteiro); nulo para texto, booleano, lista ou NaN. */
    private static BigDecimal numero(Object bruto) {
        return switch (bruto) {
            case Integer n -> BigDecimal.valueOf(n);
            case Long n -> BigDecimal.valueOf(n);
            case Short n -> BigDecimal.valueOf(n);
            case Byte n -> BigDecimal.valueOf(n);
            case BigInteger n -> new BigDecimal(n);
            case BigDecimal n -> n;
            case Double n -> n.isNaN() || n.isInfinite() ? null : BigDecimal.valueOf(n);
            case Float n -> n.isNaN() || n.isInfinite() ? null : new BigDecimal(n.toString());
            case null, default -> null;
        };
    }

    /**
     * [VALIDACAO] Os limites entre campos, nos dois sentidos. Um campo com {@code limite_campo} não passa do valor do campo-limite
     * (o novo do mesmo pedido, ou o vigente; sem nenhum dos dois, não há com que comparar). E um campo-limite não pode ficar
     * abaixo de um dependente que já tem valor vigente e não está sendo mudado neste pedido (se está, o erro cai nele).
     */
    private static void conferirLimites(Map<String, LinhaDeCampo> catalogo, Map<String, ValorRequisicao> pedidos,
                                        Map<String, Object> novos, Map<String, LinhaDeVigente> atuais, Map<String, String> erros) {
        novos.forEach((chave, valor) -> {
            if (!(valor instanceof BigDecimal numero)) {
                return;
            }
            LinhaDeCampo campo = catalogo.get(chave);
            if (campo.limiteCampo() != null) {
                LinhaDeCampo limite = catalogo.get(campo.limiteCampo());
                BigDecimal teto = novos.get(limite.chave()) instanceof BigDecimal novo ? novo
                        : pedidos.containsKey(limite.chave()) ? null : numeroVigente(atuais.get(limite.chave()));
                if (teto != null && numero.compareTo(teto) > 0) {
                    erros.putIfAbsent(chave, "Não pode passar de " + limite.rotulo() + " (" + formatar(teto) + ").");
                    return;
                }
            }
            for (LinhaDeCampo dependente : catalogo.values()) {
                BigDecimal atual = numeroVigente(atuais.get(dependente.chave()));
                if (chave.equals(dependente.limiteCampo()) && !pedidos.containsKey(dependente.chave())
                        && atual != null && atual.compareTo(numero) > 0) {
                    erros.putIfAbsent(chave, "Não pode ficar abaixo de " + dependente.rotulo() + " (" + formatar(atual) + ").");
                    return;
                }
            }
        });
    }

    private static BigDecimal numeroVigente(LinhaDeVigente atual) {
        return atual == null ? null : atual.valorNumero();
    }

    private static boolean igual(LinhaDeVigente atual, Object valor) {
        if (atual == null) {
            return false;
        }
        return valor instanceof BigDecimal numero ? atual.valorNumero() != null && atual.valorNumero().compareTo(numero) == 0
                : valor.equals(atual.valorData());
    }

    // ---------------------------------------------------------------------
    // Montagem da resposta
    // ---------------------------------------------------------------------

    private DadosDaAbaDto corpo(long parceriaId, String aba) {
        Map<String, LinhaDeVigente> atuais = vigentesPorChave(parceriaId);
        List<CampoComValorDto> campos = repositorio.campos(aba).stream()
                .map(c -> new CampoComValorDto(c.chave(), c.aba(), c.rotulo(), c.tipo(), c.unidade(), limite(c.minimo(), c.tipo()),
                        limite(c.maximo(), c.tipo()), c.ajuda(), c.limiteCampo(),
                        atuais.containsKey(c.chave()) ? vigente(atuais.get(c.chave())) : null))
                .toList();
        return new DadosDaAbaDto(parceriaId, aba, campos);
    }

    private static CampoDto campo(LinhaDeCampo c) {
        return new CampoDto(c.chave(), c.aba(), c.rotulo(), c.tipo(), c.unidade(), limite(c.minimo(), c.tipo()),
                limite(c.maximo(), c.tipo()), c.ajuda(), c.limiteCampo());
    }

    /** Mínimo e máximo como número do tipo do campo: 0 e não 0.00 num campo INTEIRO. */
    private static Number limite(BigDecimal valor, String tipo) {
        return valor == null ? null : "INTEIRO".equals(tipo) ? (Number) valor.longValue() : valor;
    }

    private static VigenteDto vigente(LinhaDeVigente l) {
        Object valor = l.valorNumero() != null ? ("INTEIRO".equals(l.tipo()) ? (Object) l.valorNumero().longValue() : l.valorNumero())
                : l.valorData() != null ? l.valorData() : l.arquivoId();
        return new VigenteDto(valor, l.origem(), new UsuarioResumoDto(l.usuarioId(), l.usuarioNome(), l.usuarioPerfil()),
                Datas.emBrasilia(l.informadoEm()), l.arquivoId() == null ? null : new ArquivoDoDadoDto(l.arquivoId(), l.arquivoNome()));
    }

    private Map<String, LinhaDeVigente> vigentesPorChave(long parceriaId) {
        Map<String, LinhaDeVigente> mapa = new LinkedHashMap<>();
        repositorio.vigentes(parceriaId).forEach(l -> mapa.put(l.campoChave(), l));
        return mapa;
    }

    private static Map<String, LinhaDeCampo> porChave(List<LinhaDeCampo> campos) {
        Map<String, LinhaDeCampo> mapa = new LinkedHashMap<>();
        campos.forEach(c -> mapa.put(c.chave(), c));
        return mapa;
    }

    // ---------------------------------------------------------------------
    // Texto
    // ---------------------------------------------------------------------

    /** O valor como o histórico o mostra: número no padrão brasileiro, data AAAA-MM-DD; nulo para o que não existia. */
    private static String descrever(Object valor) {
        return switch (valor) {
            case null -> null;
            case BigDecimal numero -> formatar(numero);
            case LocalDate data -> data.toString();
            case LinhaDeVigente atual -> atual.valorNumero() != null ? formatar(atual.valorNumero())
                    : atual.valorData() != null ? atual.valorData().toString() : atual.arquivoNome();
            default -> valor.toString();
        };
    }

    /** Milhar com ponto, decimal com vírgula e sem zeros sobrando (1200 vira 1.200; 12.50 vira 12,5), como fn_numero_br. */
    static String formatar(BigDecimal valor) {
        return new DecimalFormat("#,##0.##", SIMBOLOS_BR).format(valor);
    }

    private static DecimalFormatSymbols simbolosBr() {
        DecimalFormatSymbols simbolos = DecimalFormatSymbols.getInstance(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return simbolos;
    }
}

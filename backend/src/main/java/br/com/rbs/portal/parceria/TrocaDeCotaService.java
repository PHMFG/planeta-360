package br.com.rbs.portal.parceria;

import br.com.rbs.portal.comum.AcaoHistorico;
import br.com.rbs.portal.comum.ConflitoException;
import br.com.rbs.portal.comum.DadosInvalidosException;
import br.com.rbs.portal.comum.HistoricoService;
import br.com.rbs.portal.comum.UsuarioAutenticado;
import br.com.rbs.portal.comum.VerificadorDeAcesso;
import br.com.rbs.portal.consolidacao.ComprovacaoMensalService;
import br.com.rbs.portal.parceria.ParceriaDtos.CotaDto;
import br.com.rbs.portal.parceria.ParceriaRepositorio.AbaDaParceria;
import br.com.rbs.portal.parceria.ParceriaRepositorio.Cota;
import br.com.rbs.portal.parceria.ParceriaRepositorio.ParceriaBase;
import br.com.rbs.portal.parceria.ParceriaRepositorio.RelatorioDisponibilizado;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.AbaDoImpactoDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.AjusteManualDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.ImpactoDaTrocaDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.RelatorioDoImpactoDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.TrocaDeCotaDto;
import br.com.rbs.portal.parceria.TrocaDeCotaDtos.TrocaDeCotaRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R6.d3: a RBS troca a cota de uma parceria depois do cadastro, com a prévia do impacto (rotas 104 e 102).
 * [PERMISSAO] Só a RBS (a marca recebe 403 antes do corpo). [REGRA] A troca faz exatamente o que a prévia diz: as abas que a cota nova
 * não inclui fecham (continuam ocultas se estavam ocultas); as abas que a cota nova passa a incluir ficam como estavam (a RBS abre pelo
 * Perfil); cortesias, credenciais, plano comercial e investimento não mudam sozinhos. Com relatório disponibilizado a troca exige
 * {@code ciente: true} (409). [HISTORICO] Uma linha EDICAO (campo "cota", com a cota de antes e a de depois) e uma LIBERACAO_ABA por
 * aba que fechou. O Atlas, o relatório e os faltantes leem a cota da parceria a cada chamada, então a troca vale na leitura seguinte.
 */
@Service
public class TrocaDeCotaService {

    static final String ENCERRADA = "Esta parceria está encerrada e não aceita alterações.";

    private static final String PESQUISA = "PESQUISA";

    private final ParceriaService parcerias;
    private final ParceriaRepositorio repositorio;
    private final EmailAutorizadoRepositorio emails;
    private final VerificadorDeAcesso verificador;
    private final HistoricoService historico;

    public TrocaDeCotaService(ParceriaService parcerias, ParceriaRepositorio repositorio, EmailAutorizadoRepositorio emails,
                              VerificadorDeAcesso verificador, HistoricoService historico) {
        this.parcerias = parcerias;
        this.repositorio = repositorio;
        this.emails = emails;
        this.verificador = verificador;
        this.historico = historico;
    }

    /** Rota 104. Não grava nada. Para a mesma cota devolve {@code mudaACota: false} e as listas vazias. */
    @Transactional(readOnly = true)
    public ImpactoDaTrocaDto previa(UsuarioAutenticado usuario, long parceriaId, String para) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        ParceriaBase base = parcerias.buscar(parceriaId, false);
        Cota nova = cotaNova(para, "para");
        return impacto(base, nova);
    }

    /** Rota 102. */
    @Transactional
    public TrocaDeCotaDto trocar(UsuarioAutenticado usuario, long parceriaId, TrocaDeCotaRequisicao corpo,
                                 HttpServletRequest requisicao) {
        verificador.exigirRbs(usuario);
        verificador.exigirParceria(usuario, parceriaId);
        ParceriaBase antes = parcerias.buscar(parceriaId, true);   // trava a linha: duas trocas ao mesmo tempo esperam uma pela outra
        if ("ENCERRADA".equals(antes.situacao())) {
            throw new ConflitoException(ENCERRADA);
        }
        Cota nova = cotaNova(corpo == null ? null : corpo.cotaCodigo(), "cotaCodigo");
        if (nova.codigo().equals(antes.cotaCodigo())) {
            String frase = "A parceria já está na cota " + antes.cotaNome() + ".";
            throw new DadosInvalidosException(frase, Map.of("cotaCodigo", frase));
        }
        ImpactoDaTrocaDto impacto = impacto(antes, nova);
        if (impacto.exigeCiencia() && !Boolean.TRUE.equals(corpo.ciente())) {
            throw new ConflitoException("Esta parceria tem o relatório v" + impacto.relatorioDisponibilizado().versao()
                    + " disponibilizado. Trocar a cota muda o cabeçalho dele e os indicadores que a cota nova considera; "
                    + "confirme com ciente = true para continuar.");
        }

        List<AbaDaParceria> abas = repositorio.abasDaParceria(parceriaId);
        repositorio.atualizarCota(parceriaId, nova.id());
        for (AbaDoImpactoDto fecha : impacto.abasQueFecham()) {
            AbaDaParceria aba = abas.stream().filter(a -> a.codigo().equals(fecha.codigo())).findFirst().orElseThrow();
            repositorio.atualizarAba(parceriaId, aba.abaId(), false, aba.visivel());
            historico.registrar(AcaoHistorico.LIBERACAO_ABA, usuario, parceriaId, "aba", aba.abaId().longValue(),
                    aba.visivel() ? "liberada" : "oculta", aba.visivel() ? "fechada" : "oculta",
                    aba.nome() + ": a cota mudou de " + antes.cotaNome() + " para " + nova.nome(), requisicao);
        }
        historico.registrar(AcaoHistorico.EDICAO, usuario, parceriaId, "parceria", parceriaId, antes.cotaNome(), nova.nome(), "cota",
                requisicao);

        return new TrocaDeCotaDto(impacto, parcerias.montar(parcerias.buscar(parceriaId, false), emails.vigentes(parceriaId)));
    }

    // ---------------------------------------------------------------------

    private Cota cotaNova(String codigo, String campo) {
        String limpo = codigo == null ? "" : codigo.trim();
        // Caractere de controle (NUL, por exemplo) nunca é código de cota: 422 como o código desconhecido, e o banco nem é consultado.
        Optional<Cota> achada = limpo.chars().anyMatch(Character::isISOControl) ? Optional.empty() : repositorio.cotaPorCodigo(limpo);
        return achada.orElseThrow(() -> {
            String frase = limpo.isEmpty() ? "Informe a cota nova: MASTER, SUBCOTA_G ou SUBCOTA."
                    : "Cota não encontrada. Use MASTER, SUBCOTA_G ou SUBCOTA.";
            Map<String, String> campos = new LinkedHashMap<>();
            campos.put(campo, frase);
            return new DadosInvalidosException(frase, campos);
        });
    }

    private ImpactoDaTrocaDto impacto(ParceriaBase base, Cota nova) {
        Cota atual = repositorio.cotaPorCodigo(base.cotaCodigo()).orElseThrow();
        CotaDto cotaAtual = new CotaDto(atual.codigo(), atual.nome());
        CotaDto cotaNova = new CotaDto(nova.codigo(), nova.nome());
        if (atual.codigo().equals(nova.codigo())) {
            return new ImpactoDaTrocaDto(base.id(), cotaAtual, cotaNova, false, List.of(), List.of(), null, false, "SEM_MUDANCA",
                    "SEM_MUDANCA", List.of(), List.of());
        }
        Set<String> daNova = repositorio.abasDaCota(nova.id());
        Set<String> daAtual = repositorio.abasDaCota(atual.id());
        List<AbaDoImpactoDto> fecham = new ArrayList<>();
        List<AbaDoImpactoDto> passamAPoderAbrir = new ArrayList<>();
        for (AbaDaParceria aba : repositorio.abasDaParceria(base.id())) {
            if (!daNova.contains(aba.codigo()) && aba.liberada()) {
                fecham.add(new AbaDoImpactoDto(aba.codigo(), aba.nome()));
            }
            if (daNova.contains(aba.codigo()) && !daAtual.contains(aba.codigo())) {
                passamAPoderAbrir.add(new AbaDoImpactoDto(aba.codigo(), aba.nome()));
            }
        }
        RelatorioDisponibilizado disponibilizado = repositorio.relatorioDisponibilizado(base.id()).orElse(null);
        String pesquisa = muda(daAtual.contains(PESQUISA), daNova.contains(PESQUISA));
        Set<String> mensal = ComprovacaoMensalService.COTAS_COM_PLANILHA_MENSAL;
        String comprovacao = muda(mensal.contains(atual.codigo()), mensal.contains(nova.codigo()));

        List<String> avisos = new ArrayList<>();
        if (!fecham.isEmpty()) {
            avisos.add("Vão fechar: " + String.join(", ", fecham.stream().map(AbaDoImpactoDto::nome).toList())
                    + ". A marca deixa de ver essas abas e a RBS não consegue abri-las enquanto a cota não as incluir.");
        }
        if (!passamAPoderAbrir.isEmpty()) {
            avisos.add("Passam a poder abrir: " + String.join(", ", passamAPoderAbrir.stream().map(AbaDoImpactoDto::nome).toList())
                    + ". Elas ficam como estão (fechadas); a RBS as abre no Perfil.");
        }
        if ("DEIXA_DE_VALER".equals(pesquisa)) {
            avisos.add("A pesquisa deixa de valer: as perguntas e os resultados ficam guardados, mas saem do relatório e dos faltantes "
                    + "enquanto a cota não for Master.");
        } else if ("PASSA_A_VALER".equals(pesquisa)) {
            avisos.add("A pesquisa passa a valer: o relatório novo e os faltantes passam a considerar os indicadores da pesquisa.");
        }
        if ("DEIXA_DE_VALER".equals(comprovacao)) {
            avisos.add("A comprovação mensal deixa de valer: ela é das cotas Master, e a marca passa a receber 403 nela.");
        } else if ("PASSA_A_VALER".equals(comprovacao)) {
            avisos.add("A comprovação mensal passa a valer: a marca passa a vê-la na aba Mídia.");
        }
        if (disponibilizado != null) {
            avisos.add("O relatório v" + disponibilizado.versao() + " já está disponibilizado: ele guarda os números, mas o cabeçalho "
                    + "mostra a cota nova. Gere o relatório de novo depois da troca.");
        }
        List<AjusteManualDto> manuais = List.of(
                new AjusteManualDto("CORTESIAS", "As cortesias não mudam com a cota: ajuste a cota por setor e dia na aba Cortesias."),
                new AjusteManualDto("CREDENCIAMENTO", "As credenciais não mudam com a cota: ajuste-as na aba Credenciamento."),
                new AjusteManualDto("PERFIL", "O plano comercial e o investimento não mudam com a cota: revise-os no Perfil."));

        return new ImpactoDaTrocaDto(base.id(), cotaAtual, cotaNova, true, List.copyOf(fecham), List.copyOf(passamAPoderAbrir),
                disponibilizado == null ? null : new RelatorioDoImpactoDto(disponibilizado.versao(), disponibilizado.disponibilizadoEm()),
                disponibilizado != null, pesquisa, comprovacao, manuais, List.copyOf(avisos));
    }

    private static String muda(boolean valiaAntes, boolean valeDepois) {
        return valiaAntes == valeDepois ? "SEM_MUDANCA" : valeDepois ? "PASSA_A_VALER" : "DEIXA_DE_VALER";
    }
}

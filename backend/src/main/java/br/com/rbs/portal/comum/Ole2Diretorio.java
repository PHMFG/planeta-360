package br.com.rbs.portal.comum;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [R4.1] Conferência de segurança do Office antigo (.doc, .xls e .ppt, contêiner OLE2 / "Compound File"). O servidor lê SÓ o
 * diretório do contêiner (os nomes dos storages e streams) e nada mais: não abre o Word, a planilha nem o PowerPoint, não
 * descompacta fluxo nenhum e não usa biblioteca de Office. Duas perguntas: o contêiner é íntegro, e o diretório tem projeto VBA?
 *
 * <p>Arquivo malformado ou feito para travar o leitor é recusado cedo, sem alocar memória em função do que o cabeçalho diz: toda
 * lista é limitada ({@link #MAX_SETORES_DE_FAT}, {@link #MAX_SETORES_DE_DIFAT}, {@link #MAX_SETORES_DO_DIRETORIO},
 * {@link #MAX_ENTRADAS}), toda leitura confere o tamanho do arquivo, e a cadeia do diretório é percorrida com um conjunto de setores
 * já vistos, então um laço (diretório circular) para na primeira volta.
 *
 * <p>Limites conhecidos, registrados na rota 17 da {@code docs/api.md}: o XLM (macro antiga do Excel, "Excel 4.0", que vive em folha
 * de planilha dentro do fluxo {@code Workbook}) e a macro do PowerPoint (que vive dentro do fluxo {@code PowerPoint Document}) não
 * aparecem no diretório; só o projeto VBA aparece, e é ele que esta classe recusa.
 */
final class Ole2Diretorio {

    /** Resultado da leitura do diretório. */
    enum Resultado { SEM_MACRO, COM_MACRO, MALFORMADO }

    static final String FRASE_DE_MACRO = "Este arquivo contém macros (VBA), que o portal não aceita. Salve uma cópia sem macros "
            + "e envie de novo.";

    /** Até 2.048 setores de FAT (com setores de 512 bytes, 262 mil setores: bem mais que os 20 MB do limite de envio). */
    static final int MAX_SETORES_DE_FAT = 2048;
    static final int MAX_SETORES_DE_DIFAT = 64;
    /** O diretório de um documento real cabe em poucos setores; passar de 1.024 setores é arquivo feito para travar. */
    static final int MAX_SETORES_DO_DIRETORIO = 1024;
    static final int MAX_ENTRADAS = 4096;

    private static final int TAMANHO_DO_CABECALHO = 512;
    private static final int TAMANHO_DA_ENTRADA = 128;
    private static final int FIM_DA_CADEIA = -2;
    private static final int DIFAT_NO_CABECALHO = 109;

    /** Nomes (em minúsculas) de storage ou stream que só existem quando o arquivo carrega um projeto VBA. */
    private static final Set<String> NOMES_DE_VBA = Set.of("_vba_project_cur", "_vba_project", "vba", "macros");

    private Ole2Diretorio() {
    }

    static boolean ehOfficeAntigo(TipoDeArquivo tipo) {
        return tipo == TipoDeArquivo.DOC || tipo == TipoDeArquivo.XLS || tipo == TipoDeArquivo.PPT;
    }

    static Resultado inspecionar(byte[] b) {
        try {
            return ler(b);
        } catch (RuntimeException erro) {
            return Resultado.MALFORMADO;   // qualquer índice fora do arquivo ou número absurdo
        }
    }

    private static Resultado ler(byte[] b) {
        if (b.length < TAMANHO_DO_CABECALHO || u16(b, 0x1C) != 0xFFFE) {
            return Resultado.MALFORMADO;
        }
        int deslocamento = u16(b, 0x1E);
        if (deslocamento != 9 && deslocamento != 12) {
            return Resultado.MALFORMADO;
        }
        int tamanhoDoSetor = 1 << deslocamento;
        long setoresNoArquivo = (b.length - (long) tamanhoDoSetor + tamanhoDoSetor - 1) / tamanhoDoSetor;   // o último pode estar cortado
        int qtdFat = s32(b, 0x2C);
        int primeiroDoDiretorio = s32(b, 0x30);
        int primeiroDifat = s32(b, 0x44);
        int qtdDifat = s32(b, 0x48);
        if (qtdFat < 1 || qtdFat > MAX_SETORES_DE_FAT || qtdFat > setoresNoArquivo
                || qtdDifat < 0 || qtdDifat > MAX_SETORES_DE_DIFAT || qtdDifat > setoresNoArquivo) {
            return Resultado.MALFORMADO;
        }

        // Quais setores guardam a FAT: os 109 do cabeçalho e, se não bastarem, a cadeia de setores DIFAT (no máximo qtdDifat).
        List<Integer> setoresDaFat = new ArrayList<>();
        for (int i = 0; i < Math.min(qtdFat, DIFAT_NO_CABECALHO); i++) {
            setoresDaFat.add(s32(b, 0x4C + 4 * i));
        }
        int difat = primeiroDifat;
        for (int passo = 0; setoresDaFat.size() < qtdFat; passo++) {
            if (passo >= qtdDifat || difat < 0 || difat >= setoresNoArquivo) {
                return Resultado.MALFORMADO;
            }
            int base = inicioDoSetor(difat, tamanhoDoSetor);
            int porSetor = tamanhoDoSetor / 4 - 1;
            for (int i = 0; i < porSetor && setoresDaFat.size() < qtdFat; i++) {
                setoresDaFat.add(s32(b, base + 4 * i));
            }
            difat = s32(b, base + 4 * porSetor);
        }
        for (int setor : setoresDaFat) {
            if (setor < 0 || setor >= setoresNoArquivo || inicioDoSetor(setor, tamanhoDoSetor) + tamanhoDoSetor > b.length) {
                return Resultado.MALFORMADO;
            }
        }

        // O diretório: uma cadeia de setores na FAT, sem passar dos limites e sem voltar a um setor já visto.
        int entradasPorSetor = tamanhoDoSetor / TAMANHO_DA_ENTRADA;
        int porSetorDaFat = tamanhoDoSetor / 4;
        BitSet vistos = new BitSet();
        int setor = primeiroDoDiretorio;
        int lidos = 0;
        int entradas = 0;
        boolean temRaiz = false;
        boolean comMacro = false;
        while (setor != FIM_DA_CADEIA) {
            if (setor < 0 || setor >= setoresNoArquivo || vistos.get(setor) || ++lidos > MAX_SETORES_DO_DIRETORIO) {
                return Resultado.MALFORMADO;
            }
            vistos.set(setor);
            int base = inicioDoSetor(setor, tamanhoDoSetor);
            if (base + tamanhoDoSetor > b.length) {
                return Resultado.MALFORMADO;
            }
            for (int i = 0; i < entradasPorSetor; i++) {
                if (++entradas > MAX_ENTRADAS) {
                    return Resultado.MALFORMADO;
                }
                int entrada = base + i * TAMANHO_DA_ENTRADA;
                int tipo = b[entrada + 66] & 0xFF;
                if (tipo == 0) {
                    continue;
                }
                if (tipo != 1 && tipo != 2 && tipo != 5) {
                    return Resultado.MALFORMADO;
                }
                int bytesDoNome = u16(b, entrada + 64);
                if (bytesDoNome < 2 || bytesDoNome > 64 || bytesDoNome % 2 != 0) {
                    return Resultado.MALFORMADO;
                }
                if (tipo == 5) {
                    temRaiz = true;
                }
                String nome = new String(b, entrada, bytesDoNome - 2, StandardCharsets.UTF_16LE).toLowerCase(Locale.ROOT);
                if (NOMES_DE_VBA.contains(nome)) {
                    comMacro = true;
                }
            }
            int indiceDaFat = setor / porSetorDaFat;
            if (indiceDaFat >= setoresDaFat.size()) {
                return Resultado.MALFORMADO;
            }
            setor = s32(b, inicioDoSetor(setoresDaFat.get(indiceDaFat), tamanhoDoSetor) + 4 * (setor % porSetorDaFat));
        }
        if (!temRaiz) {
            return Resultado.MALFORMADO;
        }
        return comMacro ? Resultado.COM_MACRO : Resultado.SEM_MACRO;
    }

    /** O cabeçalho ocupa o primeiro setor (512 ou 4.096 bytes); o setor 0 vem depois dele. */
    private static int inicioDoSetor(int setor, int tamanhoDoSetor) {
        long inicio = (setor + 1L) * tamanhoDoSetor;
        if (inicio > Integer.MAX_VALUE - tamanhoDoSetor) {
            throw new IllegalArgumentException("setor fora do arquivo");
        }
        return (int) inicio;
    }

    private static int u16(byte[] b, int posicao) {
        return (b[posicao] & 0xFF) | ((b[posicao + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] b, int posicao) {
        return (b[posicao] & 0xFF) | ((b[posicao + 1] & 0xFF) << 8) | ((b[posicao + 2] & 0xFF) << 16) | ((b[posicao + 3] & 0xFF) << 24);
    }
}

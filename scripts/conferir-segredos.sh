#!/bin/bash
# Confere que nenhuma chave dos provedores do Atlas vazou: no Git, nos logs da API, em target/ e no traço de um banco. [Planeta 360]
#
# Uso: scripts/conferir-segredos.sh [--segredos ARQUIVO] [--logs CAMINHO]... [--banco BANCO] [--historico]
#   --segredos ARQUIVO  o arquivo de chaves (padrão: backend/config/segredos.local.properties, nesta pasta ou na principal)
#   --logs CAMINHO      mais um arquivo ou pasta de log para procurar (pode repetir)
#   --banco BANCO       um banco (nome local, ou URI postgresql://) cujas tabelas atlas_traco, atlas_tool_call, atlas_mensagem e
#                       atlas_decisao são varridas; sem ele, o banco não é conferido
#   --historico         procura também em todo o histórico do Git (mais lento)
#
# O que procura: o VALOR de cada chave do arquivo de segredos (as variáveis com KEY, SECRET, TOKEN, SENHA, PASSWORD ou CHAVE no nome) e
# os formatos conhecidos de chave (Google AIza, sk-, chave privada, token do GitHub, AWS, Slack), em: git ls-files, .atlas-dev/ (logs e
# .jar da API), backend/target/, e no dump do banco. Um formato que traz uma palavra de chave falsa (DeTeste, NaoEh, mentira, Exemplo,
# Fake, Falsa, Dummy) é de teste e não conta: os testes usam chaves inventadas no formato de verdade; o VALOR de chave de verdade conta
# sempre, em qualquer lugar. Os valores NUNCA aparecem na saída, nem em linha de comando: vão para um arquivo
# temporário (modo 0600, apagado ao sair) e o grep só diz EM QUE ARQUIVO achou (-l) ou QUANTAS linhas (-c). Este é o único script
# autorizado a ler o arquivo de segredos.
#
# Sai com 0 se está limpo, 1 se achou alguma coisa (dizendo onde, sem repetir o valor) e 2 se não conseguiu conferir.

set -u
set +x
umask 077

RAIZ="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || exit 2
# Num worktree, a pasta principal é a que tem o arquivo de segredos e os logs de quem sobe a API.
PRINCIPAL="$(dirname "$(git -C "$RAIZ" rev-parse --path-format=absolute --git-common-dir)")"

SEGREDOS=""
BANCO=""
HISTORICO=0
EXTRAS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --segredos) [ "$#" -ge 2 ] || { echo "falta o valor de --segredos" >&2; exit 2; }; SEGREDOS="$2"; shift 2 ;;
    --logs) [ "$#" -ge 2 ] || { echo "falta o valor de --logs" >&2; exit 2; }; EXTRAS+=("$2"); shift 2 ;;
    --banco) [ "$#" -ge 2 ] || { echo "falta o valor de --banco" >&2; exit 2; }; BANCO="$2"; shift 2 ;;
    --historico) HISTORICO=1; shift ;;
    -h | --help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "argumento desconhecido: $1 (veja --help)" >&2; exit 2 ;;
  esac
done

if [ -z "$SEGREDOS" ]; then
  for candidato in "$RAIZ/backend/config/segredos.local.properties" "$PRINCIPAL/backend/config/segredos.local.properties"; do
    if [ -f "$candidato" ]; then SEGREDOS="$candidato"; break; fi
  done
fi

TMP="$(mktemp -d)" || exit 2
trap 'rm -rf "$TMP"' EXIT
VALORES="$TMP/valores"      # um valor por linha, texto fixo
NOMES="$TMP/nomes"          # os nomes das variáveis lidas (nunca os valores)
CURTOS="$TMP/curtos"        # variáveis com valor curto demais para procurar sem falso alarme
VAZIOS="$TMP/vazios"
PADROES="$TMP/padroes"      # formatos de chave, expressão regular estendida
ACHADOS="$TMP/achados"
FALSAS='DeTeste|NaoEh|mentira|Exemplo|Fake|Falsa|Dummy'
: > "$VALORES"; : > "$NOMES"; : > "$CURTOS"; : > "$VAZIOS"; : > "$ACHADOS"

cat > "$PADROES" <<'PADROES_DE_CHAVE'
AIza[0-9A-Za-z_-]{35}
sk-[A-Za-z0-9_-]{24,}
-----BEGIN [A-Z ]*PRIVATE KEY-----
gh[pousr]_[A-Za-z0-9]{36,}
github_pat_[A-Za-z0-9_]{60,}
AKIA[0-9A-Z]{16}
xox[baprs]-[A-Za-z0-9-]{10,}
PADROES_DE_CHAVE

# ---------------------------------------------------------------------
# 1. Os valores das chaves (sem imprimi-los)
# ---------------------------------------------------------------------
if [ -n "$SEGREDOS" ] && [ -f "$SEGREDOS" ]; then
  awk -v valores="$VALORES" -v nomes="$NOMES" -v curtos="$CURTOS" -v vazios="$VAZIOS" '
    /^[[:space:]]*#/ { next }
    !/=/ { next }
    {
      nome = substr($0, 1, index($0, "=") - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", nome)
      if (toupper(nome) !~ /(KEY|SECRET|TOKEN|SENHA|PASSWORD|CHAVE)/) next
      valor = substr($0, index($0, "=") + 1)
      gsub(/\r/, "", valor)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", valor)
      if (valor ~ /^".*"$/ || valor ~ /^\047.*\047$/) valor = substr(valor, 2, length(valor) - 2)
      if (valor == "") { print nome >> vazios; next }
      if (length(valor) < 8) { print nome >> curtos; next }
      print nome >> nomes
      print valor >> valores
    }' "$SEGREDOS"
  echo "Arquivo de segredos: lido ($(wc -l < "$NOMES" | tr -d ' ') chave(s) com valor; os valores não são mostrados)."
  if [ -s "$NOMES" ]; then echo "  variáveis: $(tr '\n' ' ' < "$NOMES")"; fi
  if [ -s "$VAZIOS" ]; then echo "  sem valor (nada a procurar): $(tr '\n' ' ' < "$VAZIOS")"; fi
  if [ -s "$CURTOS" ]; then echo "  valor curto demais para procurar sem falso alarme: $(tr '\n' ' ' < "$CURTOS")"; fi
else
  echo "Arquivo de segredos: não encontrado. Só os formatos de chave serão procurados." >&2
fi

achar() {
  echo "ACHOU: $1"
  echo "$1" >> "$ACHADOS"
}

# Lê um texto da entrada e conta os trechos no formato de uma chave de verdade (os de chave falsa de teste ficam de fora). Só o
# número sai: o trecho em si nunca passa do pipe.
contar_formatos() {
  grep -a -o -E -f "$PADROES" 2>/dev/null | grep -a -v -i -E "$FALSAS" | wc -l | tr -d ' '
}

# Procura nos arquivos de um caminho (arquivo ou pasta) o valor das chaves e os formatos de chave.
procurar_no_caminho() {
  local rotulo="$1" alvo="$2" achado
  [ -e "$alvo" ] || return 0
  if [ -s "$VALORES" ]; then
    while IFS= read -r achado; do achar "$rotulo: o valor de uma chave está em $achado"; done \
      < <(grep -r -a -l -F -f "$VALORES" -- "$alvo" 2>/dev/null)
  fi
  while IFS= read -r achado; do
    if [ "$(contar_formatos < "$achado")" != "0" ]; then achar "$rotulo: um formato de chave está em $achado"; fi
  done < <(grep -r -a -l -E -f "$PADROES" -- "$alvo" 2>/dev/null)
  # o .jar é um zip: as entradas comprimidas só aparecem descomprimidas
  while IFS= read -r achado; do
    if [ -s "$VALORES" ] && [ "$(unzip -p "$achado" 2>/dev/null | grep -a -c -F -f "$VALORES")" != "0" ]; then
      achar "$rotulo: o valor de uma chave está dentro de $achado"
    fi
    if [ "$(unzip -p "$achado" 2>/dev/null | contar_formatos)" != "0" ]; then
      achar "$rotulo: um formato de chave está dentro de $achado"
    fi
  done < <(find "$alvo" -name '*.jar' -type f 2>/dev/null)
}

# ---------------------------------------------------------------------
# 2. O Git: os arquivos versionados (e, com --historico, todas as revisões)
# ---------------------------------------------------------------------
echo "Procurando nos arquivos versionados (git ls-files)…"
if [ -s "$VALORES" ]; then
  while IFS= read -r achado; do achar "git: o valor de uma chave está em $achado"; done \
    < <(git -C "$RAIZ" ls-files -z | (cd "$RAIZ" && xargs -0 grep -a -l -F -f "$VALORES" 2>/dev/null))
fi
while IFS= read -r achado; do
  if [ "$(contar_formatos < "$RAIZ/$achado")" != "0" ]; then achar "git: um formato de chave está em $achado"; fi
done < <(git -C "$RAIZ" ls-files -z | (cd "$RAIZ" && xargs -0 grep -a -l -E -f "$PADROES" 2>/dev/null))

if [ "$HISTORICO" = "1" ]; then
  echo "Procurando em todo o histórico do Git (pode demorar)…"
  if [ -s "$VALORES" ]; then
    while IFS= read -r achado; do achar "histórico: o valor de uma chave está em $achado"; done \
      < <(git -C "$RAIZ" rev-list --all | xargs git -C "$RAIZ" grep -a -l -F -f "$VALORES" 2>/dev/null)
  fi
  # o mesmo arquivo repete em centenas de revisões: cada conteúdo (blob) é conferido uma vez
  : > "$TMP/blobs"
  while IFS= read -r achado; do
    blob="$(git -C "$RAIZ" rev-parse "$achado" 2>/dev/null)"
    if [ -n "$blob" ] && grep -q -x "$blob" "$TMP/blobs"; then continue; fi
    echo "$blob" >> "$TMP/blobs"
    if [ "$(git -C "$RAIZ" cat-file -p "$blob" 2>/dev/null | contar_formatos)" != "0" ]; then
      achar "histórico: um formato de chave está em $achado"
    fi
  done < <(git -C "$RAIZ" rev-list --all | xargs git -C "$RAIZ" grep -a -l -E -f "$PADROES" 2>/dev/null)
fi

# ---------------------------------------------------------------------
# 3. Os logs da API, target/ e o que mais foi pedido
# ---------------------------------------------------------------------
echo "Procurando nos logs da API (.atlas-dev/) e em backend/target/…"
alvos=("$RAIZ/.atlas-dev" "$RAIZ/backend/target")
if [ "$PRINCIPAL" != "$RAIZ" ]; then
  alvos+=("$PRINCIPAL/.atlas-dev" "$PRINCIPAL/backend/target")
fi
for alvo in "${alvos[@]}"; do
  procurar_no_caminho "log/build" "$alvo"
done
for alvo in ${EXTRAS[@]+"${EXTRAS[@]}"}; do
  if [ ! -e "$alvo" ]; then echo "  aviso: $alvo não existe" >&2; continue; fi
  procurar_no_caminho "log" "$alvo"
done

# ---------------------------------------------------------------------
# 4. O banco: o traço e as chamadas de ferramenta
# ---------------------------------------------------------------------
if [ -n "$BANCO" ]; then
  PSQL="$(command -v psql || echo /opt/homebrew/opt/postgresql@18/bin/psql)"
  [ -x "$PSQL" ] || { echo "psql não encontrado" >&2; exit 2; }
  echo "Procurando no traço do banco $(printf '%s' "$BANCO" | sed -E 's#(postgres(ql)?://)[^@/]*@#\1…@#')…"
  for tabela in atlas_traco atlas_tool_call atlas_mensagem atlas_decisao; do
    existe="$("$PSQL" -X -A -t -d "$BANCO" -c "SELECT to_regclass('public.$tabela') IS NOT NULL" 2>/dev/null)" || {
      echo "não consegui abrir o banco $BANCO" >&2; exit 2; }
    if [ "$existe" != "t" ]; then echo "  (a tabela $tabela não existe neste banco)"; continue; fi
    if [ -s "$VALORES" ]; then
      n="$("$PSQL" -X -A -t -d "$BANCO" -c "SELECT row_to_json(t)::text FROM $tabela t" | grep -a -c -F -f "$VALORES")"
      if [ "$n" != "0" ]; then achar "banco: o valor de uma chave está em $n linha(s) de $tabela"; fi
    fi
    n="$("$PSQL" -X -A -t -d "$BANCO" -c "SELECT row_to_json(t)::text FROM $tabela t" | contar_formatos)"
    if [ "$n" != "0" ]; then achar "banco: $n formato(s) de chave em $tabela"; fi
  done
fi

# ---------------------------------------------------------------------
echo
if [ -s "$ACHADOS" ]; then
  echo "Achou $(wc -l < "$ACHADOS" | tr -d ' ') lugar(es) com chave. Troque a chave e tire o texto do lugar (os valores não foram mostrados)." >&2
  exit 1
fi
echo "Limpo: nenhuma chave nos arquivos versionados, nos logs, em target/${BANCO:+ nem no traço do banco}."
exit 0

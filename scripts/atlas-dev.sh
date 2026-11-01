#!/bin/bash
# API do perfil dev em segundo plano, para o avaliador e os testes no navegador. [Planeta 360]
# Uso: scripts/atlas-dev.sh start | stop | status | log [linhas]
#   ATLAS_DEV_PORTA  porta da API (padrão 8080). Cada porta tem o próprio processo.
#   ATLAS_PERFIL     o perfil do Spring (padrão dev; demo sobe como na apresentação, sem as rotas de debug da chave).
#   As outras variáveis (PORTAL_BD_URL, ATLAS_TETO_DIARIO...) passam direto para a API.
# As chaves vêm de backend/config/segredos.local.properties: a API o lê ao subir a partir de backend/.
# O .jar, o PID e o log ficam em .atlas-dev/ (fora do Git). O stop derruba só o processo que este script subiu.

set -u
RAIZ="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || exit 1
PORTA="${ATLAS_DEV_PORTA:-8080}"
PASTA="$RAIZ/.atlas-dev"
JAR="$PASTA/portal-$PORTA.jar"
PID="$PASTA/api-$PORTA.pid"
LOG="$PASTA/api-$PORTA.log"
ESPERA_SEGUNDOS=120

# O PID só vale se o processo ainda é o nosso: o sistema reaproveita números. Confere só o fim do caminho,
# porque o macOS pode devolver os acentos da pasta noutra forma de Unicode.
pid_no_ar() {
  [ -f "$PID" ] || return 1
  local pid
  pid="$(cat "$PID")"
  ps -ww -p "$pid" -o command= 2>/dev/null | grep -qF "${JAR#"$RAIZ"/}" && echo "$pid"
}

iniciar() {
  local pid
  if pid="$(pid_no_ar)"; then
    echo "a API já está no ar: PID $pid, porta $PORTA"
    return 0
  fi
  if lsof -nP -iTCP:"$PORTA" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "a porta $PORTA está ocupada por outro processo. Use outra: ATLAS_DEV_PORTA=8090 $0 start" >&2
    return 1
  fi
  mkdir -p "$PASTA"
  echo "montando o .jar da API…"
  (cd "$RAIZ/backend" && ./mvnw -q -B package -DskipTests) || { echo "o build da API falhou" >&2; return 1; }
  cp "$RAIZ"/backend/target/portal-*.jar "$JAR"
  (
    cd "$RAIZ/backend" || exit 1
    SERVER_PORT="$PORTA" SPRING_PROFILES_ACTIVE="${ATLAS_PERFIL:-dev}" nohup java -jar "$JAR" < /dev/null > "$LOG" 2>&1 &
    echo $! > "$PID"
  )
  for _ in $(seq 1 "$ESPERA_SEGUNDOS"); do
    if grep -q "Started PortalApplication" "$LOG" 2>/dev/null; then
      echo "API no ar: PID $(cat "$PID"), porta $PORTA, log em $LOG"
      return 0
    fi
    if ! pid_no_ar > /dev/null; then
      echo "a API não subiu: veja $LOG" >&2
      return 1
    fi
    sleep 1
  done
  echo "a API não ficou pronta em $ESPERA_SEGUNDOS s: veja $LOG" >&2
  return 1
}

parar() {
  local pid
  if ! pid="$(pid_no_ar)"; then
    rm -f "$PID"
    echo "a API desta porta ($PORTA) não está no ar"
    return 0
  fi
  kill "$pid"
  for _ in $(seq 1 30); do
    pid_no_ar > /dev/null || break
    sleep 1
  done
  if pid_no_ar > /dev/null; then
    echo "a API não parou: PID $pid" >&2
    return 1
  fi
  rm -f "$PID"
  echo "API parada"
}

case "${1:-}" in
  start) iniciar ;;
  stop) parar ;;
  status)
    if pid="$(pid_no_ar)"; then echo "no ar: PID $pid, porta $PORTA"; else echo "parada (porta $PORTA)"; fi
    ;;
  log) tail -n "${2:-100}" "$LOG" ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac

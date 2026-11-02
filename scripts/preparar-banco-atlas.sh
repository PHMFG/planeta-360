#!/bin/bash
# Prepara o PostgreSQL para o Atlas: pgvector, schema extensoes e papel atlas_ro. [Planeta 360]
# Pode rodar de novo sem problema. Banco que ainda não existe é criado, com o dono portal.
# Uso: scripts/preparar-banco-atlas.sh [banco...]    sem argumento, prepara portal e portal_teste
#
# Precisa de um superusuário, porque o vector não é uma extensão "trusted". O psql usa o usuário atual, como no
# macOS com Homebrew; em outro ambiente, informe PGUSER=postgres (e PGHOST e PGPASSWORD, se for o caso).
#
# A extensão fica no schema extensoes, com dono superusuário: assim o clean do Flyway, que apaga o schema public
# nos testes, não a leva junto. O unaccent não entra aqui: a V11 o cria em public, como portal.

set -euo pipefail

VERSAO_MINIMA="0.8.0" # hnsw.iterative_scan
[ "$#" -gt 0 ] || set -- portal portal_teste
for banco in "$@"; do
  case "$banco" in
    *[!a-z0-9_]* | "" | [0-9]*) echo "nome de banco inválido: $banco" >&2; exit 1 ;;
  esac
done

PSQL="$(command -v psql || echo /opt/homebrew/opt/postgresql@18/bin/psql)"
[ -x "$PSQL" ] || { echo "psql não encontrado: instale o PostgreSQL (README, Antes de começar)" >&2; exit 1; }

consulta() { "$PSQL" -d postgres -v ON_ERROR_STOP=1 -Atc "$1"; }
versao_disponivel() { consulta "SELECT default_version FROM pg_available_extensions WHERE name = 'vector'"; }

if [ "$(consulta "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")" != "t" ]; then
  echo "o usuário do psql ($(consulta "SELECT current_user")) não é superusuário: informe PGUSER=postgres" >&2
  exit 1
fi
if [ -z "$(consulta "SELECT 1 FROM pg_roles WHERE rolname = 'portal'")" ]; then
  echo "o usuário portal não existe: rode o passo 1 de 'Como rodar' no README" >&2
  exit 1
fi

VERSAO="$(versao_disponivel)"
if [ -z "$VERSAO" ]; then
  if ! command -v brew > /dev/null; then
    echo "o pgvector não está instalado neste PostgreSQL: https://github.com/pgvector/pgvector#installation" >&2
    exit 1
  fi
  echo "instalando o pgvector…"
  HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_UPGRADE=1 brew install pgvector
  VERSAO="$(versao_disponivel)"
  [ -n "$VERSAO" ] || { echo "o pgvector do Homebrew não atende este PostgreSQL" >&2; exit 1; }
fi
if [ "$(printf '%s\n%s\n' "$VERSAO_MINIMA" "$VERSAO" | sort -V | head -1)" != "$VERSAO_MINIMA" ]; then
  echo "o pgvector $VERSAO é antigo: o Atlas precisa da $VERSAO_MINIMA ou mais nova" >&2
  exit 1
fi

"$PSQL" -d postgres -v ON_ERROR_STOP=1 -q <<'SQL'
SET client_min_messages = warning;
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'atlas_ro') THEN
    CREATE ROLE atlas_ro NOLOGIN NOBYPASSRLS;
  END IF;
END
$$;
GRANT atlas_ro TO portal;
SQL

for banco in "$@"; do
  if [ -z "$(consulta "SELECT 1 FROM pg_database WHERE datname = '$banco'")" ]; then
    "$PSQL" -d postgres -v ON_ERROR_STOP=1 -qc "CREATE DATABASE $banco OWNER portal"
  fi
  "$PSQL" -d "$banco" -v ON_ERROR_STOP=1 -q <<SQL
SET client_min_messages = warning;
CREATE SCHEMA IF NOT EXISTS extensoes;
CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensoes;
GRANT USAGE ON SCHEMA extensoes TO portal, atlas_ro;
ALTER DATABASE $banco SET search_path = public, extensoes;
SQL
  # Numa conexão nova, como portal: é o que a API e a V11 vão encontrar.
  "$PSQL" -d "$banco" -v ON_ERROR_STOP=1 -q -c "SET ROLE portal" -c "SELECT '[1,2,3]'::vector(3)" > /dev/null
  echo "$banco: vector $("$PSQL" -d "$banco" -Atc "SELECT extversion FROM pg_extension WHERE extname = 'vector'") em extensoes, papel atlas_ro pronto"
done

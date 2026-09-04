#!/usr/bin/env bash
# macOS / Linux 용 메디바이스 DB 초기화 스크립트
# 실행 방법: ./database/setup_db.sh
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$DIR/sql"

# .env 파일 로드 (존재하는 경우)
if [ -f "$DIR/.env" ]; then
  export $(grep -v '^#' "$DIR/.env" | xargs -0)
elif [ -f "$DIR/../.env" ]; then
  export $(grep -v '^#' "$DIR/../.env" | xargs -0)
fi

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-medivice_db}"
PGUSER="${PGUSER:-postgres}"

echo "=========================================================="
echo " [MediVice] DB 스키마 및 백엔드 확장 설치 시작"
echo " 대상: $PGUSER@$PGHOST:$PGPORT/$PGDATABASE"
echo "=========================================================="

if command -v psql >/dev/null 2>&1; then
  echo "▶ psql 명령을 감지했습니다. SQL 파일을 순차 실행합니다."

  # 데이터베이스가 없으면 생성 시도
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$PGDATABASE'" | grep -q 1 || \
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "CREATE DATABASE $PGDATABASE;"

  echo "  1) 01_schema_ddl.sql 실행 중..."
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$SQL_DIR/01_schema_ddl.sql" > /dev/null

  echo "  2) 02_seed_code.sql 실행 중..."
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$SQL_DIR/02_seed_code.sql" > /dev/null

  echo "  3) 03_medilight_views.sql 실행 중..."
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$SQL_DIR/03_medilight_views.sql" > /dev/null

  echo "  4) 05_backend_extensions.sql 실행 중..."
  PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$SQL_DIR/05_backend_extensions.sql" > /dev/null

  echo "=========================================================="
  echo " [MediVice] DB 스키마 및 백엔드 확장 설치가 완료되었습니다!"
  echo "=========================================================="
elif command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1; then
  PY_CMD=$(command -v python3 || command -v python)
  echo "▶ psql 명령이 없어 Python ($PY_CMD) 스크립트로 실행합니다."
  "$PY_CMD" "$DIR/src/setup_db.py"
else
  echo "오류: psql 또는 python(psycopg2 포함)이 설치되어 있어야 합니다."
  exit 1
fi

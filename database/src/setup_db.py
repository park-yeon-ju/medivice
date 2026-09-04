"""
작성자 : 박기준
작성목적 : psql 없이 DB와 스키마를 만든다.
          Windows PostgreSQL 설치본은 bin 폴더를 PATH에 넣지 않는 경우가 많아
          psql 명령이 안 잡힌다. 파이썬(psycopg2)만으로 같은 일을 하도록 해 둔다.
작성일 : 2026-09-02
실행    : python src/setup_db.py           (DB 생성 + 01~03 실행)
          python src/setup_db.py --demo    (위 + 04 데모 시나리오까지 실행하고 결과 출력)
          python src/setup_db.py --all     (위 + load_postgres 까지 한 번에)
"""
import re
import sys
from pathlib import Path

from psycopg2 import sql
from dotenv import load_dotenv

load_dotenv()
import config  # noqa: E402

SQL_DIR = config.BASE_DIR / "sql"
STEPS = [
    "01_schema_ddl.sql",
    "02_seed_code.sql",
    "03_medilight_views.sql",
    "05_backend_extensions.sql",
]


def connect(dbname):
    return config.connect_db(dbname)


def create_database():
    """postgres 기본 DB에 붙어 대상 DB를 만든다(이미 있으면 넘어간다)."""
    target = config.DB["dbname"]
    conn = connect("postgres")
    conn.autocommit = True                     # CREATE DATABASE 는 트랜잭션 안에서 안 된다
    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (target,))
        if cur.fetchone():
            print(f"  · 데이터베이스 {target} 이미 있음")
        else:
            cur.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(target)))
            print(f"  · 데이터베이스 {target} 생성")
    conn.close()


def run_sql_file(conn, path):
    """psql 메타명령(\\echo, \\pset 등)이 없는 순수 SQL 파일을 통째로 실행한다."""
    text = path.read_text(encoding="utf-8")
    with conn.cursor() as cur:
        cur.execute(text)
    conn.commit()
    print(f"  · {path.name} 실행 완료")


def print_table(cur):
    """SELECT 결과를 psql 비슷하게 찍는다."""
    cols = [d.name for d in cur.description]
    rows = cur.fetchall()
    if not rows:
        print("    (0 rows)")
        return
    data = [[("" if v is None else str(v)) for v in r] for r in rows]
    width = [max(len(c), *(len(r[i]) for r in data)) for i, c in enumerate(cols)]
    line = "  +" + "+".join("-" * (w + 2) for w in width) + "+"
    print(line)
    print("  | " + " | ".join(c.ljust(w) for c, w in zip(cols, width)) + " |")
    print(line)
    for r in data[:30]:
        print("  | " + " | ".join(v.ljust(w) for v, w in zip(r, width)) + " |")
    print(line)
    print(f"    ({len(rows)} rows)" + (" — 상위 30행만 표시" if len(rows) > 30 else ""))


def run_demo(conn):
    """04_demo_scenario.sql 은 psql 메타명령을 쓰므로 줄 단위로 해석해 실행한다."""
    path = SQL_DIR / "04_demo_scenario.sql"
    buf = []
    with conn.cursor() as cur:
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line.startswith("\\echo"):
                m = re.match(r"\\echo\s+'(.*)'", line)
                print("\n" + (m.group(1) if m else line))
                continue
            if line.startswith("\\"):
                continue                        # \pset 등은 무시
            if line.startswith("--") or not line:
                continue                        # 전체줄 주석·빈 줄은 문장에 섞지 않는다
            buf.append(raw)
            if line.endswith(";"):
                stmt = "\n".join(buf).strip()
                buf = []
                if not stmt:
                    continue
                cur.execute(stmt)
                if cur.description:
                    print_table(cur)
    conn.commit()


def main():
    args = set(sys.argv[1:])
    demo_only = "--demo" in args and "--all" not in args and "--reset" not in args

    print("[DB 준비] psql 없이 진행합니다")
    print(f"  대상: {config.DB['user']}@{config.DB['host']}:{config.DB['port']}"
          f"/{config.DB['dbname']}")

    if demo_only:
        # 스키마·데이터를 그대로 두고 데모만 다시 돌린다.
        # (--demo 가 스키마를 재생성하면 DROP TABLE 로 적재 데이터가 지워진다)
        conn = connect(config.DB["dbname"])
        # 데이터가 실제로 있는지 확인한다. 비어 있으면 안내하고 멈춘다.
        with conn.cursor() as cur:
            cur.execute("SET search_path TO medivice, public")
            cur.execute("SELECT count(*) FROM products")
            n = cur.fetchone()[0]
        if n == 0:
            print("\n  products 테이블이 비어 있습니다. 먼저 데이터를 적재하세요:")
            print("    python src/load_postgres.py")
            print("  또는 전체를 한 번에:")
            print("    python src/setup_db.py --all")
            conn.close()
            sys.exit(1)
        print(f"  · 기존 데이터 유지 (products {n:,}행)")
        print("\n[데모 시나리오]")
        run_demo(conn)
        conn.close()
        print("\n완료.")
        return

    # --all / 인자 없음 / --reset : 스키마부터 새로 만든다
    create_database()
    conn = connect(config.DB["dbname"])
    for name in STEPS:
        run_sql_file(conn, SQL_DIR / name)

    if "--all" in args:
        conn.close()
        print("\n[적재]")
        import load_postgres
        load_postgres.main()
        conn = connect(config.DB["dbname"])
        print("\n[데모 시나리오]")
        run_demo(conn)

    conn.close()
    print("\n완료.")
    if "--all" not in args:
        print("이어서:  python src/load_postgres.py  →  python src/setup_db.py --demo")


if __name__ == "__main__":
    main()

@echo off
rem Windows용 메디바이스 DB 초기화 스크립트
rem 실행 방법: database\setup_db.bat
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "SQL_DIR=%DIR%sql"

if exist "%DIR%.env" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%DIR%.env") do (
    if not "%%A"=="" if not "%%A:~0,1%"=="#" set "%%A=%%B"
  )
)

if "%PGHOST%"=="" set "PGHOST=localhost"
if "%PGPORT%"=="" set "PGPORT=5432"
if "%PGDATABASE%"=="" set "PGDATABASE=medivice_db"
if "%PGUSER%"=="" set "PGUSER=postgres"

echo ==========================================================
echo  [MediVice] DB 스키마 및 백엔드 확장 설치 시작 (Windows)
echo  대상: %PGUSER%@%PGHOST%:%PGPORT%/%PGDATABASE%
echo ==========================================================

python --version >nul 2>&1
if %errorlevel% equ 0 (
  echo ▶ 파이썬 스크립트(src\setup_db.py)로 실행합니다...
  python "%DIR%src\setup_db.py"
  goto :done
)

psql --version >nul 2>&1
if %errorlevel% equ 0 (
  echo ▶ psql을 사용하여 SQL 파일을 순차 실행합니다...
  psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%SQL_DIR%\01_schema_ddl.sql"
  psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%SQL_DIR%\02_seed_code.sql"
  psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%SQL_DIR%\03_medilight_views.sql"
  psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%SQL_DIR%\05_backend_extensions.sql"
  goto :done
)

echo 오류: python 또는 psql이 PATH에 등록되어 있어야 합니다.
exit /b 1

:done
echo ==========================================================
echo  [MediVice] DB 스키마 및 백엔드 확장 설치 완료!
echo ==========================================================

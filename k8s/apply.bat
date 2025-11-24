@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Script/Root path
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%\..") do set "ROOT_DIR=%%~fI"

rem ---------------- Help ----------------
:show_help
echo Usage: %~n0 [-n^|--namespace ^<namespace^>] [-i^|--image ^<image^>]
echo.
echo Options:
echo   -n, --namespace   kubectl 네임스페이스 (우선: CLI ^> .env ^> default)
echo   -i, --image       컨테이너 이미지 (우선: CLI ^> .env, 필수)
echo   -h, --help        도움말
echo.
echo Notes:
echo   이미지 값이 없으면 스크립트는 실패합니다.
goto :eof

rem ---------------- Args ----------------
set "NAMESPACE="
set "IMAGE_CLI="

:parse_args
if "%~1"=="" goto end_parse
if /I "%~1"=="-n"        goto ns_opt
if /I "%~1"=="--namespace" goto ns_opt
if /I "%~1"=="-i"        goto img_opt
if /I "%~1"=="--image"   goto img_opt
if /I "%~1"=="-h"        goto help_opt
if /I "%~1"=="--help"    goto help_opt
echo ERROR: 알 수 없는 옵션: %~1 1>&2
call :show_help
exit /b 1

:ns_opt
if "%~2"=="" (
  set "NAMESPACE=default"
  shift
  goto parse_args
)
set "NEXT=%~2"
echo %NEXT%| findstr /b "-" >nul
if not errorlevel 1 (
  set "NAMESPACE=default"
  shift
) else (
  set "NAMESPACE=%~2"
  shift
  shift
)
goto parse_args

:img_opt
if "%~2"=="" (
  rem 값이 없으면 무시하고 계속 (최종 검증에서 실패 처리)
  shift
  goto parse_args
)
set "NEXT=%~2"
echo %NEXT%| findstr /b "-" >nul
if not errorlevel 1 (
  shift
) else (
  set "IMAGE_CLI=%~2"
  shift
  shift
)
goto parse_args

:help_opt
call :show_help
exit /b 0

:end_parse

rem ---------------- Requires ----------------
where kubectl >nul 2>&1
if errorlevel 1 (
  echo ERROR: kubectl이 PATH에 없습니다. 1>&2
  exit /b 1
)
where powershell >nul 2>&1
if errorlevel 1 (
  echo ERROR: PowerShell이 필요합니다. 1>&2
  exit /b 1
)

rem ---------------- Load .env ----------------
if not exist "%ROOT_DIR%\.env" (
  echo ERROR: "%ROOT_DIR%\.env" 파일이 없습니다. 1>&2
  exit /b 1
)
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT_DIR%\.env") do (
  set "KEY=%%A"
  set "VAL=%%B"
  if /I "!KEY:~0,7!"=="export " set "KEY=!KEY:~7!"
  if defined VAL (
    if "!VAL:~0,1!"=="\"" set "VAL=!VAL:~1,-1!"
  )
  set "!KEY!=!VAL!"
)

rem ---------------- Resolve namespace/image ----------------
set "NAMESPACE_EFFECTIVE=%NAMESPACE%"
if not defined NAMESPACE_EFFECTIVE set "NAMESPACE_EFFECTIVE=%K8S_NAMESPACE%"
if not defined NAMESPACE_EFFECTIVE set "NAMESPACE_EFFECTIVE=default"

set "IMAGE_EFFECTIVE=%IMAGE_CLI%"
if not defined IMAGE_EFFECTIVE set "IMAGE_EFFECTIVE=%IMAGE%"
if not defined IMAGE_EFFECTIVE (
  echo ERROR: 이미지가 지정되지 않았습니다. --image 옵션 또는 .env의 IMAGE를 설정하세요. 1>&2
  exit /b 1
)
set "IMAGE=%IMAGE_EFFECTIVE%"

rem ---------------- Render and apply helper ----------------
rem %1 = input template path
:render_and_apply
set "INFILE=%~1"
if not exist "%INFILE%" goto :eof
set "OUTFILE=%TEMP%\rendered-%RANDOM%.yaml"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$in='%INFILE%'; $out='%OUTFILE%';" ^
  "$c = Get-Content -LiteralPath $in -Raw;" ^
  "$c = [regex]::Replace($c,'\$\{([A-Za-z_][A-Za-z0-9_]*)\}', { param($m) $n=$m.Groups[1].Value; $v=[Environment]::GetEnvironmentVariable($n); if ($null -ne $v) { $v } else { '' } });" ^
  "Set-Content -LiteralPath $out -Value $c -NoNewline;"
if errorlevel 1 (
  echo ERROR: 템플릿 렌더링 실패: "%INFILE%" 1>&2
  if exist "%OUTFILE%" del "%OUTFILE%" >nul 2>&1
  exit /b 1
)
kubectl apply -n "%NAMESPACE_EFFECTIVE%" -f "%OUTFILE%"
set "APPLY_ERR=%ERRORLEVEL%"
del "%OUTFILE%" >nul 2>&1
if not "%APPLY_ERR%"=="0" exit /b %APPLY_ERR%
goto :eof

rem ---------------- Apply manifests ----------------
echo [apply] ConfigMap: "%SCRIPT_DIR%configmap.tpl.yaml"
call :render_and_apply "%SCRIPT_DIR%configmap.tpl.yaml"

echo [apply] Secret: "%SCRIPT_DIR%secret.tpl.yaml"
call :render_and_apply "%SCRIPT_DIR%secret.tpl.yaml"

if exist "%SCRIPT_DIR%deployment.tpl.yaml" (
  echo [apply] Deployment: "%SCRIPT_DIR%deployment.tpl.yaml" (IMAGE=%IMAGE%)
  call :render_and_apply "%SCRIPT_DIR%deployment.tpl.yaml"
)

echo [apply] Service: "%SCRIPT_DIR%service.yaml"
kubectl apply -n "%NAMESPACE_EFFECTIVE%" -f "%SCRIPT_DIR%service.yaml"
if errorlevel 1 exit /b 1

echo ✅ Done.
exit /b 0


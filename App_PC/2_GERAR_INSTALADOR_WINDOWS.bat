@echo off
title Music Box Teleprompter
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo  FALTA O NODE.JS neste computador.
  echo  Baixe em https://nodejs.org  ^(botao LTS^), instale e rode este arquivo de novo.
  echo.
  pause
  exit /b 1
)
if not exist node_modules\electron (
  echo  Primeira vez: baixando as ferramentas ^(precisa de internet, leva alguns minutos^)...
  call npm install --no-audit --no-fund
  if errorlevel 1 ( echo. & echo  ERRO ao baixar as ferramentas. & pause & exit /b 1 )
)
echo  Gerando o instalador do Windows...
call npm run dist-win
if errorlevel 1 ( echo. & echo  ERRO ao gerar o instalador. Tire um print desta janela. & pause & exit /b 1 )
echo.
echo  PRONTO. O instalador esta na pasta "instalador":
dir /b instalador\*.exe
echo.
pause

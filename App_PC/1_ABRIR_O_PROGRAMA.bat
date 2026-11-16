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
call npm start
if errorlevel 1 pause

#!/bin/sh
# Gera o instalador para Linux (arquivo .AppImage). Rode num computador com Linux
# e Node.js instalado:   sh gerar_instalador_linux.sh
cd "$(dirname "$0")" || exit 1
command -v node >/dev/null || { echo "Falta o Node.js (https://nodejs.org)"; exit 1; }
[ -d node_modules/electron ] || npm install --no-audit --no-fund || exit 1
npm run dist-linux && echo && echo "PRONTO: veja a pasta instalador/" && ls instalador/*.AppImage

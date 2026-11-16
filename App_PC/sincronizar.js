// Copia a tela (index.html + fontes) e as músicas de amostra do projeto Android
// para cá. Assim existe UMA tela só para o celular e para o PC: o que mudar em
// App_Android\app\src\main\assets vale para os dois.
const fs = require('fs'), path = require('path');
const origem = path.join(__dirname, '..', 'App_Android', 'app', 'src', 'main', 'assets');
if (!fs.existsSync(origem)) { console.log('[sincronizar] pasta do Android não encontrada; usando a cópia local.'); process.exit(0); }
for (const [de, para] of [['www', 'www'], ['amostras', 'amostras']]) {
  fs.rmSync(path.join(__dirname, para), { recursive: true, force: true });
  fs.cpSync(path.join(origem, de), path.join(__dirname, para), { recursive: true });
}
console.log('[sincronizar] tela e amostras copiadas do projeto Android.');

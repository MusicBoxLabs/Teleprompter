/*
 * MUSIC BOX TELEPROMPTER — versão para PC (Windows e Linux)
 *
 * A tela é a MESMA do app Android (www/index.html). Este arquivo faz o papel
 * que o MainActivity.java faz no celular:
 *  - guarda as músicas em Documentos/MusicBox;
 *  - importa .txt e .zip (botão IMPORTAR ou arrastando para a janela);
 *  - escuta o pedal, que é um teclado (Page Down desce, Page Up sobe);
 *  - F11 = tela cheia, F9 = tela em pé (monitor girado), Esc = voltar.
 */
const { app, BrowserWindow, ipcMain, dialog, shell, Menu, powerSaveBlocker } = require('electron');
const path = require('path'), fs = require('fs');
const { Acervo } = require('./nucleo');

let janela = null, acervo = null, leituraAtiva = false, pedalVisto = false;
let prefs = {}, arqPrefs = '';

// MUSICBOX_PASTA / MUSICBOX_CONFIG: só para testes automáticos
const pastaConfig = () => process.env.MUSICBOX_CONFIG || app.getPath('userData');
const pastaMusicas = () => process.env.MUSICBOX_PASTA || path.join(app.getPath('documents'), 'MusicBox');

function carregarPrefs() {
  arqPrefs = path.join(pastaConfig(), 'preferencias.json');
  try { prefs = JSON.parse(fs.readFileSync(arqPrefs, 'utf8')) || {}; } catch (e) { prefs = {}; }
}
function gravarPrefs() {
  try { fs.mkdirSync(path.dirname(arqPrefs), { recursive: true }); fs.writeFileSync(arqPrefs, JSON.stringify(prefs)); } catch (e) { }
}
function js(codigo) {
  if (janela && !janela.isDestroyed()) janela.webContents.executeJavaScript('window.MB && ' + codigo).catch(() => { });
}
function telaCheia() { if (janela) janela.setFullScreen(!janela.isFullScreen()); }

// ── O que a tela pode pedir (mesmos nomes do window.Android do celular) ──
const J = JSON.stringify;
const ponte = {
  estado: () => J({ pc: true, visivel: true, podeVisivel: false, raiz: acervo.raiz, pedal: pedalVisto, pedalTipo: 'pc' }),
  pastas: () => J(acervo.pastas()),
  musicas: (p) => J(acervo.musicas(p)),
  ler: (p, a) => acervo.ler(p, a),
  salvarMusica: (p, o, n, t) => J(acervo.salvarMusica(p, o, n, t)),
  novaPasta: (n) => J(acervo.novaPasta(n)),
  renomearPasta: (a, b) => J(acervo.renomearPasta(a, b)),
  apagarPasta: (a) => J(acervo.apagarPasta(a)),
  renomearMusica: (p, a, b) => J(acervo.renomearMusica(p, a, b)),
  apagarMusica: (p, a) => J(acervo.apagarMusica(p, a)),
  moverMusica: (p, a, d) => J(acervo.moverMusica(p, a, d)),
  importacao: () => J(acervo.importacao()),
  concluirImportacao: (p, nomes, subst) => J(acervo.concluirImportacao(p, nomes, subst)),
  descartarImportacao: () => { acervo.descartarImportacao(); },
  receberArquivo: (nome, bytes) => { acervo.receber(nome, bytes); },
  abrirSeletor: () => {
    // Responde na hora e abre a janela de arquivos depois, para não travar a tela
    setImmediate(async () => {
      const r = await dialog.showOpenDialog(janela, {
        title: 'Importar músicas', properties: ['openFile', 'multiSelections'],
        filters: [{ name: 'Músicas (.txt) e pacotes (.zip)', extensions: ['txt', 'zip'] }, { name: 'Todos os arquivos', extensions: ['*'] }]
      });
      if (r.canceled || !r.filePaths.length) return;
      r.filePaths.forEach(f => acervo.receberCaminho(f));
      js("MB.evento('importacao')");
    });
  },
  pedirPastaVisivel: () => { },
  restaurarAmostras: () => { const qtd = acervo.primeiroUso(); return J({ ok: true, qtd }); },
  getPref: (k) => (prefs['p_' + k] === undefined ? '' : String(prefs['p_' + k])),
  setPref: (k, v) => { prefs['p_' + k] = String(v); gravarPrefs(); },
  teclado: () => { },
  setLeitura: (b) => { leituraAtiva = !!b; },
  setPaisagem: () => { },
  abrirPastaMusicas: () => { shell.openPath(acervo.raiz); },
  telaCheia: () => { telaCheia(); },
  sair: () => { app.quit(); }
};
ipcMain.on('mb', (ev, metodo, args) => {
  try { const r = ponte[metodo] ? ponte[metodo](...(args || [])) : undefined; ev.returnValue = r === undefined ? null : r; }
  catch (e) { console.error('[mb]', metodo, e); ev.returnValue = null; }
});

// ── Teclado: o pedal é um teclado ──
function aoTeclar(ev, input) {
  if (!janela || janela.isDestroyed()) return;
  const k = input.key;
  let dir = 0;
  if (k === 'PageDown') dir = 1;
  else if (k === 'PageUp') dir = -1;
  else if (leituraAtiva && (k === 'ArrowDown' || k === 'ArrowRight')) dir = 1;
  else if (leituraAtiva && (k === 'ArrowUp' || k === 'ArrowLeft')) dir = -1;
  if (dir) {
    ev.preventDefault();
    if (input.type === 'keyDown') {
      if (input.isAutoRepeat) return;
      const primeira = !pedalVisto; pedalVisto = true;
      if (primeira) js("MB.evento('pedal')");
      js("MB.pedal('down'," + dir + ")");
    } else if (input.type === 'keyUp') js("MB.pedal('up'," + dir + ")");
    return;
  }
  if (input.type !== 'keyDown' || input.isAutoRepeat) return;
  if (k === 'F11') { ev.preventDefault(); telaCheia(); }
  else if (k === 'F9') { ev.preventDefault(); js("MB.evento('giro')"); }
  else if (k === 'Escape') {
    ev.preventDefault();
    if (janela.isFullScreen() && !leituraAtiva) {
      // Na tela das pastas, Esc sai da tela cheia; nas outras, volta uma tela
      janela.webContents.executeJavaScript("(window.voltar ? window.voltar() : false)").then(v => { if (!v) janela.setFullScreen(false); }).catch(() => { });
    } else janela.webContents.executeJavaScript("window.voltar && window.voltar()").catch(() => { });
  }
}

// As teclas chegam da tela (preload.js)
ipcMain.on('tecla', (ev, tipo, key, repetida) => aoTeclar({ preventDefault() { } }, { type: tipo, key, isAutoRepeat: repetida }));

function criarJanela() {
  janela = new BrowserWindow({
    width: 1280, height: 800, minWidth: 420, minHeight: 420,
    backgroundColor: '#0a0a00', title: 'Music Box Teleprompter',
    icon: path.join(__dirname, 'icone.png'), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false }
  });
  Menu.setApplicationMenu(null);
  janela.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));      // nunca abre outra janela
  janela.webContents.on('will-navigate', (e) => e.preventDefault());        // nem sai da própria tela (ex.: arquivo solto na janela)
  janela.loadFile(path.join(__dirname, 'www', 'index.html'));
  janela.on('closed', () => { janela = null; });
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (janela) { if (janela.isMinimized()) janela.restore(); janela.focus(); } });
  app.whenReady().then(() => {
    carregarPrefs();
    acervo = new Acervo(pastaMusicas(), path.join(__dirname, 'amostras'));
    if (!prefs.iniciado) { acervo.primeiroUso(); prefs.iniciado = true; gravarPrefs(); }
    powerSaveBlocker.start('prevent-display-sleep');     // a tela não apaga no meio do show
    criarJanela();
  });
  app.on('window-all-closed', () => app.quit());
}

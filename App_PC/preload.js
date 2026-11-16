// Ponte entre a tela (index.html) e o programa. A tela foi feita para o Android
// e chama window.Android.algumaCoisa(); aqui cada chamada vai para o main.js.
const { contextBridge, ipcRenderer } = require('electron');
const metodos = ['estado', 'pastas', 'musicas', 'ler', 'salvarMusica', 'novaPasta', 'renomearPasta',
  'apagarPasta', 'renomearMusica', 'apagarMusica', 'moverMusica', 'importacao', 'concluirImportacao',
  'descartarImportacao', 'abrirSeletor', 'pedirPastaVisivel', 'restaurarAmostras', 'getPref', 'setPref',
  'teclado', 'setLeitura', 'setPaisagem', 'sair', 'abrirPastaMusicas', 'telaCheia', 'receberArquivo'];
const ponte = {};
let leitura = false;
for (const m of metodos) ponte[m] = (...args) => {
  if (m === 'setLeitura') leitura = !!args[0];
  return ipcRenderer.sendSync('mb', m, args);
};

// Teclado. O pedal é um teclado: Page Down desce e Page Up sobe (na leitura,
// as setas também valem). F9 = tela em pé, F11 = tela cheia, Esc = voltar.
function tecla(tipo, e) {
  const k = e.key;
  const seta = k === 'ArrowDown' || k === 'ArrowUp' || k === 'ArrowLeft' || k === 'ArrowRight';
  const nossa = k === 'PageDown' || k === 'PageUp' || k === 'F9' || k === 'F11' || k === 'Escape' || (seta && leitura);
  if (!nossa) return;
  e.preventDefault(); e.stopPropagation();
  ipcRenderer.send('tecla', tipo, k, !!e.repeat);
}
window.addEventListener('keydown', (e) => tecla('keyDown', e), true);
window.addEventListener('keyup', (e) => tecla('keyUp', e), true);
contextBridge.exposeInMainWorld('Android', ponte);

// Tudo o que mexe com arquivos: pastas, músicas, acentos, .zip.
// Mesmas regras do app Android (MainActivity.java). Não depende do Electron,
// então pode ser testado sozinho.
const fs = require('fs'), path = require('path'), zlib = require('zlib');

const MAX_MUSICA = 2 * 1024 * 1024;
const PASTAS_INICIAIS = ['MINHA_PLAYLIST'];

function limparBase(n) {
  n = String(n == null ? '' : n);
  n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
  n = n.replace(/[\x00-\x1f\\/:*?"<>|]/g, '').trim();
  while (n.startsWith('.')) n = n.substring(1).trim();
  if (n.length > 100) n = n.substring(0, 100).trim();
  return n;
}
function nomeMusica(n) {
  let r = limparBase(n);
  if (r.toLowerCase().endsWith('.txt')) r = r.substring(0, r.length - 4).trim();
  r = r.replace(/[. ]+$/, '');          // Windows não aceita nome terminado em ponto/espaço
  return (r || 'Musica') + '.txt';
}
function nomePasta(n) {
  let r = limparBase(n).replace(/\./g, '').replace(/\s+/g, '_').toLocaleUpperCase('pt-BR').replace(/^_+|_+$/g, '');
  return r || 'MINHA_PASTA';
}

// UTF-8 (com ou sem BOM), UTF-16 e, por último, Windows-1252 (Bloco de Notas antigo)
function decodificar(b) {
  if (b.length >= 2 && b[0] === 0xFF && b[1] === 0xFE) return new TextDecoder('utf-16le').decode(b.subarray(2));
  if (b.length >= 2 && b[0] === 0xFE && b[1] === 0xFF) return new TextDecoder('utf-16be').decode(b.subarray(2));
  const ini = (b.length >= 3 && b[0] === 0xEF && b[1] === 0xBB && b[2] === 0xBF) ? 3 : 0;
  try { return new TextDecoder('utf-8', { fatal: true }).decode(b.subarray(ini)); }
  catch (e) { return new TextDecoder('windows-1252').decode(b.subarray(ini)); }
}
function pareceBinario(b) {
  if (b.length >= 2 && (b[0] === 0xFF || b[0] === 0xFE)) return false;
  const lim = Math.min(b.length, 8192);
  for (let i = 0; i < lim; i++) if (b[i] === 0) return true;
  return false;
}
function ehZip(b) { return b.length >= 4 && b[0] === 0x50 && b[1] === 0x4B && b[2] === 3 && b[3] === 4; }

// Nomes de arquivo dentro do .zip: UTF-8 quando o zip avisa; senão CP850,
// o padrão do "Enviar para > Pasta compactada" do Windows em português.
const CP850 = 'ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜø£Ø×ƒáíóúñÑªº¿®¬½¼¡«»░▒▓│┤ÁÂÀ©╣║╗╝¢¥┐└┴┬├─┼ãÃ╚╔╩╦╠═╬¤ðÐÊËÈıÍÎÏ┘┌█▄¦Ì▀ÓßÔÒõÕµþÞÚÛÙýÝ¯´­±‗¾¶§÷¸°¨·¹³²■ ';
function nomeZip(buf, utf8) {
  if (utf8) return buf.toString('utf8');
  try { return new TextDecoder('utf-8', { fatal: true }).decode(buf); } catch (e) { }
  let s = '';
  for (const c of buf) s += c < 128 ? String.fromCharCode(c) : CP850[c - 128];
  return s;
}
/** Abre um .zip e devolve [{nome, texto}] de cada .txt de dentro (subpastas inclusive). */
function extrairZip(buf) {
  buf = Buffer.from(buf);
  const out = [];
  let fim = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { fim = i; break; }
  }
  if (fim < 0) return out;
  const total = buf.readUInt16LE(fim + 10);
  let p = buf.readUInt32LE(fim + 16);
  for (let k = 0; k < total && p + 46 <= buf.length; k++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) break;
    const flags = buf.readUInt16LE(p + 8), metodo = buf.readUInt16LE(p + 10);
    const tamComp = buf.readUInt32LE(p + 20), tamReal = buf.readUInt32LE(p + 24);
    const nLen = buf.readUInt16LE(p + 28), eLen = buf.readUInt16LE(p + 30), cLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const nome = nomeZip(buf.subarray(p + 46, p + 46 + nLen), (flags & 0x800) !== 0);
    p += 46 + nLen + eLen + cLen;
    const base = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);
    if (!base || nome.endsWith('/') || nome.startsWith('__MACOSX') || base.startsWith('.') || !base.toLowerCase().endsWith('.txt')) continue;
    if (tamReal > MAX_MUSICA || (flags & 1) || local + 30 > buf.length) continue;   // grande demais ou com senha
    const ini = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const comp = buf.subarray(ini, ini + tamComp);
    let dados;
    try { dados = metodo === 0 ? comp : metodo === 8 ? zlib.inflateRawSync(comp) : null; } catch (e) { dados = null; }
    if (!dados || pareceBinario(dados)) continue;
    out.push({ nome: nomeMusica(base), texto: decodificar(dados) });
  }
  return out;
}

const ordem = new Intl.Collator('pt-BR', { sensitivity: 'accent', numeric: false });
const ehTxt = (dir, n) => !n.startsWith('.') && n.toLowerCase().endsWith('.txt') && fs.statSync(path.join(dir, n)).isFile();
const res = (ok, erro, extra) => Object.assign({ ok }, erro ? { erro } : {}, extra || {});

class Acervo {
  /** raiz = pasta das músicas (Documentos/MusicBox); amostras = pasta com as músicas de fábrica */
  constructor(raiz, amostras) {
    this.raiz = raiz; this.amostras = amostras;
    this.pendentes = []; this.rejeitados = 0; this.zip = '';
    fs.mkdirSync(raiz, { recursive: true });
  }
  primeiroUso() {
    for (const p of PASTAS_INICIAIS) fs.mkdirSync(path.join(this.raiz, p), { recursive: true });
    return this.copiarAmostras();
  }
  copiarAmostras() {   // nunca sobrescreve
    let n = 0;
    if (!this.amostras || !fs.existsSync(this.amostras)) return 0;
    for (const p of fs.readdirSync(this.amostras)) {
      const de = path.join(this.amostras, p);
      if (!fs.statSync(de).isDirectory()) continue;
      const para = path.join(this.raiz, p);
      fs.mkdirSync(para, { recursive: true });
      for (const a of fs.readdirSync(de)) {
        const destino = path.join(para, a);
        if (fs.existsSync(destino)) continue;
        fs.copyFileSync(path.join(de, a), destino); n++;
      }
    }
    return n;
  }
  dirPasta(p) {
    const n = limparBase(p); if (!n) return null;
    const d = path.join(this.raiz, n);
    return fs.existsSync(d) && fs.statSync(d).isDirectory() ? d : null;
  }
  arq(dir, a) { const n = limparBase(a); return dir && n ? path.join(dir, n) : null; }
  existe(f) { return !!f && fs.existsSync(f) && fs.statSync(f).isFile(); }

  pastas() {
    return fs.readdirSync(this.raiz)
      .filter(n => !n.startsWith('.') && fs.statSync(path.join(this.raiz, n)).isDirectory())
      .sort(ordem.compare)
      .map(n => { const d = path.join(this.raiz, n); return { nome: n, qtd: fs.readdirSync(d).filter(a => ehTxt(d, a)).length }; });
  }
  musicas(pasta) {
    const d = this.dirPasta(pasta); if (!d) return [];
    return fs.readdirSync(d).filter(a => ehTxt(d, a)).sort(ordem.compare);
  }
  ler(pasta, arq) {
    const f = this.arq(this.dirPasta(pasta), arq);
    if (!this.existe(f)) return '(arquivo não encontrado)';
    try {
      if (fs.statSync(f).size > MAX_MUSICA) return '(arquivo grande demais)';
      return decodificar(fs.readFileSync(f));
    } catch (e) { return '(erro ao abrir arquivo)'; }
  }
  salvarMusica(pasta, original, nomeNovo, texto) {
    const d = this.dirPasta(pasta); if (!d) return res(false, 'Pasta não encontrada');
    const novo = nomeMusica(nomeNovo), destino = path.join(d, novo);
    const mesmo = !!original && original.toLowerCase() === novo.toLowerCase();
    if (!mesmo && fs.existsSync(destino)) return res(false, 'Já existe uma música com esse nome');
    try {
      if (mesmo && original !== novo) fs.renameSync(this.arq(d, original), destino);
      fs.writeFileSync(destino, texto, 'utf8');
      if (!mesmo && original) { const o = this.arq(d, original); if (this.existe(o)) fs.unlinkSync(o); }
    } catch (e) { return res(false, 'Não foi possível gravar'); }
    return res(true, null, { arq: novo });
  }
  novaPasta(nome) {
    const n = nomePasta(nome), d = path.join(this.raiz, n);
    if (fs.existsSync(d)) return res(false, 'Essa pasta já existe', { nome: n });
    try { fs.mkdirSync(d, { recursive: true }); } catch (e) { return res(false, 'Não foi possível criar'); }
    return res(true, null, { nome: n });
  }
  renomearPasta(antiga, nova) {
    const d = this.dirPasta(antiga); if (!d) return res(false, 'Pasta não encontrada');
    const n = nomePasta(nova);
    if (n === path.basename(d)) return res(true, null, { nome: n });
    const alvo = path.join(this.raiz, n);
    if (fs.existsSync(alvo) && n.toLowerCase() !== path.basename(d).toLowerCase()) return res(false, 'Essa pasta já existe');
    try { fs.renameSync(d, alvo); } catch (e) { return res(false, 'Não foi possível renomear'); }
    return res(true, null, { nome: n });
  }
  apagarPasta(pasta) {
    const d = this.dirPasta(pasta); if (!d) return res(false, 'Pasta não encontrada');
    try { fs.rmSync(d, { recursive: true, force: true }); } catch (e) { return res(false, 'Não foi possível apagar tudo'); }
    return res(true);
  }
  renomearMusica(pasta, antiga, nova) {
    const d = this.dirPasta(pasta), f = this.arq(d, antiga);
    if (!this.existe(f)) return res(false, 'Música não encontrada');
    const n = nomeMusica(nova);
    if (n === path.basename(f)) return res(true, null, { arq: n });
    const alvo = path.join(d, n);
    if (fs.existsSync(alvo) && n.toLowerCase() !== path.basename(f).toLowerCase()) return res(false, 'Já existe uma música com esse nome');
    try { fs.renameSync(f, alvo); } catch (e) { return res(false, 'Não foi possível renomear'); }
    return res(true, null, { arq: n });
  }
  apagarMusica(pasta, arq) {
    const f = this.arq(this.dirPasta(pasta), arq);
    if (!this.existe(f)) return res(false, 'Música não encontrada');
    try { fs.unlinkSync(f); } catch (e) { return res(false, 'Não foi possível apagar'); }
    return res(true);
  }
  moverMusica(pasta, arq, destinoPasta) {
    const f = this.arq(this.dirPasta(pasta), arq), dd = this.dirPasta(destinoPasta);
    if (!this.existe(f) || !dd) return res(false, 'Não encontrado');
    const alvo = path.join(dd, path.basename(f));
    if (fs.existsSync(alvo)) return res(false, 'Já existe uma música com esse nome lá');
    try { fs.renameSync(f, alvo); } catch (e) { return res(false, 'Não foi possível mover'); }
    return res(true);
  }

  // ── Importação (.txt soltos ou .zip) ──
  receber(nome, bytes) {
    const b = Buffer.from(bytes);
    if (ehZip(b)) {
      const ms = extrairZip(b);
      if (!ms.length) { this.rejeitados++; return; }
      this.zip = String(nome).replace(/\.zip$/i, '').trim();
      this.pendentes.push(...ms);
      return;
    }
    if (b.length > MAX_MUSICA || pareceBinario(b)) { this.rejeitados++; return; }
    this.pendentes.push({ nome: nomeMusica(nome), texto: decodificar(b) });
  }
  receberCaminho(caminho) {
    try { this.receber(path.basename(caminho), fs.readFileSync(caminho)); } catch (e) { this.rejeitados++; }
  }
  importacao() {
    const r = { itens: this.pendentes.map(p => ({ nome: p.nome, tamanho: p.texto.length })), rejeitados: this.rejeitados, zip: this.zip };
    if (!this.pendentes.length) this.rejeitados = 0;
    return r;
  }
  concluirImportacao(pasta, nomesJson, substituir) {
    const d = this.dirPasta(pasta); if (!d) return res(false, 'Pasta não encontrada');
    let nomes = []; try { nomes = JSON.parse(nomesJson) || []; } catch (e) { }
    let qtd = 0;
    this.pendentes.forEach((p, i) => {
      const nome = nomeMusica(nomes[i] || p.nome);
      let alvo = path.join(d, nome);
      if (fs.existsSync(alvo) && !substituir) {
        const base = nome.substring(0, nome.length - 4); let n = 2;
        while (fs.existsSync(alvo)) alvo = path.join(d, base + ' (' + (n++) + ').txt');
      }
      try { fs.writeFileSync(alvo, p.texto, 'utf8'); qtd++; } catch (e) { }
    });
    this.descartarImportacao();
    return res(true, null, { qtd });
  }
  descartarImportacao() { this.pendentes = []; this.rejeitados = 0; this.zip = ''; }
}

module.exports = { Acervo, nomeMusica, nomePasta, decodificar, extrairZip, ehZip };

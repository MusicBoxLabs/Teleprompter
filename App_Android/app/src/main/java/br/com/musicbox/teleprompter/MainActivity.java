package br.com.musicbox.teleprompter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MUSIC BOX TELEPROMPTER — APP
 *
 * A interface inteira é o HTML em assets/www/index.html (a mesma "cara" do
 * FTSW1). Esta Activity só faz o que o navegador não pode fazer sozinho:
 *  - guardar as músicas em pastas no celular (Documentos/MusicBox);
 *  - receber arquivos pelo "Compartilhar" de outros apps;
 *  - escutar o pedal (teclado USB que envia Page Down) e avisar o HTML;
 *  - tela sempre acesa e modo imersivo (sem barras do Android).
 */
public class MainActivity extends Activity {

    private static final int REQ_ABRIR = 11;
    private static final int REQ_PERMISSAO = 12;
    private static final int VENDOR_ESPRESSIF = 0x303A;   // VID do pedal ESP32-S3
    private static final int MAX_BYTES_MUSICA = 2 * 1024 * 1024;
    private static final int MAX_BYTES_ZIP = 60 * 1024 * 1024;   // ~centenas de músicas
    private static final String NOME_PASTA = "MusicBox";
    private static final String[] PASTAS_INICIAIS = {"MINHA_PLAYLIST"};

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean leituraAtiva = false;
    private boolean paginaPronta = false;
    private boolean visivelAntes = false;

    // Arquivos recebidos aguardando o usuário escolher a pasta: {nome, conteúdo}
    private final List<String[]> pendentes = new ArrayList<>();
    private int rejeitados = 0;
    private String nomeZip = "";   // nome do último .zip recebido: vira sugestão de pasta nova

    private InputManager.InputDeviceListener ouvintePedal;

    // ═══════════════════════════════════════════════════════════
    //  CICLO DE VIDA
    // ═══════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        prefs = getSharedPreferences("musicbox", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        aplicarOrientacao("1".equals(prefs.getString("p_tv", "0")));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        FrameLayout raizLayout = new FrameLayout(this);
        raizLayout.setBackgroundColor(0xFF0A0A00);
        web = new WebView(this);
        raizLayout.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(raizLayout);

        // Com as barras escondidas, o teclado virtual cobriria o editor.
        // Aplica a altura do teclado como margem inferior.
        if (Build.VERSION.SDK_INT >= 30) {
            raizLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                int teclado = insets.getInsets(WindowInsets.Type.ime()).bottom;
                v.setPadding(0, 0, 0, teclado);
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);              // fonte grande do sistema não pode quebrar o layout
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowContentAccess(false);
        web.setBackgroundColor(0xFF0A0A00);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(false);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);   // sem menu de "selecionar texto"
        web.addJavascriptInterface(new Ponte(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;   // o app nunca navega para fora da própria página
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                paginaPronta = true;
            }
        });

        visivelAntes = temPastaVisivel();
        prepararPrimeiroUso();
        tratarIntent(getIntent());
        web.loadUrl("file:///android_asset/www/index.html");
        registrarOuvintePedal();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tratarIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        imersivo();
        // Voltou da tela de permissão? Se a pasta visível foi liberada agora,
        // copia o que estava na memória interna para Documentos/MusicBox.
        boolean visivel = temPastaVisivel();
        if (visivel && !visivelAntes) {
            migrarInternoParaVisivel();
            js("MB.evento('armazenamento')");
        }
        visivelAntes = visivel;
    }

    @Override
    public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco) imersivo();
    }

    @Override
    protected void onDestroy() {
        if (ouvintePedal != null) {
            InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
            if (im != null) im.unregisterInputDeviceListener(ouvintePedal);
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /**
     * Normal: a tela gira junto com o aparelho (celular ou tablet, em pé ou deitado).
     * Espelhando na Smart TV: trava deitada, para a imagem ocupar a TV inteira.
     */
    private void aplicarOrientacao(boolean paisagem) {
        setRequestedOrientation(paisagem
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    @SuppressWarnings("deprecation")
    private void imersivo() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // Botão VOLTAR: o HTML decide (fecha janela, volta uma tela...).
    // Só fecha o app quando já está na tela inicial.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!paginaPronta) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.voltar ? window.voltar() : false)", valor -> {
            if (!"true".equals(valor)) finish();
        });
    }

    private void js(final String codigo) {
        ui.post(() -> {
            if (web != null && paginaPronta) web.evaluateJavascript("window.MB && " + codigo, null);
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  PEDAL — o ESP32-S3 é um teclado USB que envia PAGE DOWN.
    //  Pisou = tecla apertada; soltou = tecla solta. A repetição
    //  automática do Android (tecla segurada) é ignorada.
    // ═══════════════════════════════════════════════════════════
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        int dir = 0;
        if (k == KeyEvent.KEYCODE_PAGE_DOWN) dir = 1;
        else if (k == KeyEvent.KEYCODE_PAGE_UP) dir = -1;
        else if (leituraAtiva && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_RIGHT)) dir = 1;
        else if (leituraAtiva && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT)) dir = -1;
        if (dir != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) js("MB.pedal('down'," + dir + ")");
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                js("MB.pedal('up'," + dir + ")");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * Que pedal está ligado agora: "usb" (cabo), "bt" (Bluetooth) ou "" (nenhum).
     * O pedal aparece para o Android como um teclado. Vale qualquer teclado
     * externo cujo nome tenha "Music Box" (Music Box Pedal, Music Box Turner,
     * Music Box Universal...) ou "BoxTurner" (pedal Bluetooth com nome
     * comercial), ou que seja da Espressif (ESP32).
     * Se existe um aparelho USB desses ligado no cabo, é "usb"; se o teclado
     * está lá mas não há nada no USB, ele só pode ter vindo pelo Bluetooth.
     */
    private String tipoPedal() {
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im == null) return "";
        boolean achou = false;
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            if (ehNomeMusicBox(d.getName()) || d.getVendorId() == VENDOR_ESPRESSIF) { achou = true; break; }
        }
        if (!achou) return "";
        return pedalNoCaboUsb() ? "usb" : "bt";
    }

    private static boolean ehNomeMusicBox(String nome) {
        if (nome == null) return false;
        String n = nome.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
        return n.contains("musicbox") || n.contains("boxturner");
    }

    private boolean pedalNoCaboUsb() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um == null) return false;
            for (UsbDevice d : um.getDeviceList().values()) {
                if (d.getVendorId() == VENDOR_ESPRESSIF || ehNomeMusicBox(d.getProductName())) return true;
            }
        } catch (Exception ignorado) { }
        return false;
    }

    private void registrarOuvintePedal() {
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (im == null) return;
        ouvintePedal = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id) { js("MB.evento('pedal')"); }
            @Override public void onInputDeviceRemoved(int id) { js("MB.evento('pedal')"); }
            @Override public void onInputDeviceChanged(int id) { }
        };
        im.registerInputDeviceListener(ouvintePedal, ui);
    }

    // ═══════════════════════════════════════════════════════════
    //  ARMAZENAMENTO
    //  Com a permissão "gerenciar todos os arquivos": Documentos/MusicBox
    //  (visível no PC pelo cabo e em qualquer gerenciador de arquivos).
    //  Sem ela: memória interna do app (funciona, mas fica escondida).
    // ═══════════════════════════════════════════════════════════
    private boolean temPastaVisivel() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private File pastaVisivel() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), NOME_PASTA);
    }

    private File pastaInterna() {
        return new File(getFilesDir(), NOME_PASTA);
    }

    private File raiz() {
        if (temPastaVisivel()) {
            File v = pastaVisivel();
            if (!v.exists()) v.mkdirs();
            if (v.isDirectory() && v.canWrite()) return v;
        }
        File i = pastaInterna();
        if (!i.exists()) i.mkdirs();
        return i;
    }

    private void prepararPrimeiroUso() {
        if (prefs.getBoolean("iniciado", false)) return;
        File r = raiz();
        for (String p : PASTAS_INICIAIS) new File(r, p).mkdirs();
        copiarAmostras();
        prefs.edit().putBoolean("iniciado", true).apply();
    }

    /** Copia assets/amostras/PASTA/arquivo.txt para o armazenamento. Nunca sobrescreve. */
    private int copiarAmostras() {
        int n = 0;
        try {
            String[] pastas = getAssets().list("amostras");
            if (pastas == null) return 0;
            for (String p : pastas) {
                String[] arqs = getAssets().list("amostras/" + p);
                if (arqs == null || arqs.length == 0) continue;
                File dir = new File(raiz(), p);
                dir.mkdirs();
                for (String a : arqs) {
                    File destino = new File(dir, a);
                    if (destino.exists()) continue;
                    try (InputStream in = getAssets().open("amostras/" + p + "/" + a);
                         OutputStream out = new FileOutputStream(destino)) {
                        copiar(in, out);
                        n++;
                    }
                }
            }
        } catch (IOException ignorado) { }
        return n;
    }

    private void migrarInternoParaVisivel() {
        File origem = pastaInterna();
        File destino = pastaVisivel();
        if (!origem.isDirectory()) return;
        destino.mkdirs();
        File[] pastas = origem.listFiles();
        if (pastas == null) return;
        for (File p : pastas) {
            if (!p.isDirectory()) continue;
            File dp = new File(destino, p.getName());
            dp.mkdirs();
            File[] arqs = p.listFiles();
            if (arqs != null) {
                for (File a : arqs) {
                    File da = new File(dp, a.getName());
                    if (!da.exists()) {
                        try (InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(da)) {
                            copiar(in, out);
                        } catch (IOException ignorado) { continue; }
                    }
                    a.delete();
                }
            }
            p.delete();
        }
    }

    private void pedirPastaVisivel() {
        if (temPastaVisivel()) { js("MB.evento('armazenamento')"); return; }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSAO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERMISSAO && temPastaVisivel() && !visivelAntes) {
            migrarInternoParaVisivel();
            visivelAntes = true;
            js("MB.evento('armazenamento')");
        }
    }

    // ── Nomes seguros ─────────────────────────────────────────
    private static String limparBase(String n) {
        if (n == null) n = "";
        int barra = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (barra >= 0) n = n.substring(barra + 1);
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            if (c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        String r = sb.toString().trim();
        while (r.startsWith(".")) r = r.substring(1).trim();
        if (r.length() > 100) r = r.substring(0, 100).trim();
        return r;
    }

    /** Nome de música: sempre termina em .txt */
    static String nomeMusica(String n) {
        String r = limparBase(n);
        if (r.toLowerCase(Locale.ROOT).endsWith(".txt")) r = r.substring(0, r.length() - 4).trim();
        if (r.isEmpty()) r = "Musica";
        return r + ".txt";
    }

    /** Nome de pasta: maiúsculo e com "_" no lugar de espaço (mesmo padrão do FTSW1) */
    static String nomePasta(String n) {
        String r = limparBase(n).replace(".", "").replaceAll("\\s+", "_").toUpperCase(new Locale("pt", "BR"));
        while (r.startsWith("_")) r = r.substring(1);
        while (r.endsWith("_")) r = r.substring(0, r.length() - 1);
        return r.isEmpty() ? "MINHA_PASTA" : r;
    }

    private File dirPasta(String p) {
        String n = limparBase(p);
        if (n.isEmpty()) return null;
        File d = new File(raiz(), n);
        return d.isDirectory() ? d : null;
    }

    private File arqMusica(File dir, String a) {
        String n = limparBase(a);
        if (dir == null || n.isEmpty()) return null;
        return new File(dir, n);
    }

    private static final Comparator<String> ORDEM;
    static {
        final Collator c = Collator.getInstance(new Locale("pt", "BR"));
        c.setStrength(Collator.SECONDARY);
        ORDEM = c::compare;
    }

    private static boolean ehTxt(File f) {
        return f.isFile() && !f.getName().startsWith(".") && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    // ── Leitura e escrita de texto ─────────────────────────────
    private static void copiar(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static byte[] lerBytes(InputStream in, int limite) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n, total = 0;
        while ((n = in.read(b)) > 0) {
            total += n;
            if (total > limite) return null;
            bo.write(b, 0, n);
        }
        return bo.toByteArray();
    }

    /** UTF-8 (com ou sem BOM), UTF-16, e por último Windows-1252 — o .txt "acentuado" do Bloco de Notas antigo. */
    static String decodificar(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        int ini = (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) ? 3 : 0;
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(b, ini, b.length - ini)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, ini, b.length - ini, Charset.forName("windows-1252"));
        }
    }

    private static boolean pareceBinario(byte[] b) {
        if (b.length >= 2 && ((b[0] & 0xff) == 0xFF || (b[0] & 0xff) == 0xFE)) return false; // UTF-16
        int lim = Math.min(b.length, 8192);
        for (int i = 0; i < lim; i++) if (b[i] == 0) return true;
        return false;
    }

    private static String lerArquivo(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = lerBytes(in, MAX_BYTES_MUSICA);
            return b == null ? "(arquivo grande demais)" : decodificar(b);
        } catch (IOException e) {
            return "(erro ao abrir arquivo)";
        }
    }

    private static boolean gravarArquivo(File f, String texto) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(texto.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean apagarTudo(File f) {
        if (f.isDirectory()) {
            File[] filhos = f.listFiles();
            if (filhos != null) for (File c : filhos) apagarTudo(c);
        }
        return f.delete();
    }

    private static String res(boolean ok, String erro, String chave, Object valor) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (erro != null) o.put("erro", erro);
            if (chave != null) o.put(chave, valor);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  RECEBER ARQUIVOS — "Compartilhar → Music Box", "Abrir com"
    //  e o seletor de arquivos do próprio app.
    // ═══════════════════════════════════════════════════════════
    @SuppressWarnings("deprecation")
    private void tratarIntent(Intent it) {
        if (it == null || it.getAction() == null) return;
        String acao = it.getAction();
        int antes = pendentes.size() + rejeitados;
        if (Intent.ACTION_SEND.equals(acao)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                lerUri(u);
            } else if (it.getClipData() != null && it.getClipData().getItemCount() > 0
                    && it.getClipData().getItemAt(0).getUri() != null) {
                lerClip(it.getClipData());
            } else {
                CharSequence t = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null && t.length() > 0) {
                    String assunto = it.getStringExtra(Intent.EXTRA_SUBJECT);
                    String nome = assunto != null && !assunto.trim().isEmpty() ? assunto : primeiraLinha(t.toString());
                    synchronized (pendentes) { pendentes.add(new String[]{nomeMusica(nome), t.toString()}); }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(acao)) {
            ArrayList<Uri> us = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) for (Uri u : us) lerUri(u);
            else if (it.getClipData() != null) lerClip(it.getClipData());
        } else if (Intent.ACTION_VIEW.equals(acao) && it.getData() != null) {
            lerUri(it.getData());
        }
        it.setAction(null);   // não reimporta se a Activity for recriada
        if (pendentes.size() + rejeitados != antes) js("MB.evento('importacao')");
    }

    private void lerClip(ClipData c) {
        for (int i = 0; i < c.getItemCount(); i++) {
            Uri u = c.getItemAt(i).getUri();
            if (u != null) lerUri(u);
        }
    }

    private static String primeiraLinha(String t) {
        for (String l : t.split("\n")) {
            String s = l.trim();
            if (!s.isEmpty() && !s.startsWith("[")) return s.length() > 40 ? s.substring(0, 40) : s;
        }
        return "Musica compartilhada";
    }

    private String nomeDoUri(Uri u) {
        String nome = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) nome = c.getString(0);
        } catch (Exception ignorado) { }
        if (nome == null) nome = u.getLastPathSegment();
        return nome == null ? "Musica" : nome;
    }

    private void lerUri(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) { rejeitados++; return; }
            byte[] b = lerBytes(in, MAX_BYTES_ZIP);
            if (b == null) { rejeitados++; return; }
            if (ehZip(b)) {
                String nz = nomeDoUri(u).trim();
                if (nz.toLowerCase(Locale.ROOT).endsWith(".zip")) nz = nz.substring(0, nz.length() - 4);
                nomeZip = nz;
                // Pacote .zip (ex.: 200 músicas pelo WhatsApp): extrai só os .txt
                List<String[]> musicas = extrairZip(b);
                if (musicas.isEmpty()) { rejeitados++; return; }
                synchronized (pendentes) { pendentes.addAll(musicas); }
                return;
            }
            if (b.length > MAX_BYTES_MUSICA || pareceBinario(b)) { rejeitados++; return; }
            synchronized (pendentes) { pendentes.add(new String[]{nomeMusica(nomeDoUri(u)), decodificar(b)}); }
        } catch (Exception e) {
            rejeitados++;
        }
    }

    static boolean ehZip(byte[] b) {
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    /**
     * Abre um .zip e devolve {nome, texto} de cada .txt de dentro (subpastas
     * inclusive). Nomes de arquivo: tenta UTF-8; se falhar, CP850 — o padrão do
     * "Enviar para > Pasta compactada" do Windows em português.
     */
    static List<String[]> extrairZip(byte[] zip) {
        for (String cs : new String[]{"UTF-8", "IBM850", "IBM437"}) {
            List<String[]> out = new ArrayList<>();
            try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip), Charset.forName(cs))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String nome = e.getName();
                    String base = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);
                    if (e.isDirectory() || nome.startsWith("__MACOSX") || base.startsWith(".")
                            || !base.toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
                    byte[] b = lerBytes(z, MAX_BYTES_MUSICA);
                    if (b == null || pareceBinario(b)) continue;
                    out.add(new String[]{nomeMusica(base), decodificar(b)});
                }
                return out;
            } catch (Exception erro) {
                // nome de arquivo em outra codificação: tenta a próxima
            }
        }
        return new ArrayList<>();
    }

    @Override
    protected void onActivityResult(int req, int resultado, Intent dados) {
        super.onActivityResult(req, resultado, dados);
        if (req != REQ_ABRIR || resultado != RESULT_OK || dados == null) return;
        int antes = pendentes.size() + rejeitados;
        if (dados.getClipData() != null) lerClip(dados.getClipData());
        else if (dados.getData() != null) lerUri(dados.getData());
        if (pendentes.size() + rejeitados != antes) js("MB.evento('importacao')");
    }

    // ═══════════════════════════════════════════════════════════
    //  PONTE JavaScript ↔ Android (window.Android no HTML)
    // ═══════════════════════════════════════════════════════════
    private class Ponte {

        @JavascriptInterface
        public String estado() {
            try {
                JSONObject o = new JSONObject();
                o.put("visivel", temPastaVisivel());
                o.put("podeVisivel", true);
                o.put("raiz", raiz().getAbsolutePath());
                String tp = tipoPedal();
                o.put("pedal", !tp.isEmpty());
                o.put("pedalTipo", tp);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String pastas() {
            JSONArray a = new JSONArray();
            File[] fs = raiz().listFiles();
            if (fs == null) return a.toString();
            List<File> lista = new ArrayList<>();
            for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) lista.add(f);
            Collections.sort(lista, (x, y) -> ORDEM.compare(x.getName(), y.getName()));
            try {
                for (File f : lista) {
                    int qtd = 0;
                    File[] arqs = f.listFiles();
                    if (arqs != null) for (File m : arqs) if (ehTxt(m)) qtd++;
                    JSONObject o = new JSONObject();
                    o.put("nome", f.getName());
                    o.put("qtd", qtd);
                    a.put(o);
                }
            } catch (Exception ignorado) { }
            return a.toString();
        }

        @JavascriptInterface
        public String musicas(String pasta) {
            JSONArray a = new JSONArray();
            File d = dirPasta(pasta);
            if (d == null) return a.toString();
            File[] fs = d.listFiles();
            if (fs == null) return a.toString();
            List<String> nomes = new ArrayList<>();
            for (File f : fs) if (ehTxt(f)) nomes.add(f.getName());
            Collections.sort(nomes, ORDEM);
            for (String n : nomes) a.put(n);
            return a.toString();
        }

        @JavascriptInterface
        public String ler(String pasta, String arq) {
            File f = arqMusica(dirPasta(pasta), arq);
            if (f == null || !f.isFile()) return "(arquivo não encontrado)";
            return lerArquivo(f);
        }

        @JavascriptInterface
        public String salvarMusica(String pasta, String original, String nomeNovo, String texto) {
            File d = dirPasta(pasta);
            if (d == null) return res(false, "Pasta não encontrada", null, null);
            String novo = nomeMusica(nomeNovo);
            File destino = new File(d, novo);
            boolean mesmo = original != null && !original.isEmpty() && original.equalsIgnoreCase(novo);
            if (!mesmo && destino.exists()) return res(false, "Já existe uma música com esse nome", null, null);
            if (mesmo && !original.equals(novo)) {
                File orig = arqMusica(d, original);   // só mudou maiúscula/minúscula
                if (orig != null) orig.delete();
            }
            if (!gravarArquivo(destino, texto)) return res(false, "Não foi possível gravar", null, null);
            if (!mesmo && original != null && !original.isEmpty()) {
                File orig = arqMusica(d, original);
                if (orig != null && orig.isFile()) orig.delete();
            }
            return res(true, null, "arq", novo);
        }

        @JavascriptInterface
        public String novaPasta(String nome) {
            String n = nomePasta(nome);
            File d = new File(raiz(), n);
            if (d.exists()) return res(false, "Essa pasta já existe", "nome", n);
            return d.mkdirs() ? res(true, null, "nome", n) : res(false, "Não foi possível criar", null, null);
        }

        @JavascriptInterface
        public String renomearPasta(String antiga, String nova) {
            File d = dirPasta(antiga);
            if (d == null) return res(false, "Pasta não encontrada", null, null);
            String n = nomePasta(nova);
            if (n.equals(d.getName())) return res(true, null, "nome", n);
            File alvo = new File(raiz(), n);
            if (alvo.exists()) return res(false, "Essa pasta já existe", null, null);
            return d.renameTo(alvo) ? res(true, null, "nome", n) : res(false, "Não foi possível renomear", null, null);
        }

        @JavascriptInterface
        public String apagarPasta(String pasta) {
            File d = dirPasta(pasta);
            if (d == null) return res(false, "Pasta não encontrada", null, null);
            return apagarTudo(d) ? res(true, null, null, null) : res(false, "Não foi possível apagar tudo", null, null);
        }

        @JavascriptInterface
        public String renomearMusica(String pasta, String antiga, String nova) {
            File d = dirPasta(pasta);
            File f = arqMusica(d, antiga);
            if (f == null || !f.isFile()) return res(false, "Música não encontrada", null, null);
            String n = nomeMusica(nova);
            if (n.equals(f.getName())) return res(true, null, "arq", n);
            File alvo = new File(d, n);
            if (alvo.exists() && !n.equalsIgnoreCase(f.getName()))
                return res(false, "Já existe uma música com esse nome", null, null);
            return f.renameTo(alvo) ? res(true, null, "arq", n) : res(false, "Não foi possível renomear", null, null);
        }

        @JavascriptInterface
        public String apagarMusica(String pasta, String arq) {
            File f = arqMusica(dirPasta(pasta), arq);
            if (f == null || !f.isFile()) return res(false, "Música não encontrada", null, null);
            return f.delete() ? res(true, null, null, null) : res(false, "Não foi possível apagar", null, null);
        }

        @JavascriptInterface
        public String moverMusica(String pasta, String arq, String destinoPasta) {
            File f = arqMusica(dirPasta(pasta), arq);
            File dd = dirPasta(destinoPasta);
            if (f == null || !f.isFile() || dd == null) return res(false, "Não encontrado", null, null);
            File alvo = new File(dd, f.getName());
            if (alvo.exists()) return res(false, "Já existe uma música com esse nome lá", null, null);
            if (f.renameTo(alvo)) return res(true, null, null, null);
            // renameTo pode falhar entre volumes: copia e apaga
            if (gravarArquivo(alvo, lerArquivo(f)) && f.delete()) return res(true, null, null, null);
            return res(false, "Não foi possível mover", null, null);
        }

        @JavascriptInterface
        public String importacao() {
            try {
                JSONObject o = new JSONObject();
                JSONArray a = new JSONArray();
                synchronized (pendentes) {
                    for (String[] p : pendentes) {
                        JSONObject i = new JSONObject();
                        i.put("nome", p[0]);
                        i.put("tamanho", p[1].length());
                        a.put(i);
                    }
                }
                o.put("itens", a);
                o.put("rejeitados", rejeitados);
                o.put("zip", nomeZip);
                if (a.length() == 0) rejeitados = 0;   // aviso de rejeitados já foi mostrado
                return o.toString();
            } catch (Exception e) {
                return "{\"itens\":[]}";
            }
        }

        @JavascriptInterface
        public String concluirImportacao(String pasta, String nomesJson, boolean substituir) {
            File d = dirPasta(pasta);
            if (d == null) return res(false, "Pasta não encontrada", null, null);
            JSONArray nomes;
            try { nomes = new JSONArray(nomesJson); } catch (Exception e) { nomes = new JSONArray(); }
            int qtd = 0;
            synchronized (pendentes) {
                for (int i = 0; i < pendentes.size(); i++) {
                    String[] p = pendentes.get(i);
                    String nome = nomeMusica(nomes.optString(i, p[0]));
                    File alvo = new File(d, nome);
                    if (alvo.exists() && !substituir) {
                        String base = nome.substring(0, nome.length() - 4);
                        int n = 2;
                        while (alvo.exists()) alvo = new File(d, base + " (" + (n++) + ").txt");
                    }
                    if (gravarArquivo(alvo, p[1])) qtd++;
                }
                pendentes.clear();
            }
            rejeitados = 0;
            nomeZip = "";
            return res(true, null, "qtd", qtd);
        }

        @JavascriptInterface
        public void descartarImportacao() {
            synchronized (pendentes) { pendentes.clear(); }
            rejeitados = 0;
            nomeZip = "";
        }

        @JavascriptInterface
        public void abrirSeletor() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_ABRIR); } catch (Exception ignorado) { }
            });
        }

        @JavascriptInterface
        public void pedirPastaVisivel() {
            ui.post(MainActivity.this::pedirPastaVisivel);
        }

        @JavascriptInterface
        public String restaurarAmostras() {
            int n = copiarAmostras();
            File r = raiz();
            for (String p : PASTAS_INICIAIS) new File(r, p).mkdirs();
            return res(true, null, "qtd", n);
        }

        @JavascriptInterface
        public String getPref(String k) {
            return prefs.getString("p_" + k, "");
        }

        @JavascriptInterface
        public void setPref(String k, String v) {
            prefs.edit().putString("p_" + k, v).apply();
        }

        /** Mostra o teclado virtual mesmo com o pedal (teclado físico) conectado. */
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void teclado() {
            ui.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    web.requestFocus();
                    imm.showSoftInput(web, InputMethodManager.SHOW_FORCED);
                }
            });
        }

        @JavascriptInterface
        public void setLeitura(boolean ativa) {
            leituraAtiva = ativa;
        }

        @JavascriptInterface
        public void setPaisagem(boolean paisagem) {
            ui.post(() -> aplicarOrientacao(paisagem));
        }

        @JavascriptInterface
        public void sair() {
            ui.post(MainActivity.this::finish);
        }
    }
}

/*
 * ============================================================
 *  MUSIC BOX PEDAL — ESP32-S3 (Super Mini ou DevKit)
 * ------------------------------------------------------------
 *  O pedal é um TECLADO USB. Ligado no celular/tablet pelo cabo
 *  OTG, ele envia:
 *     pisou  → tecla PAGE DOWN apertada  (a letra desce)
 *     soltou → tecla PAGE DOWN solta     (a letra para)
 *  O app Music Box escuta essa tecla. Não precisa de Wi-Fi,
 *  Bluetooth, bateria nem pareamento: o celular alimenta o pedal.
 *
 *  MODO INSTALAÇÃO (pen drive):
 *     SEGURE O PEDAL PRESSIONADO enquanto liga o cabo.
 *     O pedal aparece como um pen drive "MUSICBOX" com o
 *     MusicBox.apk dentro. Funciona no PC (para você gravar o APK
 *     de fábrica) e no celular do comprador (para instalar o app).
 *     Para voltar ao modo pedal: desligue e ligue o cabo de novo,
 *     sem pisar.
 *
 *  LIGAÇÃO:
 *     Pedal (contato "normalmente aberto") entre o pino PINO_PEDAL
 *     e o GND. Não precisa de resistor (usa o pull-up interno).
 *     Um capacitor de 100 nF entre o pino e o GND ajuda contra
 *     ruído de cabo comprido, mas é opcional.
 *
 *  CONFIGURAÇÃO DA ARDUINO IDE (menu Ferramentas):
 *     Placa ............ "ESP32S3 Dev Module"
 *     USB Mode ......... "USB-OTG (TinyUSB)"          ← obrigatório
 *     USB CDC On Boot .. "Disabled"
 *     Flash Size ....... "4MB" (ou o tamanho da sua placa)
 *     Partition Scheme . "No OTA (1MB APP/3MB FATFS)"
 *                        (o pen drive fica com ~3 MB; o APK tem menos de 1 MB.
 *                         Também funciona "Default 4MB with ffat", com ~1,5 MB)
 *     Upload Mode ...... "UART0 / Hardware CDC"
 *
 *  GRAVAR O FIRMWARE: com "USB-OTG (TinyUSB)" a placa não entra
 *  sozinha em modo de gravação. Antes de clicar em Carregar:
 *     segure BOOT, aperte e solte RESET (ou RST), solte BOOT.
 *  Depois de gravado, aperte RESET uma vez.
 * ============================================================
 */

#include <Arduino.h>
#include "USB.h"
#include "USBHIDKeyboard.h"
#include "USBMSC.h"
#include "FFat.h"
#include "esp_partition.h"
#include "wear_levelling.h"

// ── Pinos ─────────────────────────────────────────────────
// GPIO 4 existe em todas as ESP32-S3 Super Mini e DevKit.
// Evite GPIO 0 (BOOT), 19/20 (USB) e 45/46 (pinos de configuração).
#define PINO_PEDAL   4

// LED RGB da placa (Super Mini: GPIO 48). Se a sua placa não tiver,
// nada acontece. Para desligar o LED, comente a linha abaixo.
#define PINO_LED_RGB 48

// ── Identidade USB ────────────────────────────────────────
// VID 0x303A = Espressif. O app Android reconhece o pedal por este VID
// e pelo PID abaixo (arquivo res/xml/pedal_usb.xml). Se mudar aqui,
// mude lá também. Para produção em série, a Espressif fornece PIDs
// próprios gratuitamente (github.com/espressif/usb-pids).
#define USB_VID_PEDAL      0x303A
#define USB_PID_TECLADO    0x4D42   // modo pedal
#define USB_PID_PENDRIVE   0x4D43   // modo instalação (diferente: não abre o app)

// ── Tecla enviada ─────────────────────────────────────────
// PAGE DOWN é a mesma tecla dos pedais "vira-página" comerciais.
#define TECLA_PEDAL  KEY_PAGE_DOWN

// ── Tempos (milissegundos) ────────────────────────────────
// Pisada: confirma rápido (o músico sente a resposta na hora).
#define CONFIRMA_PISADA_MS   20
// Soltura: só vale depois de ficar solta este tempo. É a mesma
// blindagem do FTSW1 v21: um vacilo de contato no meio da rolagem
// (mola cansada, jack oxidado, cabo mexendo) não pode virar PAUSA.
#define CONFIRMA_SOLTURA_MS  150

USBHIDKeyboard teclado;
USBMSC         pendrive;

static wl_handle_t wl = WL_INVALID_HANDLE;
static uint32_t    tamSetor = 4096;
static uint8_t     bufSetor[4096];
static bool        modoInstalacao = false;

// ═══════════════════════════════════════════════════════════
//  LED
// ═══════════════════════════════════════════════════════════
void led(uint8_t r, uint8_t g, uint8_t b) {
#ifdef PINO_LED_RGB
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  rgbLedWrite(PINO_LED_RGB, r, g, b);
#else
  neopixelWrite(PINO_LED_RGB, r, g, b);
#endif
#endif
}

// ═══════════════════════════════════════════════════════════
//  PEN DRIVE (modo instalação)
//  A partição "ffat" da flash é entregue ao computador/celular
//  como um disco. O sistema de arquivos FAT fica por conta de
//  quem está do outro lado do cabo; aqui só lemos e gravamos
//  setores, passando pela camada de "wear levelling" (a mesma
//  que a biblioteca FFat usa), para não gastar a flash.
// ═══════════════════════════════════════════════════════════
static int32_t aoLer(uint32_t lba, uint32_t offset, void* buffer, uint32_t tamanho) {
  size_t endereco = (size_t)lba * tamSetor + offset;
  if (wl_read(wl, endereco, buffer, tamanho) != ESP_OK) return -1;
  return (int32_t)tamanho;
}

static int32_t aoGravar(uint32_t lba, uint32_t offset, uint8_t* buffer, uint32_t tamanho) {
  size_t endereco = (size_t)lba * tamSetor + offset;
  uint32_t feito = 0;
  while (feito < tamanho) {
    size_t   pos    = endereco + feito;
    size_t   setor  = pos / tamSetor;
    uint32_t dentro = pos % tamSetor;
    uint32_t n      = min(tamSetor - dentro, tamanho - feito);
    size_t   base   = setor * tamSetor;

    if (dentro == 0 && n == tamSetor) {
      // Setor inteiro: apaga e grava direto
      if (wl_erase_range(wl, base, tamSetor) != ESP_OK) return -1;
      if (wl_write(wl, base, buffer + feito, tamSetor) != ESP_OK) return -1;
    } else {
      // Pedaço de setor: lê, altera só o pedaço, apaga e grava de volta
      if (wl_read(wl, base, bufSetor, tamSetor) != ESP_OK) return -1;
      memcpy(bufSetor + dentro, buffer + feito, n);
      if (wl_erase_range(wl, base, tamSetor) != ESP_OK) return -1;
      if (wl_write(wl, base, bufSetor, tamSetor) != ESP_OK) return -1;
    }
    feito += n;
  }
  return (int32_t)tamanho;
}

static bool aoIniciarParar(uint8_t power_condition, bool start, bool load_eject) {
  return true;
}

// Na primeira vez, formata a partição e escreve um LEIA-ME.
// Depois disso nunca mais mexe: o conteúdo é o que você gravou pelo PC.
void prepararDiscoPrimeiraVez() {
  if (!FFat.begin(true)) return;   // true = formata se ainda não estiver formatada
  if (!FFat.exists("/LEIA-ME.txt")) {
    File f = FFat.open("/LEIA-ME.txt", FILE_WRITE);
    if (f) {
      f.print(
        "MUSIC BOX TELEPROMPTER - INSTALACAO DO APLICATIVO\r\n"
        "\r\n"
        "1. Toque no arquivo MusicBox.apk.\r\n"
        "2. Se o Android pedir, permita \"instalar apps desta fonte\".\r\n"
        "3. Toque em Instalar.\r\n"
        "4. Desligue o cabo e ligue de novo SEM pisar no pedal.\r\n"
        "   Pronto: o pedal volta a ser pedal e o app ja reconhece.\r\n"
        "\r\n"
        "Para entrar neste modo de novo: segure o pedal pressionado\r\n"
        "enquanto liga o cabo.\r\n");
      f.close();
    }
  }
  FFat.end();
}

bool iniciarPendrive() {
  prepararDiscoPrimeiraVez();
  const esp_partition_t* part = esp_partition_find_first(
      ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_DATA_FAT, NULL);
  if (!part) return false;   // esquema de partição sem "ffat": veja o cabeçalho
  if (wl_mount(part, &wl) != ESP_OK) return false;
  tamSetor = wl_sector_size(wl);
  if (tamSetor > sizeof(bufSetor)) return false;
  uint32_t setores = wl_size(wl) / tamSetor;

  pendrive.vendorID("MUSICBOX");     // até 8 letras
  pendrive.productID("INSTALADOR");  // até 16 letras
  pendrive.productRevision("1.0");
  pendrive.onRead(aoLer);
  pendrive.onWrite(aoGravar);
  pendrive.onStartStop(aoIniciarParar);
  pendrive.mediaPresent(true);
  return pendrive.begin(setores, tamSetor);
}

// ═══════════════════════════════════════════════════════════
//  SETUP
// ═══════════════════════════════════════════════════════════
void setup() {
  pinMode(PINO_PEDAL, INPUT_PULLUP);
  delay(30);

  // Pedal pisado durante os primeiros 0,3 s = modo instalação
  modoInstalacao = true;
  for (int i = 0; i < 15; i++) {
    if (digitalRead(PINO_PEDAL) != LOW) { modoInstalacao = false; break; }
    delay(20);
  }

  USB.VID(USB_VID_PEDAL);
  USB.manufacturerName("Music Box");

  if (modoInstalacao) {
    USB.PID(USB_PID_PENDRIVE);
    USB.productName("Music Box Instalador");
    if (!iniciarPendrive()) {
      // Sem partição FFat: pisca vermelho e segue como pedal
      for (int i = 0; i < 6; i++) { led(40, 0, 0); delay(150); led(0, 0, 0); delay(150); }
      modoInstalacao = false;
    }
  }

  if (!modoInstalacao) {
    USB.PID(USB_PID_TECLADO);
    USB.productName("Music Box Pedal");
    teclado.begin();
  }

  USB.begin();
  led(0, modoInstalacao ? 0 : 6, modoInstalacao ? 12 : 0);   // verde = pedal, azul = pen drive
}

// ═══════════════════════════════════════════════════════════
//  LOOP — leitura do pedal com confirmação assimétrica
// ═══════════════════════════════════════════════════════════
void loop() {
  if (modoInstalacao) {
    // Pisca azul devagar: "estou como pen drive"
    static uint32_t t = 0;
    static bool aceso = false;
    if (millis() - t > 700) { t = millis(); aceso = !aceso; led(0, 0, aceso ? 14 : 1); }
    delay(10);
    return;
  }

  static bool     pressionado  = false;   // estado já enviado ao celular
  static uint32_t mudouEm      = 0;
  static bool     leituraAnt   = false;

  bool leitura = (digitalRead(PINO_PEDAL) == LOW);
  uint32_t agora = millis();
  if (leitura != leituraAnt) { leituraAnt = leitura; mudouEm = agora; }
  uint32_t estavel = agora - mudouEm;

  if (!pressionado && leitura && estavel >= CONFIRMA_PISADA_MS) {
    pressionado = true;
    teclado.press(TECLA_PEDAL);
    led(0, 30, 0);
  } else if (pressionado && !leitura && estavel >= CONFIRMA_SOLTURA_MS) {
    pressionado = false;
    teclado.release(TECLA_PEDAL);
    led(0, 6, 0);
  }

  delay(2);
}

# TV Launcher

Launcher leve e personalizado para Android TV, feito para o **Xiaomi Mi TV Stick (MDZ-24-AA)**
(Android TV 9, 1 GB de RAM), mas funciona em qualquer Android TV com API 21 ou superior.

- APK de ~180 KB, sem Leanback/Compose/AppCompat: só `Activity` + `RecyclerView`
- Relógio e data, linha de **Favoritos** (ordenável) e grade de **todos os apps**
- Usa o banner 16:9 dos apps de TV; apps de celular (sideload) aparecem com ícone e nome
- Menu lateral do app (segure OK ou aperte MENU): favoritar, mover (◀ ▶ e OK para confirmar), ocultar, informações, desinstalar
- Cabeçalho recolhe ao descer na grade; rolagem suave mantendo o próximo card à vista
- Atualiza sozinho quando um app é instalado ou removido
- Serviço de acessibilidade **opcional** para o botão HOME abrir este launcher sem root

## Instalação no Mi TV Stick

1. No stick: **Configurações → Preferências do dispositivo → Sobre → Build**, clique 7 vezes
   para habilitar as Opções do desenvolvedor. Em **Opções do desenvolvedor**, ative **Depuração USB**
   (para ADB via rede) ou permita **Fontes desconhecidas** para o app que vai instalar o APK.
2. Instale o APK por um destes caminhos:
   - **ADB pela rede** (PC na mesma rede):
     ```bash
     adb connect IP_DO_STICK:5555
     adb install -r tv-launcher.apk
     ```
   - **Send Files to TV / Downloader / pendrive com gerenciador de arquivos.**
3. Abra o "TV Launcher" na lista de apps.

## Fazer o botão HOME abrir o TV Launcher

O Android TV não tem seletor de launcher padrão: o HOME sempre abre o launcher do sistema
(`com.google.android.tvlauncher`). Há duas formas de contornar:

**A) Sem PC (acessibilidade):** no launcher, ⚙ de opções → *Ativar botão HOME (Acessibilidade)* →
ative **TV Launcher – botão HOME**. O serviço consome o `KEYCODE_HOME` e também troca o launcher
original pelo nosso se ele aparecer (por exemplo, após o boot). Não lê o conteúdo da tela.

**B) ADB (definitivo):** desabilita o launcher original, e o nosso passa a ser o único HOME.
```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```
Para reverter:
```bash
adb shell pm enable com.google.android.tvlauncher
```
> Com o launcher original desabilitado, a linha de recomendações e o atalho de entradas do
> Google somem. As configurações continuam acessíveis pelo ícone de engrenagem.

### Se o HOME continuar abrindo o launcher original

- **Definir como preferido não basta.** O `tvlauncher` declara o HOME com prioridade 2, e o
  Android 9 sempre escolhe o candidato de maior prioridade sem consultar a preferência. Por isso
  `adb shell cmd package set-home-activity com.korsarts.tvlauncher/.MainActivity` retorna
  `Success` mas não muda nada enquanto o launcher original estiver habilitado. Use a opção **B**.
  Ainda vale rodar esse comando se houver outro launcher instalado (Projectivy, ATV Launcher...),
  para o sistema escolher o nosso entre os que sobram.
- **Outro launcher pode estar capturando o botão.** Launchers como o Projectivy têm o próprio
  serviço de acessibilidade, que consome o HOME antes do nosso. Veja quais estão ativos:
  ```bash
  adb shell settings get secure enabled_accessibility_services
  ```
  Deixe só o nosso (isso substitui a lista inteira):
  ```bash
  adb shell settings put secure enabled_accessibility_services com.korsarts.tvlauncher/com.korsarts.tvlauncher.HomeButtonService
  adb shell settings put secure accessibility_enabled 1
  ```
- **Para conferir** qual app o sistema abre no HOME:
  ```bash
  adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  ```
  A última linha deve ser `com.korsarts.tvlauncher/.MainActivity`.

## Build

Requisitos: JDK 17+ e Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleRelease
```
APK em `app/build/outputs/apk/release/app-release.apk`.

### Assinatura

- **Local:** crie `keystore.properties` na raiz (já está no `.gitignore`):
  ```properties
  storeFile=release.jks
  storePassword=...
  keyAlias=tvlauncher
  keyPassword=...
  ```
- **GitHub Actions:** adicione os secrets `KEYSTORE_BASE64` (`base64 -w0 release.jks`),
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` e `SIGNING_KEY_PASSWORD`.
- Sem keystore, o APK é assinado com a chave de debug. Funciona, mas atualizações só instalam
  por cima se a chave for a mesma.

O workflow gera o APK como artefato em todo push e publica um Release ao criar uma tag `v*`.

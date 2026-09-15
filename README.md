# Chess Vault

App Android de estatísticas do Chess.com para o usuário **LuckGaspar**.
Importa as partidas pela API pública do Chess.com, guarda tudo num banco local
e mostra estatísticas: aberturas mais jogadas, erros cometidos, altura da
partida em que o erro acontece, quando você pune o erro do oponente e quando
deixa passar — além do tabuleiro (gadget WebView do próprio chess.com).

- Pacote: `com.chessvault.app`
- Offline-first: depois de sincronizar, as análises rodam no aparelho.

## Funcionalidades

- Importação de partidas via `https://api.chess.com/pub/player/<user>/games/...`.
- Parser PGN em Java puro (`GameParser`), com teste unitário em `tests/`.
- Extrai lances SAN e relógios `[%clk]`; classifica resultado e cor do usuário.
- Proxy de erro: detecta lances ruins (ex.: mate recebido → ~3 lances antes).
- Estatísticas por abertura, por fase do jogo e punição de erros do oponente.
- Banco SQLite local (`DatabaseHelper`); sincronização em 2º plano
  (`SyncService` + alarmes + reinício após boot).
- Tabuleiro interativo via WebView (`assets/chess-vault/index.html`).
- Ponte JS↔Java (`VaultBridge`), log de crash em `chess_crash.log`.

## Permissões

`INTERNET`, `ACCESS_NETWORK_STATE`, notificações, alarmes exatos e boot
(sync periódica). Sem localização, sem contatos.

## Compilar

Pipeline sem Gradle (aapt2 + javac + d8), igual aos demais apps do portfólio:

```sh
cd ~/projects/chess-vault-apk
bash build.sh
```

O APK final (`ChessVault-*.apk`) sai em `build/` e é copiado para
`Documents/`. A keystore de release é gerada automaticamente no primeiro
build e **não** vai para o git (ver `.gitignore`).

## Estrutura

```
chess-vault-apk/
├── AndroidManifest.xml
├── src/com/chessvault/app/
│   ├── MainActivity.java   # UI + WebView
│   ├── VaultBridge.java    # ponte JS↔Java
│   ├── GameParser.java     # parser PGN puro-Java (testável)
│   ├── DatabaseHelper.java # SQLite
│   ├── SyncService.java    # sync em 2º plano
│   ├── AlarmReceiver.java / BootReceiver.java
├── assets/chess-vault/     # tabuleiro + telas web
├── res/                    # layouts, strings, ícone
└── tests/GameParserTest.java
```

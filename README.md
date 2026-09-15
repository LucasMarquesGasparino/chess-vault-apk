# Chess Vault

App Android de estatísticas do Chess.com para o usuário **LuckGaspar**.
Importa as partidas pela API pública do Chess.com, guarda tudo num banco local
e mostra estatísticas: aberturas mais jogadas, erros cometidos, altura da
partida em que o erro acontece, taxa de vitórias por ritmo e cor — além de
replay completo offline em tabuleiro interativo no Canvas.

- Pacote: `com.chessvault.app`
- Offline-first: depois de sincronizar, as análises e o replay rodam 100% no aparelho.

## Funcionalidades

- Sincronização em dois modos:
  - **Histórico Completo (1º Sync)**: baixa todos os arquivos mensais da conta com barra de progresso em tempo real, proteção contra rate limit da API pública e gravação de arquivos já sincronizados (`synced_archives`). Após a conclusão, o botão é ocultado automaticamente.
  - **Sincronização Rápida**: atualiza apenas o mês atual e anterior (usado também no sync automático diário às 9h e 21h).
- Parser PGN em Java puro (`GameParser`) com suporte a quebras CRLF, comentários multilinhas e teste unitário em `tests/`.
- Extrai lances SAN e relógios `[%clk]`; classifica resultado e cor do usuário.
- Proxy de erro: detecta lances críticos em mates e fases da partida.
- Banco SQLite local (`DatabaseHelper`); sincronização em segundo plano via `ForegroundService` (`dataSync`).
- Tabuleiro interativo offline em Canvas com alto contraste e navegação por toque (`board.js`).
- Ponte JS↔Java (`VaultBridge`), exportação JSON e agendamento de alarmes exatos.

## Permissões

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
notificações, alarmes exatos e boot (sync periódica). Sem localização, sem contatos.

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

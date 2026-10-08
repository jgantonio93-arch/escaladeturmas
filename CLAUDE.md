# escaladeturmas

Dois projetos independentes, ambos páginas estáticas sem build:

- `index.html` (raiz): app de sorteio de escala dos subgrupos. Não tem relação com o jogo.
- `jogo/`: **Paintball com os Amigos** (nome oficial; já se chamou "Tiroteio Emesqaeda"), jogo 3D de tiro de paintball com os rostos de amigos (fotos `amigo1..8.jpg`: Arthur, Igor, Hugo, Lucas, Isabela, Guilherme, Ana Lara, Amanda) e personagens criados com foto do aparelho.

Textos da interface, comentários e mensagens de commit em português.

## Publicação

- GitHub Pages está configurado na branch `claude/jogo-3d-tiro-amigos-g4vola` (pasta raiz). Um push nessa branch publica em 1–2 min em
  https://jgantonio93-arch.github.io/escaladeturmas/jogo/
- Versão em arquivo único (para mandar por WhatsApp etc.): `python3 jogo/tools/montar_arquivo_unico.py` gera `paintball-com-os-amigos.html` na raiz (fora do git).

## Arquitetura do jogo (`jogo/index.html`, um arquivo só)

Three.js 0.160 (`three.min.js`, global `THREE`) e mqtt.js 5 (global `mqtt`) via CDN. Todo o código fica em um `<script>` dentro de uma IIFE, nesta ordem:

1. **Config** `AMIGOS` (nome, foto, cores, velocidade): trocar nomes/fotos aqui. Personagens criados pelo jogador ficam em `CUSTOM` (`localStorage` `pb-custom`, foto jpeg 160×200 em dataURL); `charList()` junta os dois (chaves `'0'..'7'` e `'c:<id>'`; mudar a lista de `AMIGOS` muda os índices do online → aumente `VERSAO`).
2. **Cena/luzes** (`hemi`, `sun`) e **Mapas** (`MAPS`: `arena` (o mapa original, padrão), `campus`, `armazem`, `praia`). Cada mapa tem uma função `build*` que monta chão, muros, obstáculos (`addBox`/`collider` → array `obstacles` de `Box3`) e decoração em `mapGroup`. **Sem `Math.random` nos mapas** (usar `seeded()`): tem que ficar idêntico em todos os aparelhos do online. `loadMap(id)` troca o mapa; `setLook` ajusta céu/neblina/luz (mude a neblina existente, não crie outra `THREE.Fog`, isso deixa tudo invisível).
3. **Bonecos** `buildFriend` (rosto da foto num boneco cabeçudo), inimigos do modo sozinho (`enemies`, IA em `updateEnemies`).
4. **Jogador** `player` + colisão (`collides`, `moveWithCollision`, `groundHeight`: dá para subir em caixas de até ~1,25 m de diferença).
5. **Armas** `WEAPONS` (dano, cadência, pente, recarga, velocidade, espalhamento, chumbinhos, explosão, `hs` = multiplicador do headshot, `ic` ícone), `GG_ORDER` (Gun Game, filtrado pelas armas liberadas em `ggOrder()`), viewmodel `buildViewmodel` + clarão `flashSprite`. Tiros são bolinhas físicas (`fireBall`/`updateBalls`; a 2ª esfera do alvo é a cabeça → `head`); balde explode (`explode`).
5b. **HUD** `updateHud`, aviso de abates `killFeed`, números de dano `dmgNumber` (branco normal, amarelo headshot, vermelho abate) e barra de vida em cima do alvo `showBar` (HTML projetado em `updateFloats`), placar `renderBoard` (Tab / 📋), chat `openChat` (T / 💬), relógio da partida `matchHud`.
6. **Movimento** agachar/deslizar/slide cancel (`crouchPressed`, `jumpPressed`, `updatePlayer`).
7. **Power-ups** `POWERS` (velocidade, invencível, vida).
8. **Online** (seção "Online"): modos em `MODOS` (`dm`, `tdm`, `gg`, `roleta`, `zumbi`), partida (`startMatch`, `endMatch`, `endByTime`), Roleta de Armas (`rollWeapons`, `net.roll`), lobby/ranking (`openLobby`), tela de criar sala (`renderCfgForm`, `draftCfg`), menu (`openHome`, `setTab`, `renderChars`) e criador de personagem (`openCreator`).
8b. **Modo Zumbi** (seção "Modo Zumbi (cooperativo)"): o dono da sala simula as hordas em `Z.sim` (`zHostTick`, `zMove`, `zSpawn`, rodadas em `zStartRound`) e manda `zs` 10×/s; todos desenham em `zombies` (`zApply`, `updateZombiesView`, rosto esverdeado em `zombieTex`). Vida zerada → derrubado (`down`) por `BLEED_S` (30 s); amigo levanta segurando E/LEVANTAR por `REVIVE_S` perto (`revive` → `up`); se ninguém levantar → `bled` (morto até a próxima rodada); todos caídos → `zover`. Zumbi sozinho = "sala local" (`startLocalZombie`, `net.local`), sem internet.
9. **Loop** `frame()`. `window.__jogo` expõe estado para testes.

## Telas e configurações

- Telas em `SCREENS` (`showScreen`): menu principal com abas Salas abertas / Ranking / Controles e o seletor de personagem + nome sempre embaixo (`startScreen`), Criar sala / regras da sala (`roomScreen`), Treino solo (`soloScreen`, `soloCfg` em `pb-solo`), Criar personagem (`charScreen`), Controles e ajustes por cima da pausa (`controlsScreen`; o painel `#controlsPanel` é movido entre a aba e essa tela), Pausa e Resultado.
- Configurações do jogador em `settings` (`localStorage` `pb-settings`): sensibilidade mouse/toque, volume, campo de visão, tamanho dos botões do celular (`--B`), qualidade, mira assistida, inverter Y, FPS. Aplicam na hora (`readSettings`).
- Celular: vida em cima à esquerda; joystick flutuante (aparece onde o dedo toca na metade esquerda: `stickStart`/`stickRest`); botões à direita.

## Online

- Sem servidor próprio: todos conectam por WebSocket seguro a 3 servidores MQTT públicos ao mesmo tempo (`BROKERS`: EMQX, HiveMQ, Mosquitto) e descartam cópias (campo `q`). Tópicos `pbamigos/v{VERSAO}/{CODIGO}/h` (para o dono) e `/a` (do dono para todos); lista de salas em `pbamigos/v{VERSAO}/lobby/{CODIGO}` (mensagem retida, renovada a cada 5 s).
- Quem cria a sala é o **dono/host**: decide entrada, vida, dano (pela arma permitida: `hostWeaponOf`), abates, times, power-ups, vitória. Clientes mandam posição (`s`, 10/s), tiros (`shot`) e acertos (`hit`, quem atirou detecta).
- Configuração da sala `net.cfg` (`cleanCfg`, opções em `OPT`): modo, mapa, `tempo` (min, 0 = sem limite), `kills` (0 = sem meta), `armas` (lista das liberadas), `vida` (×0,5/1/2), `respawn` (s), `dif` (zumbi), `rInt` (s entre trocas da roleta, 0 = aleatório 10–40 s), `rIgual` (todos com a mesma arma ou cada um com uma), `power`, `regen`, `hs` (bônus de headshot), `ff` (fogo amigo nos Times). Vai junto no `roster` (com `roll`, `tl` = segundos que faltam e `rn` = segundos até a próxima troca).
- Dano: quem atira detecta o acerto e manda `hit` com `h:1` se foi na cabeça; o dono calcula e manda `hp` com `d` (dano) e `h`, e o atirador mostra o número. Zumbis: `zhit` → `zd`.
- Personagem criado: `hello` leva `char: -1` e `cor`; a foto vai em `face` (validada por `validFace`, até 60 KB) e o dono repassa para todos (`net.faces`).
- Ranking: cada jogador publica seus números (`stats`, `pb-stats`) retidos em `pbamigos/rank/<pid>`; o menu escuta junto com a lista de salas.
- **Mudou o protocolo/mensagens? Aumente `VERSAO`** (aparece no rodapé do menu; versões diferentes não se veem). Hoje é 13.
- Vida: no PvP (se `regen` ligado) volta após `REGEN_DELAY` (5 s) sem dano, `REGEN_RATE` (12%) por segundo, aplicada pelo dono (`setInterval` de 0,5 s). Nas hordas não volta: kit médico (+50), bolsa de sangue 🩸 (+30, 30% de chance quando um zumbi morre, some em 25 s), +25 a cada rodada nova e ser levantado (40%).

## Como testar (sem acesso aos servidores públicos)

- Servidor MQTT local: `npm i aedes ws` e um `http` + `WebSocketServer` repassando para `aedes.handle(createWebSocketStream(ws))` em `127.0.0.1:8888`; abrir o jogo com `?mqtt=ws://127.0.0.1:8888` (aceita lista separada por vírgula).
- Playwright com Chromium já instalado (`--use-gl=swiftshader --enable-unsafe-swiftshader`); servir `jogo/` com `python3 -m http.server`; interceptar as URLs do CDN com cópias locais (`npm pack three@0.160.0 mqtt@5.10.1`).
- No navegador de teste o jogo roda a poucos FPS e o `dt` é limitado a 0,05 s: o tempo de jogo passa mais devagar que o real. Viewports pequenas ajudam.
- Jogador de desktop entra pausado ("Pronto?"): no teste sem pointer lock use `__jogo.pauseGame()`/`resumeGame()`; o Esc não pausa no headless.

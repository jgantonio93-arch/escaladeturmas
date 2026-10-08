# escaladeturmas

Dois projetos independentes, ambos páginas estáticas sem build:

- `index.html` (raiz): app de sorteio de escala dos subgrupos. Não tem relação com o jogo.
- `jogo/`: **Tiroteio Emesqaeda** (nome oficial; antes "Paintball dos Amigos"), jogo 3D de tiro de paintball com os rostos de amigos (fotos `amigo1..3.jpg`).

Textos da interface, comentários e mensagens de commit em português.

## Publicação

- GitHub Pages está configurado na branch `claude/jogo-3d-tiro-amigos-g4vola` (pasta raiz). Um push nessa branch publica em 1–2 min em
  https://jgantonio93-arch.github.io/escaladeturmas/jogo/
- Versão em arquivo único (para mandar por WhatsApp etc.): `python3 jogo/tools/montar_arquivo_unico.py` gera `tiroteio-emesqaeda.html` na raiz (fora do git).

## Arquitetura do jogo (`jogo/index.html`, um arquivo só)

Three.js 0.160 (`three.min.js`, global `THREE`) e mqtt.js 5 (global `mqtt`) via CDN. Todo o código fica em um `<script>` dentro de uma IIFE, nesta ordem:

1. **Config** `AMIGOS` (nome, foto, cores, velocidade): trocar nomes/fotos aqui.
2. **Cena/luzes** (`hemi`, `sun`) e **Mapas** (`MAPS`: `campus`, `armazem`, `praia`). Cada mapa tem uma função `build*` que monta chão, muros, obstáculos (`addBox`/`collider` → array `obstacles` de `Box3`) e decoração em `mapGroup`. **Sem `Math.random` nos mapas** (usar `seeded()`): tem que ficar idêntico em todos os aparelhos do online. `loadMap(id)` troca o mapa; `setLook` ajusta céu/neblina/luz (mude a neblina existente, não crie outra `THREE.Fog`, isso deixa tudo invisível).
3. **Bonecos** `buildFriend` (rosto da foto num boneco cabeçudo), inimigos do modo sozinho (`enemies`, IA em `updateEnemies`).
4. **Jogador** `player` + colisão (`collides`, `moveWithCollision`, `groundHeight`: dá para subir em caixas de até ~1,25 m de diferença).
5. **Armas** `WEAPONS` (dano, cadência, pente, recarga, velocidade, espalhamento, chumbinhos, explosão), `GG_ORDER` (Gun Game), viewmodel `buildViewmodel`. Tiros são bolinhas físicas (`fireBall`/`updateBalls`); balde explode (`explode`).
6. **Movimento** agachar/deslizar/slide cancel (`crouchPressed`, `jumpPressed`, `updatePlayer`).
7. **Power-ups** `POWERS` (velocidade, invencível, vida).
8. **Online** (seção "Online: mata-mata").
8b. **Modo Zumbi** (seção "Modo Zumbi (cooperativo)"): o dono da sala simula as hordas em `Z.sim` (`zHostTick`, `zMove`, `zSpawn`, rodadas em `zStartRound`) e manda `zs` 10×/s; todos desenham em `zombies` (`zApply`, `updateZombiesView`, rosto esverdeado em `zombieTex`). Vida zerada → derrubado (`down`) por `BLEED_S` (30 s); amigo levanta segurando E/LEVANTAR por `REVIVE_S` perto (`revive` → `up`); se ninguém levantar → `bled` (morto até a próxima rodada); todos caídos → `zover`. Zumbi sozinho = "sala local" (`startLocalZombie`, `net.local`), sem internet.
9. **Loop** `frame()`. `window.__jogo` expõe estado para testes.

## Telas e configurações

- Telas em `SCREENS` (`showScreen`): menu principal, Jogar sozinho (modo Ondas/Zumbis, mapa, dificuldade), Online, Configurações, Como jogar, Pausa (`pauseGame`/`resumeGame`) e Resultado (`showResults`, recordes em `localStorage`).
- Configurações do jogador em `settings` (`localStorage` `pb-settings`): sensibilidade mouse/toque, volume (`master` gain), campo de visão, qualidade (pixel ratio + sombras, `applyQuality`), mira assistida, inverter Y, FPS.

## Online

- Sem servidor próprio: todos conectam por WebSocket seguro a 3 servidores MQTT públicos ao mesmo tempo (`BROKERS`: EMQX, HiveMQ, Mosquitto) e descartam cópias (campo `q`). Tópicos `pbamigos/v{VERSAO}/{CODIGO}/h` (para o dono) e `/a` (do dono para todos); lista de salas em `pbamigos/v{VERSAO}/lobby/{CODIGO}` (mensagem retida, renovada a cada 5 s).
- Quem cria a sala é o **dono/host**: decide entrada, vida, dano (pela arma permitida: `hostWeaponOf`), abates, times, power-ups, vitória. Clientes mandam posição (`s`, 10/s), tiros (`shot`) e acertos (`hit`, quem atirou detecta).
- Configuração da sala `net.cfg` (`cleanCfg`): modo `dm` | `tdm` (times, sem fogo amigo) | `gg` (Gun Game) | `zumbi` (cooperativo), mapa, dificuldade (zumbi), abates, armas, vida (×0,5/1/2), power-ups. Vai junto no `roster`.
- **Mudou o protocolo/mensagens? Aumente `VERSAO`** (aparece na tela do online; versões diferentes não se veem).
- Regra especial: jogador chamado "Ana" tem 3,5× vida (`maxHpOf`).
- Regeneração de vida: após `REGEN_DELAY` (5 s) sem levar dano, recupera `REGEN_RATE` (12%) da vida máxima por segundo. No online quem aplica é o dono da sala (`net.lastDmg` + `setInterval` de 0,5 s, manda `hp`); no modo sozinho é local em `updatePlayer`.

## Como testar (sem acesso aos servidores públicos)

- Servidor MQTT local: `npm i aedes ws` e um `http` + `WebSocketServer` repassando para `aedes.handle(createWebSocketStream(ws))` em `127.0.0.1:8888`; abrir o jogo com `?mqtt=ws://127.0.0.1:8888` (aceita lista separada por vírgula).
- Playwright com Chromium já instalado (`--use-gl=swiftshader --enable-unsafe-swiftshader`); servir `jogo/` com `python3 -m http.server`; interceptar as URLs do CDN com cópias locais (`npm pack three@0.160.0 mqtt@5.10.1`).
- No navegador de teste o jogo roda a poucos FPS e o `dt` é limitado a 0,05 s: o tempo de jogo passa mais devagar que o real. Viewports pequenas ajudam.
- Jogador de desktop entra pausado ("Clique para começar"): clique no canvas antes de testar.

# TonyNotes

App Android (Kotlin) de anotações à mão com **tela infinita na horizontal e na vertical**,
pensado para a S Pen dos Galaxy.

<p>
  <img src="docs/screenshots/1-inicio.png" width="200" alt="Página inicial com pastas">
  <img src="docs/screenshots/4-editor.png" width="200" alt="Editor">
  <img src="docs/screenshots/15-barra-esquerda.png" width="200" alt="Barra em pé na esquerda com 3 cores">
  <img src="docs/screenshots/16-folhas-verticais.png" width="200" alt="Folhas verticais">
</p>

As capturas em `docs/screenshots` são geradas pelo CI (Robolectric) a cada mudança.

## Como usar

### Pastas (página inicial)
- **Busca** de notas e pastas (ignora acentos).
- O app abre na tela de **Pastas**. Toque em **+** para criar uma pasta (nome e cor).
- Dentro de uma pasta, o **+** cria uma **nota** ou uma **subpasta**.
- Segure uma pasta ou nota para **renomear, mudar a cor, mover, duplicar ou excluir**.
- As notas aparecem com miniatura e data da última alteração.

### Barra de ferramentas
- Arraste pela **alça ⋮⋮** para levar a barra a qualquer lugar da tela.
- Solta perto da borda **esquerda** ou **direita**, ela fica **em pé** e mostra **3 cores
  rápidas** (toque para usar, toque longo para trocar a cor).
- Um toque na alça abre as posições prontas (topo, esquerda, direita).

### Página
- Menu ⋮ → **Página**: **infinita** (padrão), **folhas verticais** (uma embaixo da outra)
  ou **folhas horizontais** (lado a lado), em **retrato** ou **paisagem**.
- **Folhas infinitas**: uma folha nova aparece sozinha quando você escreve na última.
  Desligado, escolha o número de folhas.
- O PDF exportado sai com uma página por folha.

### Editor
- **S Pen** escreve; segurando o **botão lateral**, apaga (dá para desligar).
- **1 dedo** move a tela em qualquer direção; **2 dedos** dão zoom (5% a 800%).
- Menu ⋮ → **Dedo: escreve** faz o dedo também escrever (aí 2 dedos movem).

### Canetas (toque de novo na caneta, ou na bolinha de cor, para abrir a bandeja)
- **Caneta-tinteiro**, **Caneta**, **Lápis** (grão de grafite do tamanho dos pixels da tela, em qualquer zoom), **Caligrafia** (bico chato
  inclinado), **Pincel** (pressão forte e pontas afinadas) e **Marca-texto** (translúcido).
- Cada pincel lembra sua **espessura**, **opacidade** e **cor**.
- **Marca-texto** com ponta chanfrada: **tamanho**, **transparência**, **espessura da ponta**
  e **endireitar linhas** (o traço vira uma reta, perfeitamente horizontal quando quase).
- Paleta de cores + **cor personalizada** (espectro, matiz e código hex); as cores
  personalizadas ficam salvas.
- **Formas automáticas**: linha reta, círculo/elipse, triângulo, retângulo, pentágono.
- **Favoritos**: salve combinações de caneta e use pela barra (segure para remover).

### Caneta com latência mínima
- A tinta em andamento é desenhada direto no buffer da tela (front buffer), sem
  esperar o próximo quadro, e a nota não é redesenhada a cada ponto.
- Em Android 14+, previsão de movimento quando o front buffer não está disponível.
- O editor pede a taxa de atualização máxima da tela (ex.: 120 Hz).
- A previsão de movimento entra no próprio traço (mesma textura e transparência).
- Traços translúcidos (marca-texto, lápis) são desenhados sem camada extra por traço.
- Pode ser desligada no menu ⋮ ("Tinta de latência mínima").

### Texto (como no Samsung Notes)
- Toque com a ferramenta **T** e digite direto na tela. A barra acima do teclado tem
  fonte, tamanho, **negrito**, *itálico*, sublinhado, tachado, alinhamento, cor e
  marca-texto. Com um trecho selecionado, o estilo vale só para o trecho.
- Toque de novo num texto selecionado para editar. Com a seleção: girar (alça ou 90°),
  aumentar/diminuir, espelhar, virar, endireitar, cor, frente/trás, duplicar e excluir.

### Imagens e PDF
- Toque na imagem com a seleção: girar livre (com ímã nos ângulos retos), recortar
  (livre, 1:1, 16:9, círculo, à mão livre), ajustes (filtros, brilho, contraste,
  saturação, opacidade), cantos, moldura, sombra, espelhar, travar, substituir,
  salvar na galeria.
- PDF: importa todas as páginas em coluna, lado a lado ou em grade, travadas como
  fundo. Destrave uma página para mover, girar, recortar ou separar.

### Outras ferramentas
- **Borracha** (toque de novo para opções): **borracha de traço** ou **borracha de área**,
  tamanho, **apagar somente marca-texto**, apagar tudo.
- **Seleção** (toque de novo para opções): **laço** ou **retângulo**, e **incluir objetos
  parcialmente selecionados**. Depois **mova**, **redimensione**, **duplique**,
  **mude a cor** ou **exclua**.
- **Texto**: toque na tela para digitar; toque num texto existente para editar.
- **Imagem**: insere uma foto da galeria (já vem selecionada para posicionar).
- **Modelo da página**: em branco, pontilhado, pautado ou quadriculado, e cor do papel.
- **Desfazer / Refazer**, **Exportar PNG** (Imagens/TonyNotes) e
  **Exportar PDF** (Downloads/TonyNotes).
- Tudo é salvo automaticamente ao sair da nota.

## Visual
Direção definida com o Taste Skill (redesign), as Web Interface Guidelines da Vercel e o
DESIGN.md do Notion (Awesome Design): papel quente, um único acento cor de tinta, títulos em
**Fraunces** e interface em **Onest** (fontes OFL em `app/src/main/assets/fonts`, com licenças).
Todos os pares de texto/fundo passam no contraste WCAG AA (auditado com o Playwright CLI).

## Como instalar
O APK é gerado pelo GitHub Actions (workflow `TonyNotes (APK Android)`):
abra a execução mais recente na aba **Actions**, baixe o artefato `tonynotes-apk`,
descompacte e instale o `.apk` no celular (permita "instalar apps desconhecidos").

O APK é assinado com uma chave de debug fixa (`app/debug.keystore`), então as versões
novas instalam por cima das antigas sem perder as notas.

## Compilar localmente
Requer JDK 17 e Android SDK (Android Studio):

```
cd notas-infinitas
./gradlew assembleDebug
```

## Próximos passos
- Reconhecimento de escrita à mão (converter para texto)
- Busca nas notas
- Gravação de voz junto com a nota
- Importar PDF para anotar por cima

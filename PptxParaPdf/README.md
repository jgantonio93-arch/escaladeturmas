# PPTX para PDF — app Android

Converte apresentações do PowerPoint (.pptx) em PDF direto no celular, **sem internet e sem bibliotecas externas**. O motor de conversão lê o XML do arquivo e redesenha cada slide com a API nativa `PdfDocument` do Android.

## Como usar o app
1. Toque em **Escolher arquivo .pptx**, ou, em outro app (WhatsApp, e-mail, Drive, gerenciador de arquivos), toque em **Compartilhar** no .pptx e escolha **PPTX para PDF**. Também aceita "Abrir com". Se vários arquivos forem enviados juntos, o primeiro .pptx é convertido.
2. Aguarde a conversão (barra de progresso por slide).
3. **Salvar PDF**, **Abrir PDF** ou **Compartilhar**.

## Como gerar o APK

### Opção A — sem instalar nada (GitHub Actions)
1. O projeto fica na pasta `PptxParaPdf/` deste repositório; o workflow está em `.github/workflows/build-apk.yml` (na raiz). Ele roda a cada push que altere a pasta, ou manualmente em **Actions → Gerar APK → Run workflow**.
2. Abra a aba **Actions** e aguarde o workflow **Gerar APK** (~5 min).
3. Ao terminar, baixe o artefato **PptxParaPdf-apk**, descompacte e instale o `app-release.apk` no celular (permita "instalar apps de fontes desconhecidas").

### Opção B — Android Studio
Abra a pasta no Android Studio (Ladybug ou mais novo) e use **Build → Build APK(s)**.
Ou pelo terminal: `./gradlew assembleRelease` → `app/build/outputs/apk/release/`.

> O APK de release é assinado com a chave de debug para instalar direto. Para publicar na Play Store, configure sua própria chave em `app/build.gradle.kts`.

## O que é convertido
- Tamanho real do slide (16:9, 4:3 ou personalizado), slides ocultos são ignorados
- Fundos (cor, degradê, imagem) e herança de layout/mestre/tema
- Formas (retângulos, elipses, setas, estrelas, fluxograma, formas livres), linhas e conectores com pontas de seta
- Imagens com recorte, rotação e espelhamento
- Textos: tamanho, negrito, itálico, sublinhado, cores, alinhamento, marcadores e numeração, espaçamento, ajuste automático, texto vertical
- Tabelas (com estilos de tabela, mesclagens e bordas)
- SmartArt e gráficos básicos (colunas, barras, linhas, áreas, pizza e rosca)

## Limitações
- Formato antigo `.ppt` e arquivos protegidos por senha não são suportados
- Fontes são substituídas pelas do Android (sans/serif/mono), então quebras de linha podem diferir levemente
- Imagens EMF/WMF, efeitos 3D, sombras, WordArt avançado e animações não são renderizados
- Gráficos complexos (combinados, eixos secundários, rótulos detalhados) aparecem simplificados

## Estrutura
```
app/src/main/java/com/conversor/pptxpdf/
  MainActivity.kt          interface (escolher, converter, salvar, compartilhar)
  PptxToPdfConverter.kt    motor de conversão PPTX → PDF
../.github/workflows/build-apk.yml   compilação automática do APK (na raiz do repositório)
```

# Notas Infinitas

App Android (Kotlin) de anotações à mão com **tela infinita na horizontal e na vertical**,
pensado para a S Pen dos Galaxy.

## Como usar
- **S Pen** escreve. Segurando o **botão lateral**, ela apaga.
- **1 dedo** move a tela para qualquer direção; **2 dedos** dão zoom (5% a 800%).
- **☝ Dedo move / Dedo escreve**: alterna se o dedo também escreve (útil sem caneta).
  No modo "Dedo escreve", 2 dedos movem e dão zoom.
- **Borracha** apaga o traço inteiro que ela tocar.
- **Desfazer / Refazer**, **Centralizar** (também tocando no indicador de zoom),
  **Exportar PNG** (salva em Imagens/NotasInfinitas) e **Limpar**.
- A nota é salva automaticamente quando o app sai da tela.

## Como instalar
O APK é gerado pelo GitHub Actions (workflow `Notas Infinitas (APK Android)`):
abra a execução mais recente na aba **Actions**, baixe o artefato `notas-infinitas-apk`,
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
- Várias notas (lista/pastas)
- Exportar PDF
- Borracha parcial (apagar só um pedaço do traço) e laço de seleção
- Renderização com menor latência (front buffer / Jetpack Ink)

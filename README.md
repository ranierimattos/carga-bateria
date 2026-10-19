# Carga Bateria

App Android mínimo para ver a **corrente (mA)** e a **potência (W)** do carregamento em tempo real.
Também mostra tensão, temperatura, % da bateria e tempo estimado até 100%.

*A tiny Android app that shows battery charging current (mA) and power (W) in real time. No ads, no permissions, no internet.*

- Uma tela só, sem anúncios, **sem nenhuma permissão** e sem acesso à internet.
- APK de ~10 KB, sem bibliotecas (só o SDK do Android). Android 5.0+.
- Atualiza a cada 1 segundo e mantém a tela ligada enquanto aberto. Não roda em segundo plano.

## Baixar

**[⬇ Baixar a última versão (carga-bateria.apk)](https://github.com/ranierimattos/carga-bateria/releases/latest/download/carga-bateria.apk)**

Abra o link no celular, baixe e instale. O Android vai pedir para permitir
"instalar apps desconhecidos" para o navegador ou gerenciador de arquivos.
A versão instalada aparece no rodapé do app.

**Para atualizar:** desinstale a versão anterior e instale a nova. Cada versão é assinada com
uma chave descartável (assim o projeto não precisa guardar nenhuma chave secreta), e o Android
não aceita instalar por cima de uma assinatura diferente. O app não guarda dados importantes,
então nada se perde.

## Como funciona

- Os valores vêm de `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`, que é o que o kernel do
  aparelho reporta. A unidade (µA ou mA) e o sinal variam por fabricante; o app detecta e mostra
  sempre o valor absoluto, com a cor indicando carregando (verde) ou descarregando (laranja).
- Potência = tensão × corrente da bateria (o que entra na bateria, não o que sai do carregador).
- **Célula dupla** (detecção automática, sem configuração):
  - *Células em série*: se a tensão reportada passa de ~6 V, o app mostra a tensão total e por célula.
  - *Corrente pela metade*: o app compara a corrente reportada com a carga que realmente entra/sai
    da bateria (contador de carga, ou variação de % × capacidade de projeto). Se a razão for ~2,
    passa a multiplicar a corrente por 2. Leva ~1–3 min de uso na primeira vez e fica salvo.
    Enquanto não confirma, mostra "verificando…".
  - **Toque no tipo de bateria** (ex.: "célula única ↻") para refazer a identificação. Abre um
    painel com a memória de cálculo ao vivo (as duas medidas, a razão e uma régua mostrando se ela
    está na faixa de 1× ou 2×) e um botão para reportar erro, que abre uma issue já preenchida
    no GitHub (com um comentário opcional).
  - Se errar, **toque e segure no número da corrente** para alternar ×1/×2 manualmente.
- O tempo até 100% usa a estimativa do Android (9+); se indisponível, calcula pela corrente média
  (marcado com `~`).

## Compilar

Precisa de JDK 17 e Android SDK.

```
./gradlew assembleRelease
```

O GitHub Actions compila a cada push; pushes no branch principal publicam uma release com
`carga-bateria.apk`.

## Licença

[MIT](LICENSE)

# Carga Bateria

App Android mínimo para ver a **corrente (mA)** e a **potência (W)** do carregamento em tempo real.
Também mostra tensão, temperatura, % da bateria e tempo estimado até 100%.

- Uma tela, sem anúncios, sem permissões, sem bibliotecas (só o SDK do Android).
- Atualiza a cada 1 segundo e mantém a tela ligada enquanto aberto.
- Android 5.0+.

## Instalar

Cada push gera um APK em **Releases** (`carga-bateria.apk`). Baixe no celular e instale
(permita "instalar apps desconhecidos" para o navegador/gerenciador de arquivos).

## Observações

- Os valores vêm de `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`, que é o que o kernel do
  aparelho reporta. A unidade (µA ou mA) e o sinal variam por fabricante; o app detecta e mostra
  sempre o valor absoluto, com a cor indicando carregando (verde) ou descarregando (laranja).
- Potência = tensão × corrente da bateria (o que entra na bateria, não o que sai do carregador).
- O tempo até 100% usa a estimativa do Android (9+); se indisponível, calcula pela corrente média
  (marcado com `~`).

## Build local

```
./gradlew assembleRelease
```

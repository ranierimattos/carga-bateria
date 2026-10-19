package com.ranieri.cargabateria;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Detecta aparelhos de célula dupla que reportam só metade da corrente.
 *
 * Não existe API para saber o número de células, então comparamos a corrente
 * reportada (integrada no tempo) com quanta carga realmente entrou/saiu da bateria,
 * medida pelo contador de carga ou pela variação de % × capacidade de projeto.
 * Se a razão for ~2, a corrente está pela metade. O resultado fica salvo.
 */
final class CurrentScale {

    private static final String PREFS = "scale";
    private static final String KEY_FACTOR = "factor";
    private static final String KEY_STATE = "state";
    private static final int STATE_GUESS = 0, STATE_AUTO = 1, STATE_MANUAL = 2;
    static final double SINGLE_MIN = 0.7, SINGLE_MAX = 1.35, DUAL_MIN = 1.6, DUAL_MAX = 2.6;

    private final SharedPreferences prefs;
    /** Capacidade de projeto em mAh (0 se desconhecida). */
    private final double designMah;

    private int factor;
    private int state;

    // Janela de calibração em andamento.
    private boolean windowOpen;
    private boolean windowPlugged;
    private boolean windowUsesCounter;
    private int counterScale;
    private double startTruthMah;
    private double integratedMah;
    private long lastSampleMs;

    // Valores da janela atual, para exibir a memória de cálculo.
    private double liveDeltaMah;
    private double liveMinDeltaMah;
    /** Razão da última medição concluída (NaN se nenhuma). */
    private double lastRatio = Double.NaN;

    CurrentScale(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        designMah = readDesignCapacity(context);
        factor = prefs.getInt(KEY_FACTOR, 1);
        state = prefs.getInt(KEY_STATE, STATE_GUESS);
    }

    int factor() {
        return factor;
    }

    /** true quando o fator já foi confirmado por medição ou escolhido pelo usuário. */
    boolean isConfirmed() {
        return state != STATE_GUESS;
    }

    boolean isManual() {
        return state == STATE_MANUAL;
    }

    /** Alterna manualmente entre ×1 e ×2 (desliga a detecção automática). */
    void toggleManual() {
        factor = factor == 1 ? 2 : 1;
        state = STATE_MANUAL;
        save();
    }

    /** Interrompe a janela atual (ex.: app pausado, já que não integramos em segundo plano). */
    void pause() {
        windowOpen = false;
    }

    /** Esquece o resultado salvo (inclusive o manual) e identifica de novo do zero. */
    void restart() {
        factor = 1;
        state = STATE_GUESS;
        windowOpen = false;
        lastRatio = Double.NaN;
        save();
    }

    // --- Memória de cálculo (só leitura, para a tela de detalhes e o relatório) ---

    double designMah() {
        return designMah;
    }

    boolean isMeasuring() {
        return windowOpen;
    }

    boolean usesCounter() {
        return windowUsesCounter;
    }

    int counterScale() {
        return counterScale;
    }

    /** Carga calculada pela corrente reportada (∫ I·dt) na janela atual, em mAh. */
    double integratedMah() {
        return windowOpen ? integratedMah : 0;
    }

    /** Carga que realmente entrou/saiu da bateria na janela atual, em mAh. */
    double deltaMah() {
        return windowOpen ? liveDeltaMah : 0;
    }

    /** Variação mínima de carga para concluir a medição, em mAh. */
    double minDeltaMah() {
        return liveMinDeltaMah;
    }

    /** Razão parcial da janela atual (NaN se ainda sem dados). */
    double liveRatio() {
        return windowOpen && integratedMah > 0.5 ? liveDeltaMah / integratedMah : Double.NaN;
    }

    double lastRatio() {
        return lastRatio;
    }

    /**
     * @param rawMa      corrente reportada em mA (valor absoluto, sem correção)
     * @param counterRaw BATTERY_PROPERTY_CHARGE_COUNTER (µAh/mAh) ou ≤0 se indisponível
     */
    void sample(double rawMa, int counterRaw, int pct, boolean plugged, long nowMs) {
        if (state == STATE_MANUAL || Double.isNaN(rawMa) || pct <= 0) return;

        boolean hasCounter = counterRaw > 0 && counterRaw != Integer.MIN_VALUE;
        double counterMah = hasCounter ? (counterRaw > 100_000 ? counterRaw / 1000.0 : counterRaw) : 0;

        if (!windowOpen || plugged != windowPlugged || nowMs - lastSampleMs > 5000) {
            openWindow(hasCounter, counterMah, pct, plugged, nowMs);
            return;
        }
        if (windowUsesCounter != hasCounter) {
            windowOpen = false;
            return;
        }

        integratedMah += rawMa * (nowMs - lastSampleMs) / 3_600_000.0;
        lastSampleMs = nowMs;

        double truthMah;
        double minDelta;
        if (windowUsesCounter) {
            truthMah = counterMah * counterScale;
            minDelta = 40;
        } else {
            truthMah = pct / 100.0 * designMah;
            minDelta = Math.max(40, designMah * 0.04); // ≥4% para diluir o arredondamento do %
        }

        double delta = Math.abs(truthMah - startTruthMah);
        liveDeltaMah = delta;
        liveMinDeltaMah = minDelta;
        if (delta < minDelta || integratedMah < 5) return;

        double ratio = delta / integratedMah;
        lastRatio = ratio;
        if (ratio > DUAL_MIN && ratio < DUAL_MAX) {
            factor = 2;
            state = STATE_AUTO;
            save();
        } else if (ratio > SINGLE_MIN && ratio < SINGLE_MAX) {
            factor = 1;
            state = STATE_AUTO;
            save();
        }
        // Fora das faixas: medição inconsistente, tenta de novo.
        windowOpen = false;
    }

    private void openWindow(boolean hasCounter, double counterMah, int pct, boolean plugged, long nowMs) {
        windowPlugged = plugged;
        lastSampleMs = nowMs;
        integratedMah = 0;
        liveDeltaMah = 0;
        liveMinDeltaMah = hasCounter || designMah <= 0 ? 40 : Math.max(40, designMah * 0.04);

        if (hasCounter) {
            // Se o contador indica ~metade da capacidade de projeto, ele também
            // está em escala de uma célula: corrigimos antes de comparar.
            counterScale = counterScaleFor(counterMah, pct);
            if (state == STATE_GUESS && counterScale == 2 && factor != 2) {
                factor = 2; // palpite inicial até a medição confirmar
            }
            windowUsesCounter = true;
            startTruthMah = counterMah * counterScale;
            windowOpen = true;
        } else if (designMah > 0) {
            windowUsesCounter = false;
            startTruthMah = pct / 100.0 * designMah;
            windowOpen = true;
        } else {
            windowOpen = false; // sem referência; fica no fator atual
        }
    }

    /**
     * Capacidade total estimada (mAh, já corrigida para célula dupla), usada para
     * estimar o tempo até 100%. 0 se não houver dado.
     */
    double fullCapacityMah(int counterRaw, int pct) {
        if (counterRaw > 0 && counterRaw != Integer.MIN_VALUE && pct > 0) {
            double counterMah = counterRaw > 100_000 ? counterRaw / 1000.0 : counterRaw;
            return counterMah * 100.0 / pct * counterScaleFor(counterMah, pct);
        }
        return designMah;
    }

    /**
     * Se o contador indica ~metade da capacidade de projeto, ele também está em
     * escala de uma célula e precisa ser multiplicado por 2.
     */
    private int counterScaleFor(double counterMah, int pct) {
        if (designMah > 0 && pct >= 20 && counterMah * 100.0 / pct < designMah * 0.65) return 2;
        return 1;
    }

    private void save() {
        prefs.edit().putInt(KEY_FACTOR, factor).putInt(KEY_STATE, state).apply();
    }

    /** Capacidade de projeto via PowerProfile (API interna, usada por vários apps de bateria). */
    private static double readDesignCapacity(Context context) {
        try {
            Class<?> k = Class.forName("com.android.internal.os.PowerProfile");
            Object profile = k.getConstructor(Context.class).newInstance(context);
            double mah = (Double) k.getMethod("getBatteryCapacity").invoke(profile);
            return mah > 500 && mah < 30_000 ? mah : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}

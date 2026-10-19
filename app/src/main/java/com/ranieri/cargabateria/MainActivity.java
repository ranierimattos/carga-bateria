package com.ranieri.cargabateria;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final long INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private BatteryManager bm;
    private TextView status, current, power, scaleHint, voltage, temp, level, eta;
    private CurrentScale scale;
    private View calcPanel;
    private RatioGauge calcGauge;
    private ProgressBar calcProgress;
    private TextView calcLive;
    private EditText calcComment;

    // Última leitura, para o relatório de erro.
    private int lastRaw = Integer.MIN_VALUE, lastCounter = Integer.MIN_VALUE, lastMv, lastPct = -1;

    // A doc do Android diz microamperes, mas alguns aparelhos reportam miliamperes.
    // Uma vez que vemos um valor que só faz sentido em µA, fixamos essa unidade.
    private boolean microAmps;
    // Média móvel da corrente (mA), usada só para estimar o tempo até 100%.
    private double avgMa = Double.NaN;
    private boolean wasPlugged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        status = findViewById(R.id.status);
        current = findViewById(R.id.current);
        power = findViewById(R.id.power);
        scaleHint = findViewById(R.id.scale_hint);
        voltage = findViewById(R.id.voltage);
        temp = findViewById(R.id.temp);
        level = findViewById(R.id.level);
        eta = findViewById(R.id.eta);

        scale = new CurrentScale(this);
        // Ajuste manual caso a detecção automática erre.
        current.setOnLongClickListener(v -> {
            scale.toggleManual();
            Toast.makeText(this, "Corrente ×" + scale.factor() + " (manual)", Toast.LENGTH_SHORT).show();
            update();
            return true;
        });

        calcPanel = findViewById(R.id.calc_panel);
        calcGauge = findViewById(R.id.calc_gauge);
        calcProgress = findViewById(R.id.calc_progress);
        calcLive = findViewById(R.id.calc_live);
        calcComment = findViewById(R.id.calc_comment);

        // Tocar no tipo de bateria refaz a identificação e mostra a memória de cálculo.
        scaleHint.setOnClickListener(v -> {
            scale.restart();
            calcPanel.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Identificação reiniciada", Toast.LENGTH_SHORT).show();
            update();
        });
        findViewById(R.id.calc_close).setOnClickListener(v -> {
            calcPanel.setVisibility(View.GONE);
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(v.getWindowToken(), 0);
        });
        findViewById(R.id.calc_report).setOnClickListener(v -> report());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        scale.pause();
    }

    private void update() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;

        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean isPlugged = plugged != 0;
        boolean isFull = st == BatteryManager.BATTERY_STATUS_FULL;

        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        if (mv > 0 && mv < 100) mv *= 1000; // alguns aparelhos reportam em V
        // Células em série reportam a tensão do conjunto (~8 V): detectável direto.
        int cellsInSeries = mv > 6000 ? Math.round(mv / 4000f) : 1;

        int tempTenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int levelScale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = (lvl >= 0 && levelScale > 0) ? Math.round(lvl * 100f / levelScale) : -1;

        int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        boolean hasCurrent = raw != Integer.MIN_VALUE && raw != 0;
        int counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        lastRaw = raw;
        lastCounter = counter;
        lastMv = mv;
        lastPct = pct;
        double ma = Double.NaN;
        if (hasCurrent) {
            if (Math.abs(raw) >= 15000) microAmps = true;
            // Sinal varia por fabricante; o sentido real vem do cabo conectado ou não.
            double rawMa = Math.abs(microAmps ? raw / 1000.0 : raw);
            scale.sample(rawMa, counter, pct, isPlugged, SystemClock.elapsedRealtime());
            ma = rawMa * scale.factor();
        }

        if (isPlugged != wasPlugged) avgMa = Double.NaN;
        wasPlugged = isPlugged;
        if (!Double.isNaN(ma)) avgMa = Double.isNaN(avgMa) ? ma : avgMa * 0.9 + ma * 0.1;

        int color = getResources().getColor(isPlugged ? R.color.charging : R.color.discharging);
        current.setTextColor(color);

        status.setText(statusText(isPlugged, isFull, plugged));
        if (Double.isNaN(ma)) {
            current.setText("— mA");
            power.setText("— W");
        } else {
            current.setText(String.format(Locale.getDefault(), "%,.0f mA", ma));
            power.setText(mv > 0
                    ? String.format(Locale.getDefault(), "%.1f W", ma * mv / 1_000_000.0)
                    : "— W");
        }

        if (mv <= 0) {
            voltage.setText("—");
        } else if (cellsInSeries > 1) {
            voltage.setText(String.format(Locale.getDefault(), "%.2f V (%d × %.2f V)",
                    mv / 1000.0, cellsInSeries, mv / 1000.0 / cellsInSeries));
        } else {
            voltage.setText(String.format(Locale.getDefault(), "%.2f V", mv / 1000.0));
        }
        scaleHint.setText(scaleHintText());
        temp.setText(tempTenths != Integer.MIN_VALUE
                ? String.format(Locale.getDefault(), "%.1f °C", tempTenths / 10.0) : "—");
        level.setText(pct >= 0 ? pct + "%" : "—");
        eta.setText(etaText(isPlugged, isFull, pct, counter));
        if (calcPanel.getVisibility() == View.VISIBLE) updateCalcPanel();
    }

    private String scaleHintText() {
        return resultText() + "  ↻";
    }

    private String resultText() {
        String cell = scale.factor() == 2 ? "célula dupla (corrente ×2)" : "célula única";
        if (scale.isManual()) return cell + " · manual";
        return scale.isConfirmed() ? cell : "verificando tipo de bateria…";
    }

    private void updateCalcPanel() {
        double live = scale.liveRatio();
        calcGauge.setRatio(Double.isNaN(live) ? scale.lastRatio() : live);
        double min = scale.minDeltaMah();
        calcProgress.setProgress(min > 0 ? (int) Math.min(100, scale.deltaMah() * 100 / min) : 0);
        calcLive.setText(calcText());
    }

    /** Memória de cálculo em texto (usada na tela e no relatório). */
    private String calcText() {
        Locale l = Locale.getDefault();
        StringBuilder sb = new StringBuilder();
        if (scale.isManual()) {
            sb.append("Modo manual: medição automática desligada.\n");
        } else if (!scale.isMeasuring()) {
            sb.append(scale.designMah() > 0 || lastCounter > 0
                    ? "Aguardando o início da medição…\n"
                    : "Sem contador de carga nem capacidade conhecida: não dá para medir.\n");
        } else {
            String src = scale.usesCounter()
                    ? "contador de carga" + (scale.counterScale() == 2 ? " (×2, escala de 1 célula)" : "")
                    : String.format(l, "%% × capacidade (%.0f mAh)", scale.designMah());
            sb.append("B vem de: ").append(src).append('\n');
            sb.append(String.format(l, "A (corrente × tempo): %6.1f mAh%n", scale.integratedMah()));
            sb.append(String.format(l, "B (carga real):       %6.1f mAh%n", scale.deltaMah()));
            sb.append(String.format(l, "Conclui com B ≥ %.0f mAh%n", scale.minDeltaMah()));
            double r = scale.liveRatio();
            sb.append("Razão B ÷ A agora:    ")
                    .append(Double.isNaN(r) ? "—" : String.format(l, "%.2f", r)).append('\n');
        }
        if (!Double.isNaN(scale.lastRatio())) {
            sb.append(String.format(l, "Última razão medida:  %.2f%n", scale.lastRatio()));
        }
        sb.append("Resultado: ").append(resultText());
        return sb.toString();
    }

    /** Abre uma issue no GitHub já preenchida com os dados do cálculo. */
    private void report() {
        String comment = calcComment.getText().toString().trim();
        Locale l = Locale.ROOT;
        String body = "**Comentário:** " + (comment.isEmpty() ? "(nenhum)" : comment) + "\n\n"
                + "**Aparelho:** " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")\n"
                + "**App:** " + getString(R.string.app_version) + "\n\n"
                + "```\n" + calcText() + "\n\n"
                + "corrente bruta: " + lastRaw + "\n"
                + "contador bruto: " + lastCounter + "\n"
                + "tensão: " + lastMv + " mV\n"
                + "nível: " + lastPct + "%\n"
                + String.format(l, "capacidade de projeto: %.0f mAh%n", scale.designMah())
                + "```\n";
        Uri uri = Uri.parse("https://github.com/ranierimattos/carga-bateria/issues/new").buildUpon()
                .appendQueryParameter("title", "Tipo de bateria incorreto: " + Build.MANUFACTURER + " " + Build.MODEL)
                .appendQueryParameter("body", body)
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Nenhum navegador encontrado", Toast.LENGTH_SHORT).show();
        }
    }

    private String statusText(boolean isPlugged, boolean isFull, int plugged) {
        if (!isPlugged) return "Descarregando";
        String src;
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: src = "tomada"; break;
            case BatteryManager.BATTERY_PLUGGED_USB: src = "USB"; break;
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: src = "sem fio"; break;
            default: src = null;
        }
        String s = isFull ? "Carregado" : "Carregando";
        return src != null ? s + " · " + src : s;
    }

    private String etaText(boolean isPlugged, boolean isFull, int pct, int counter) {
        if (isFull || pct >= 100) return isPlugged ? "Cheia" : "—";
        if (!isPlugged) return "—";

        // Estimativa do próprio sistema (considera a desaceleração perto de 100%).
        if (Build.VERSION.SDK_INT >= 28) {
            long ms = bm.computeChargeTimeRemaining();
            if (ms > 0) return formatDuration(ms / 60000);
        }

        // Fallback: carga restante / corrente média.
        double fullMah = scale.fullCapacityMah(counter, pct);
        if (fullMah <= 0 || pct <= 0 || Double.isNaN(avgMa) || avgMa < 1) return "—";
        double minutes = fullMah * (100 - pct) / 100.0 / avgMa * 60.0;
        if (minutes <= 0 || minutes > 48 * 60) return "—";
        return "~" + formatDuration(Math.round(minutes));
    }

    private static String formatDuration(long minutes) {
        long h = minutes / 60, m = minutes % 60;
        return h > 0 ? h + "h " + m + "min" : m + "min";
    }
}

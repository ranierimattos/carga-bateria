package com.ranieri.cargabateria;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
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
        scale.reset();
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
    }

    private String scaleHintText() {
        String cell = scale.factor() == 2 ? "célula dupla (corrente ×2)" : "célula única";
        if (scale.isManual()) return cell + " · manual";
        return scale.isConfirmed() ? cell : "verificando tipo de bateria…";
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

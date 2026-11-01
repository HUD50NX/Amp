package br.hud.amp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView botao, estado, info, volTxt;
    private ProgressBar nivel;
    private SeekBar vol;
    private String aviso = "";
    private final Handler h = new Handler(Looper.getMainLooper());

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER);
        raiz.setBackgroundColor(Color.parseColor("#0F1013"));
        raiz.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView titulo = new TextView(this);
        titulo.setText("Amp");
        titulo.setTextColor(Color.WHITE);
        titulo.setTextSize(28);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        titulo.setGravity(Gravity.CENTER);
        raiz.addView(titulo);

        botao = new TextView(this);
        botao.setGravity(Gravity.CENTER);
        botao.setTextColor(Color.WHITE);
        botao.setTextSize(22);
        botao.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(190), dp(190));
        bp.topMargin = dp(32);
        bp.bottomMargin = dp(24);
        raiz.addView(botao, bp);
        botao.setOnClickListener(v -> alternar());

        estado = new TextView(this);
        estado.setTextColor(Color.WHITE);
        estado.setTextSize(16);
        estado.setGravity(Gravity.CENTER);
        raiz.addView(estado);

        info = new TextView(this);
        info.setTextColor(Color.parseColor("#9AA0AA"));
        info.setTextSize(13);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2);
        ip.topMargin = dp(6);
        raiz.addView(info, ip);

        nivel = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        nivel.setMax(100);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, dp(10));
        np.topMargin = dp(24);
        raiz.addView(nivel, np);

        volTxt = new TextView(this);
        volTxt.setTextColor(Color.parseColor("#9AA0AA"));
        volTxt.setTextSize(13);
        LinearLayout.LayoutParams vtp = new LinearLayout.LayoutParams(-2, -2);
        vtp.topMargin = dp(20);
        raiz.addView(volTxt, vtp);

        vol = new SeekBar(this);
        vol.setMax(400);
        vol.setProgress((int) (AmpService.ganho * 100));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                AmpService.ganho = p / 100f;
                volTxt.setText("Volume " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        raiz.addView(vol, new LinearLayout.LayoutParams(-1, -2));
        volTxt.setText("Volume " + (int) (AmpService.ganho * 100) + "%");

        setContentView(raiz);
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(tique);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tique);
    }

    private final Runnable tique = new Runnable() {
        @Override public void run() {
            atualizar();
            h.postDelayed(this, 120);
        }
    };

    private void atualizar() {
        boolean on = AmpService.rodando;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(on ? "#E5484D" : "#2EB67D"));
        botao.setBackground(g);
        botao.setText(on ? "DESLIGAR" : "LIGAR");
        if (on) {
            estado.setText(AmpService.status);
            String l = AmpService.latenciaMs > 0 ? "  ·  atraso ~" + AmpService.latenciaMs + " ms" : "";
            info.setText(AmpService.entrada.isEmpty() ? "" : "Entrada: " + AmpService.entrada + l);
        } else {
            String s = aviso.isEmpty() ? AmpService.status : aviso;
            estado.setText(s);
            info.setText("");
        }
        nivel.setProgress(on ? AmpService.pico : 0);
    }

    private void alternar() {
        if (AmpService.rodando) {
            startService(new Intent(this, AmpService.class).setAction(AmpService.ACAO_PARAR));
            return;
        }
        aviso = "";
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (AmpService.acharEntrada(am) == null) {
            aviso = "Nenhuma entrada externa. Ligue o Cube Baby no USB-C.";
            AmpService.status = "Desligado";
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        ligar();
    }

    private void ligar() {
        AmpService.status = "Ligando…";
        startForegroundService(new Intent(this, AmpService.class));
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ligar();
        } else if (code == 7) {
            aviso = "Preciso da permissão do microfone pra ler a entrada.";
        }
    }
}

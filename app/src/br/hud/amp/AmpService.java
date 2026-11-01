package br.hud.amp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Pega o som da entrada externa (USB-C) e toca no alto-falante do celular, ao vivo. */
public class AmpService extends Service {
    static final String ACAO_PARAR = "br.hud.amp.PARAR";

    // Estado lido pela tela
    static volatile boolean rodando = false;
    static volatile String status = "Desligado";
    static volatile String entrada = "";
    static volatile int pico = 0;        // 0..100
    static volatile float ganho = 1f;    // volume
    static volatile int latenciaMs = 0;

    private Thread th;
    private volatile boolean parar;
    private AudioManager am;
    private AudioDeviceCallback cb;
    private AudioDeviceInfo entradaDev;
    private PowerManager.WakeLock wl;

    /** Entrada externa: USB (placa de som / headset) ou fone com mic de P2. Nunca o mic do celular. */
    static AudioDeviceInfo acharEntrada(AudioManager am) {
        AudioDeviceInfo fio = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) return d;
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET) fio = d;
        }
        return fio;
    }

    static AudioDeviceInfo acharAltoFalante(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    static String nome(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        String s = n == null ? "" : n.toString().trim();
        String tipo = d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ? "P2" : "USB";
        return s.isEmpty() ? tipo : tipo + " · " + s;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACAO_PARAR.equals(intent.getAction())) {
            parar = true;
            if (th == null) { encerrar(); }
            return START_NOT_STICKY;
        }
        if (th != null) return START_NOT_STICKY;
        subirNotificacao();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        parar = false;
        rodando = true;
        status = "Ligando…";
        th = new Thread(this::laco, "amp-audio");
        th.start();
        return START_NOT_STICKY;
    }

    private void subirNotificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("amp", "Amp", NotificationManager.IMPORTANCE_LOW));
        Intent abrir = new Intent(this, MainActivity.class);
        PendingIntent pa = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE);
        Intent pararI = new Intent(this, AmpService.class).setAction(ACAO_PARAR);
        PendingIntent pp = PendingIntent.getService(this, 1, pararI, PendingIntent.FLAG_IMMUTABLE);
        Notification.Action acao = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_launcher), "Desligar", pp).build();
        Notification n = new Notification.Builder(this, "amp")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Amp ligado")
                .setContentText("Entrada externa → alto-falante")
                .setContentIntent(pa)
                .addAction(acao)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    private void laco() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack trk = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amp:audio");
            wl.acquire();

            entradaDev = acharEntrada(am);
            AudioDeviceInfo alto = acharAltoFalante(am);
            if (entradaDev == null) { status = "Nenhuma entrada externa. Ligue o aparelho no USB-C."; return; }
            if (alto == null) { status = "Não achei o alto-falante do celular."; return; }
            entrada = nome(entradaDev);

            // Se tirar o cabo, desliga na hora (senão o celular trocaria pro mic dele e daria microfonia).
            cb = new AudioDeviceCallback() {
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removidos) {
                    for (AudioDeviceInfo d : removidos) {
                        if (entradaDev != null && d.getId() == entradaDev.getId()) {
                            status = "Entrada desconectada.";
                            parar = true;
                        }
                    }
                }
            };
            am.registerAudioDeviceCallback(cb, new Handler(Looper.getMainLooper()));

            int sr = 48000, fpb = 192;
            try { sr = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)); } catch (Exception e) { }
            try { fpb = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)); } catch (Exception e) { }
            if (fpb < 64) fpb = 192;

            boolean semProc = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            int fonte = semProc ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC;
            int minIn = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioSource(fonte)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minIn, fpb * 2 * 2))
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { status = "Não consegui abrir a entrada."; return; }
            rec.setPreferredDevice(entradaDev);

            int minOut = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minOut, fpb * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            trk.setPreferredDevice(alto);
            int capacidade = trk.getBufferCapacityInFrames();
            int bufFrames = Math.min(capacidade, fpb * 2);
            trk.setBufferSizeInFrames(bufFrames);
            int falhasAntes = 0;
            long ultimaCheck = System.currentTimeMillis();

            short[] in = new short[fpb];
            short[] out = new short[fpb * 2];
            rec.startRecording();
            trk.play();
            boolean conferiu = false;
            status = "Ligado";

            while (!parar) {
                int n = rec.read(in, 0, in.length);
                if (n <= 0) { status = "A entrada parou de mandar som."; break; }
                if (!conferiu) {
                    AudioDeviceInfo r = rec.getRoutedDevice();
                    if (r != null && r.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        status = "O celular quis usar o mic dele, e não a entrada externa. Desliguei.";
                        break;
                    }
                    if (r != null) conferiu = true;
                }
                float g = ganho;
                int p = 0;
                for (int i = 0; i < n; i++) {
                    int s = in[i];
                    int a = s < 0 ? -s : s;
                    if (a > p) p = a;
                    int v = (int) (s * g);
                    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                    out[2 * i] = (short) v;
                    out[2 * i + 1] = (short) v;
                }
                int nivel = p * 100 / 32768;
                pico = nivel > pico ? nivel : pico * 85 / 100;
                trk.write(out, 0, n * 2);

                long agora = System.currentTimeMillis();
                if (agora - ultimaCheck > 500) {
                    ultimaCheck = agora;
                    int falhas = trk.getUnderrunCount();
                    if (falhas > falhasAntes && bufFrames < capacidade) {
                        bufFrames = Math.min(capacidade, bufFrames + fpb);
                        trk.setBufferSizeInFrames(bufFrames);
                    }
                    falhasAntes = falhas;
                    latenciaMs = (bufFrames + fpb * 2) * 1000 / sr;
                }
            }
        } catch (Exception e) {
            status = "Erro: " + e.getMessage();
        } finally {
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception e) { }
            try { if (trk != null) { trk.stop(); trk.release(); } } catch (Exception e) { }
            try { if (cb != null) am.unregisterAudioDeviceCallback(cb); } catch (Exception e) { }
            try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
            if (!"Ligado".equals(status) && !status.startsWith("Ligando")) {
                // mantém a mensagem de erro
            } else {
                status = "Desligado";
            }
            pico = 0;
            rodando = false;
            new Handler(Looper.getMainLooper()).post(this::encerrar);
        }
    }

    private void encerrar() {
        th = null;
        rodando = false;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        parar = true;
        super.onDestroy();
    }
}

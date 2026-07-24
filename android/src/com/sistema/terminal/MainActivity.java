package com.sistema.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;

import com.xcheng.printerservice.IPrinterCallback;
import com.xcheng.printerservice.IPrinterService;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private WebView webView;
    private PrinterBridge printerBridge;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        printerBridge = new PrinterBridge(this, webView);
        webView.addJavascriptInterface(printerBridge, "NativePrinter");
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // Tenta conectar ao serviço AIDL da impressora logo ao abrir
        printerBridge.bindService();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    }).setCancelable(false).show();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("Sim", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setNegativeButton("Não", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.cancel(); }
                    }).setCancelable(false).show();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                return !url.startsWith("file://") && !url.startsWith("about:");
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        printerBridge.unbindService();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onPause()  { super.onPause();  if (webView != null) webView.onPause(); }
    @Override protected void onResume() { super.onResume(); if (webView != null) webView.onResume(); }

    // ═══════════════════════════════════════════════════════════════════════
    static class PrinterBridge {
        private final Activity act;
        private final WebView  wv;

        // Serviço AIDL XCheng (Positivo L500)
        private volatile IPrinterService xchengService = null;
        private volatile boolean         xchengBound   = false;

        // Portas seriais — fallback
        private static final String[] SERIAL_PORTS = {
            "/dev/ttyS1", "/dev/ttyS0", "/dev/ttyS2"
        };

        // CloudPOS — fallback
        private static final Object[][] CLOUDPOS_VARIANTS = {
            {"com.cloudpos.POSTerminal", 8},
            {"com.cloudpos.POSTerminal", 1},
            {"com.cloudpos.jni.POSTerminal", 8},
        };

        PrinterBridge(Activity a, WebView w) { this.act = a; this.wv = w; }

        // ── Bind XCheng AIDL ─────────────────────────────────────────────
        void bindService() {
            String[] pkgs = {"com.xcheng.printerservice"};
            String[] actions = {
                "com.xcheng.printer.PRINT_SERVICE",
                "com.xcheng.printerservice.IPrinterService"
            };
            for (String pkg : pkgs) {
                for (String action : actions) {
                    try {
                        Intent i = new Intent(action);
                        i.setPackage(pkg);
                        boolean ok = act.bindService(i, xchengConn, Context.BIND_AUTO_CREATE);
                        if (ok) return;
                    } catch (Exception ignored) {}
                }
            }
        }

        void unbindService() {
            if (xchengBound) {
                try { act.unbindService(xchengConn); } catch (Exception ignored) {}
                xchengBound = false;
                xchengService = null;
            }
        }

        private final ServiceConnection xchengConn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                xchengService = IPrinterService.Stub.asInterface(binder);
                xchengBound   = true;
                try { xchengService.printerInit(noopCallback("init")); } catch (Exception ignored) {}
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                xchengService = null;
                xchengBound   = false;
            }
        };

        // ── Diagnóstico ──────────────────────────────────────────────────
        @JavascriptInterface
        public String diagnose() {
            StringBuilder sb = new StringBuilder("{");

            // XCheng AIDL
            sb.append("\"xcheng\":{\"bound\":").append(xchengBound)
              .append(",\"service\":").append(xchengService != null).append("},");

            // Serial
            sb.append("\"serial\":{");
            for (String port : SERIAL_PORTS) {
                File f = new File(port);
                if (f.exists()) {
                    sb.append("\"").append(port).append("\":{\"exists\":true,\"canWrite\":")
                      .append(f.canWrite()).append("},");
                }
            }
            if (sb.charAt(sb.length()-1) == ',') sb.setLength(sb.length()-1);
            sb.append("},");

            // Device
            sb.append("\"device\":{\"model\":\"").append(esc(android.os.Build.MODEL))
              .append("\",\"brand\":\"").append(esc(android.os.Build.BRAND))
              .append("\",\"sdk\":").append(android.os.Build.VERSION.SDK_INT)
              .append("}}");
            return sb.toString();
        }

        @JavascriptInterface
        public boolean isAvailable() { return true; }

        // ── printText: AIDL → ESC/POS serial → CloudPOS → Intents ───────
        @JavascriptInterface
        public String printText(String text) {
            StringBuilder log = new StringBuilder();

            // 1. XCheng AIDL (serviço nativo do Positivo L500)
            if (xchengService != null) {
                String r = tryXchengPrint(text, log);
                if (r != null) return r;
            } else {
                log.append("[xcheng:nao_vinculado]");
                // Tenta reconectar
                bindService();
                // Aguarda 1s para o bind
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                if (xchengService != null) {
                    String r = tryXchengPrint(text, log);
                    if (r != null) return r;
                }
            }

            // 2. ESC/POS serial direto (com chmod)
            String r2 = tryEscPosSerial(text, log);
            if (r2 != null) return r2;

            // 3. CloudPOS
            for (Object[] v : CLOUDPOS_VARIANTS) {
                String r = tryCloudPos((String)v[0], (Integer)v[1], text, log);
                if (r != null) return r;
            }

            // 4. Intents OEM
            tryOemIntents(text);
            log.append("[intents_sent]");

            return "{\"ok\":false,\"error\":\"" + esc(log.toString()) + "\"}";
        }

        // ── XCheng AIDL — tentativas ──────────────────────────────────────
        private String tryXchengPrint(String text, StringBuilder log) {
            // a) printText nativo
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] ok = {true};
                final String[]  err = {null};

                xchengService.printText(text, new IPrinterCallback.Stub() {
                    @Override public void onException(int code, String msg) {
                        ok[0] = false; err[0] = code + ":" + msg; latch.countDown();
                    }
                    @Override public void onLength(long c, long t) {}
                    @Override public void onRealLength(double c, double t) {}
                    @Override public void onComplete() { latch.countDown(); }
                });

                // Feed + corte via sendRAWData
                xchengService.printWrapPaper(4, noopCallback("feed"));

                latch.await(5, TimeUnit.SECONDS);

                if (ok[0]) {
                    return "{\"ok\":true,\"driver\":\"xcheng-aidl-text\"}";
                } else {
                    log.append("[xcheng-text:").append(esc(err[0])).append("]");
                }
            } catch (Exception e) {
                log.append("[xcheng-text:").append(esc(e.getMessage())).append("]");
            }

            // b) sendRAWData com ESC/POS bytes (mais compatível)
            try {
                byte[] escPosData = buildEscPosBytes(text);
                final CountDownLatch latch2 = new CountDownLatch(1);
                final boolean[] ok2 = {true};

                xchengService.sendRAWData(escPosData, new IPrinterCallback.Stub() {
                    @Override public void onException(int code, String msg) {
                        ok2[0] = false; latch2.countDown();
                    }
                    @Override public void onLength(long c, long t) {}
                    @Override public void onRealLength(double c, double t) {}
                    @Override public void onComplete() { latch2.countDown(); }
                });

                latch2.await(5, TimeUnit.SECONDS);

                if (ok2[0]) {
                    return "{\"ok\":true,\"driver\":\"xcheng-aidl-raw\"}";
                } else {
                    log.append("[xcheng-raw:falhou]");
                }
            } catch (Exception e) {
                log.append("[xcheng-raw:").append(esc(e.getMessage())).append("]");
            }

            return null;
        }

        // ── ESC/POS serial (fallback) ─────────────────────────────────────
        private String tryEscPosSerial(String text, StringBuilder log) {
            for (String port : SERIAL_PORTS) {
                File f = new File(port);
                if (!f.exists()) continue;
                try { Runtime.getRuntime().exec(new String[]{"chmod","666",port}).waitFor(1500,TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                try { Runtime.getRuntime().exec(new String[]{"su","-c","chmod 666 "+port}).waitFor(2000,TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                try {
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(buildEscPosBytes(text));
                    fos.flush(); fos.close();
                    return "{\"ok\":true,\"driver\":\"escpos-serial\",\"port\":\"" + esc(port) + "\"}";
                } catch (Exception e) {
                    log.append("[").append(port).append(":").append(esc(e.getMessage())).append("]");
                }
            }
            return null;
        }

        // ── ESC/POS bytes ─────────────────────────────────────────────────
        private byte[] buildEscPosBytes(String text) throws Exception {
            byte[] init  = {0x1B, 0x40};           // ESC @ init
            byte[] enc   = {0x1B, 0x74, 0x10};     // ESC t 16 ISO-8859-1
            byte[] textB = text.getBytes("ISO-8859-1");
            byte[] feed  = {0x0A,0x0A,0x0A,0x0A,0x0A};
            byte[] cut   = {0x1D, 0x56, 0x41, 0x00};

            int len = init.length + enc.length + textB.length + feed.length + cut.length;
            byte[] out = new byte[len];
            int p = 0;
            System.arraycopy(init,  0, out, p, init.length);  p += init.length;
            System.arraycopy(enc,   0, out, p, enc.length);   p += enc.length;
            System.arraycopy(textB, 0, out, p, textB.length); p += textB.length;
            System.arraycopy(feed,  0, out, p, feed.length);  p += feed.length;
            System.arraycopy(cut,   0, out, p, cut.length);
            return out;
        }

        // ── CloudPOS ─────────────────────────────────────────────────────
        private String tryCloudPos(String cls, int devType, String text, StringBuilder log) {
            try {
                Class<?> tc = Class.forName(cls);
                Object terminal;
                try { terminal = tc.getMethod("getInstance", Context.class).invoke(null, act); }
                catch (NoSuchMethodException e) { terminal = tc.getMethod("getInstance").invoke(null); }
                Class<?> pc;
                try { pc = Class.forName("com.cloudpos.device.printer.PrinterDevice"); }
                catch (ClassNotFoundException e) { pc = Class.forName("com.cloudpos.device.PrinterDevice"); }
                Object printer = tc.getMethod("getDevice", int.class).invoke(terminal, devType);
                if (printer == null) return null;
                try { pc.getMethod("open", int.class, android.os.Bundle.class).invoke(printer, 0, null); }
                catch (NoSuchMethodException e) { pc.getMethod("open").invoke(printer); }
                android.text.SpannableString ss = new android.text.SpannableString(text + "\n\n\n\n\n");
                try { pc.getMethod("print", android.text.SpannableString.class).invoke(printer, ss); }
                catch (Exception e) { pc.getMethod("printText", String.class).invoke(printer, text); }
                try { pc.getMethod("waitUntilFinish", int.class).invoke(printer, 8000); }
                catch (Exception ignored) { Thread.sleep(3000); }
                try { pc.getMethod("close").invoke(printer); } catch (Exception ignored) {}
                return "{\"ok\":true,\"driver\":\"cloudpos\"}";
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                log.append("[cp:").append(esc(e.getClass().getSimpleName())).append("]");
            }
            return null;
        }

        // ── Intents OEM ───────────────────────────────────────────────────
        private void tryOemIntents(String text) {
            String[] actions = {
                "com.positivo.printer.action.PRINT",
                "com.pozitron.printer.PRINT",
                "com.xcheng.printer.PRINT",
            };
            for (String action : actions) {
                try {
                    Intent i = new Intent(action);
                    i.putExtra("text", text);
                    act.sendBroadcast(i);
                } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public void printScreen() {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    cbJS("{\"ok\":false,\"error\":\"Use ESC/POS ou AIDL\"}");
                }
            });
        }

        private IPrinterCallback noopCallback(final String label) {
            return new IPrinterCallback.Stub() {
                @Override public void onException(int code, String msg) {}
                @Override public void onLength(long c, long t) {}
                @Override public void onRealLength(double c, double t) {}
                @Override public void onComplete() {}
            };
        }

        private void cbJS(final String json) {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    wv.evaluateJavascript(
                        "if(typeof window._printCallback==='function')window._printCallback(" + json + ");", null);
                }
            });
        }

        private String esc(String s) {
            if (s == null) return "null";
            return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
        }
    }
}

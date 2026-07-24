package com.sistema.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        webView.addJavascriptInterface(new PrinterBridge(this, webView), "NativePrinter");
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

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

        // CloudPOS — Positivo L500 não tem, mas tentamos
        private static final Object[][] CLOUDPOS_VARIANTS = {
            {"com.cloudpos.POSTerminal",      8},
            {"com.cloudpos.POSTerminal",      1},
            {"com.cloudpos.jni.POSTerminal",  8},
            {"com.cloudpos.jni.POSTerminal",  1},
            {"xgd.cloudpos.POSTerminal",      8},
        };

        // Portas seriais — L500: ttyS1 e ttyS0 confirmados pelo diagnóstico
        private static final String[] SERIAL_PORTS = {
            "/dev/ttyS1",   // Positivo L500 ✓ (confirmado)
            "/dev/ttyS0",   // Positivo L500 ✓ (confirmado)
            "/dev/ttyS2",
            "/dev/ttyS3",
        };

        PrinterBridge(Activity a, WebView w) { this.act = a; this.wv = w; }

        @JavascriptInterface
        public boolean isAvailable() { return true; }

        // ── Diagnóstico ──────────────────────────────────────────────────
        @JavascriptInterface
        public String diagnose() {
            StringBuilder sb = new StringBuilder("{\"serial\":{");
            for (String port : SERIAL_PORTS) {
                File f = new File(port);
                if (!f.exists()) continue;
                sb.append("\"").append(port).append("\":{")
                  .append("\"exists\":true,\"canWrite\":").append(f.canWrite()).append("},");
            }
            if (sb.charAt(sb.length()-1) == ',') sb.setLength(sb.length()-1);
            sb.append("},\"cloudpos\":{");
            for (Object[] v : CLOUDPOS_VARIANTS) {
                String cls = (String) v[0];
                boolean found = false;
                try { Class.forName(cls); found = true; } catch (Exception e) {}
                if (found) sb.append("\"").append(cls).append("\":true,");
            }
            if (sb.charAt(sb.length()-1) == ',') sb.setLength(sb.length()-1);
            sb.append("},\"device\":{");
            sb.append("\"model\":\"").append(esc(android.os.Build.MODEL)).append("\",");
            sb.append("\"brand\":\"").append(esc(android.os.Build.BRAND)).append("\",");
            sb.append("\"hardware\":\"").append(esc(android.os.Build.HARDWARE)).append("\",");
            sb.append("\"sdk\":").append(android.os.Build.VERSION.SDK_INT);
            sb.append("}}");
            return sb.toString();
        }

        // ── printText ────────────────────────────────────────────────────
        @JavascriptInterface
        public String printText(String text) {
            StringBuilder log = new StringBuilder();

            // 1. CloudPOS
            for (Object[] v : CLOUDPOS_VARIANTS) {
                String r = tryCloudPos((String)v[0], (Integer)v[1], text, log);
                if (r != null) return r;
            }

            // 2. ESC/POS serial (com chmod automático)
            String r2 = tryEscPosSerial(text, log);
            if (r2 != null) return r2;

            // 3. Intents OEM
            tryOemIntents(text);
            log.append("[intents_sent]");

            return "{\"ok\":false,\"error\":\"" + esc(log.toString()) + "\"}";
        }

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
                if (printer == null) { log.append("[").append(cls).append(":null]"); return null; }
                try { pc.getMethod("open", int.class, Bundle.class).invoke(printer, 0, null); }
                catch (NoSuchMethodException e) {
                    try { pc.getMethod("open", int.class).invoke(printer, 0); }
                    catch (NoSuchMethodException e2) { pc.getMethod("open").invoke(printer); }
                }
                try { pc.getMethod("printText", String.class).invoke(printer, text); }
                catch (NoSuchMethodException e) {
                    SpannableString ss = new SpannableString(text + "\n\n\n\n\n");
                    pc.getMethod("print", SpannableString.class).invoke(printer, ss);
                }
                try { pc.getMethod("waitUntilFinish", int.class).invoke(printer, 8000); }
                catch (Exception ignored) { Thread.sleep(3000); }
                try { pc.getMethod("close").invoke(printer); } catch (Exception ignored) {}
                return "{\"ok\":true,\"driver\":\"cloudpos\",\"class\":\"" + esc(cls) + "\"}";
            } catch (ClassNotFoundException e) {
                // silencioso — classe não existe no device
            } catch (Exception e) {
                log.append("[cp:").append(esc(e.getClass().getSimpleName())).append("]");
            }
            return null;
        }

        private String tryEscPosSerial(String text, StringBuilder log) {
            for (String port : SERIAL_PORTS) {
                File f = new File(port);
                if (!f.exists()) continue;
                // chmod sem root
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"chmod", "666", port});
                    p.waitFor(1500, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
                // chmod com su
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 " + port});
                    p.waitFor(2000, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
                try {
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(new byte[]{0x1B, 0x40});            // ESC @ init
                    fos.write(new byte[]{0x1B, 0x74, 0x10});      // ESC t 16 ISO-8859-1
                    fos.write(text.getBytes("ISO-8859-1"));
                    fos.write(new byte[]{0x0A,0x0A,0x0A,0x0A,0x0A}); // 5 line feeds
                    fos.write(new byte[]{0x1D, 0x56, 0x41, 0x00});    // GS V A — corte parcial
                    fos.flush(); fos.close();
                    return "{\"ok\":true,\"driver\":\"escpos\",\"port\":\"" + esc(port) + "\"}";
                } catch (Exception e) {
                    log.append("[").append(port).append(":").append(esc(e.getMessage())).append("]");
                }
            }
            return null;
        }

        private void tryOemIntents(String text) {
            String[] actions = {
                "com.positivo.printer.action.PRINT",
                "com.pozitron.printer.PRINT",
                "com.cloudpos.printer.PRINT",
                "com.device.printer.PRINT",
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
                    cbJS("{\"ok\":false,\"error\":\"Bitmap: sem SDK no L500\"}");
                }
            });
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

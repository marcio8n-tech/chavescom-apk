package com.chavescom.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private LocalServer localServer;
    private static final int PORT = 8765;
    private static final String TAG = "ChavesCom";
    private final Handler mainHandler = new Handler();

    // Interface exposta ao JavaScript: window.Android.autoplay()
    class AndroidBridge {
        @JavascriptInterface
        public void autoplay() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    injectAutoplay();
                }
            });
        }
    }

    // Injeta JS dentro do iframe do Drive para clicar no botão de play
    private void injectAutoplay() {
        if (webView == null) return;
        // Avalia no contexto da página principal (localhost)
        // Busca o iframe e tenta disparar um clique no centro dele via coordenadas reais
        String js =
            "(function() {" +
            "  var f = document.getElementById('frame');" +
            "  if (!f) return;" +
            "  var r = f.getBoundingClientRect();" +
            "  var cx = r.left + r.width/2;" +
            "  var cy = r.top  + r.height/2;" +
            // Dispara touchstart + touchend no elemento — WebView reconhece como gesto real
            "  var el = document.elementFromPoint(cx, cy) || f;" +
            "  function fire(type) {" +
            "    var e = new MouseEvent(type, {bubbles:true,cancelable:true,clientX:cx,clientY:cy,view:window});" +
            "    el.dispatchEvent(e);" +
            "  }" +
            "  fire('mousedown');" +
            "  fire('mouseup');" +
            "  fire('click');" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    static class LocalServer {
        private final ServerSocket serverSocket;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final android.content.res.AssetManager assets;
        private volatile boolean running = true;

        LocalServer(android.content.res.AssetManager assets) throws IOException {
            this.assets = assets;
            serverSocket = new ServerSocket(PORT);
        }

        void start() {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final Socket client = serverSocket.accept();
                            pool.execute(new Runnable() {
                                @Override
                                public void run() { handle(client); }
                            });
                        } catch (IOException ignored) {}
                    }
                }
            });
        }

        void stop() {
            running = false;
            try { serverSocket.close(); } catch (IOException ignored) {}
            pool.shutdown();
        }

        private byte[] readAllBytes(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }

        private void handle(Socket client) {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                StringBuilder sb = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    sb.append((char) b);
                    String s = sb.toString();
                    if (s.endsWith("\r\n\r\n") || s.length() > 8192) break;
                }

                String req = sb.toString();
                String path = "/index.html";
                if (req.startsWith("GET ")) {
                    String[] parts = req.split(" ");
                    if (parts.length >= 2) path = parts[1];
                    if (path.equals("/")) path = "/index.html";
                }
                int q = path.indexOf('?');
                if (q != -1) path = path.substring(0, q);

                String assetPath = path.startsWith("/") ? path.substring(1) : path;
                String mime = getMime(assetPath);

                try {
                    InputStream asset = assets.open(assetPath);
                    byte[] data = readAllBytes(asset);
                    asset.close();

                    String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + mime + "\r\n" +
                        "Content-Length: " + data.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                    out.write(header.getBytes(Charset.forName("UTF-8")));
                    out.write(data);
                } catch (IOException e) {
                    byte[] body = "Not Found".getBytes(Charset.forName("UTF-8"));
                    String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    out.write(resp.getBytes(Charset.forName("UTF-8")));
                    out.write(body);
                }

                out.flush();
                client.close();
            } catch (IOException e) {
                Log.e(TAG, "handle: " + e.getMessage());
            }
        }

        private String getMime(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".js"))   return "application/javascript";
            if (path.endsWith(".css"))  return "text/css";
            if (path.endsWith(".png"))  return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".json")) return "application/json";
            return "application/octet-stream";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        final int uiFlags =
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);

        try {
            localServer = new LocalServer(getAssets());
            localServer.start();
            Log.d(TAG, "Servidor HTTP local na porta " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Erro servidor: " + e.getMessage());
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // autoplay sem gesto
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDefaultTextEncodingName("UTF-8");

        // Expor bridge Android → JS
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                getWindow().getDecorView().setSystemUiVisibility(uiFlags);

                // Quando a página principal (localhost) termina de carregar,
                // aguardar 1s e tentar autoplay do iframe via JS
                if (url != null && url.contains("localhost")) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            injectAutoplay();
                        }
                    }, 1000);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("http://localhost:" + PORT + "/index.html");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        webView.pauseTimers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (localServer != null) localServer.stop();
        if (webView != null) webView.destroy();
    }
}

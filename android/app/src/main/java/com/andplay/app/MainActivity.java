package com.andplay.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.app.Activity;

public class MainActivity extends Activity {

    private static final String REMOTE_URL = "https://andsouzam.github.io/AndPlay/?mode=tv";
    private static final String LOCAL_URL = "file:///android_asset/index.html";

    private WebView webView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View customView;
    private boolean isFallbackLoaded = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Manter a tela do projetor / TV sempre ligada durante a reprodução
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Layout raiz contendo WebView e container de vídeo em tela cheia
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF0A0A0C);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.addView(webView);

        customViewContainer = new FrameLayout(this);
        customViewContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        customViewContainer.setVisibility(View.GONE);
        rootLayout.addView(customViewContainer);

        setContentView(rootLayout);

        // Oculta barras de navegação com segurança após o layout estar anexado
        try {
            hideSystemUI();
        } catch (Exception ignored) {}

        // Configurações avançadas do WebView para streaming IPTV e D-Pad
        configureWebSettings();

        // Configura clientes para tratamento de navegação e vídeo fullscreen
        setupWebViewClients();

        // Inicia carregando a versão local empacotada (instantânea para TV e Projetores)
        loadApplication();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebSettings() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " AndPlayTV/2.0 (SmartTV/Projector; CableBox)");

        // Bloqueia abertura de popups e novas janelas de anúncios
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        // Permite streams HTTP mesmo em páginas HTTPS (crucial para IPTV)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Habilita foco e teclado para controle remoto (D-Pad)
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
    }

    private void setupWebViewClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!isFallbackLoaded && request.isForMainFrame()) {
                    isFallbackLoaded = true;
                    view.loadUrl(LOCAL_URL);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript("window.isAndroidTvApp = true;", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Iframes: sempre permitir
                if (!request.isForMainFrame()) {
                    return false;
                }
                // Frame principal: permite navegação interna
                String url = request.getUrl().toString();
                if (url.startsWith("file://")
                        || url.contains("andsouzam.github.io")
                        || url.contains("rdcanais.net")
                        || url.contains("esportesembed.net")
                        || url.contains("bolodechocolate.fit")
                        || url.contains("v2.rdembed.sbs")
                        || url.contains("about:blank")) {
                    return false;
                }
                android.util.Log.w("AndPlay", "Bloqueado redirect externo: " + url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                customViewContainer.addView(customView);
                customViewContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                customViewContainer.setVisibility(View.GONE);
                customViewContainer.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadApplication() {
        // Carrega 100% local a interface de TV a Cabo para inicialização imediata
        isFallbackLoaded = true;
        webView.loadUrl(LOCAL_URL);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (action == KeyEvent.ACTION_DOWN) {
            // Teclas de controle de TV por Assinatura / Projetor
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                webView.evaluateJavascript("if(window.onTvRemoteKey) window.onTvRemoteKey('CHANNEL_UP');", null);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                webView.evaluateJavascript("if(window.onTvRemoteKey) window.onTvRemoteKey('CHANNEL_DOWN');", null);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MENU) {
                webView.evaluateJavascript("if(window.onTvRemoteKey) window.onTvRemoteKey('MENU');", null);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_GUIDE) {
                webView.evaluateJavascript("if(window.onTvRemoteKey) window.onTvRemoteKey('GUIDE');", null);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                webView.evaluateJavascript("if(window.onTvRemoteKey) window.onTvRemoteKey('PLAY_PAUSE');", null);
                return true;
            }
        }

        // Intercepta botão Voltar do controle remoto da TV / Projetor
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP) {
            if (customView != null) {
                WebChromeClient client = (WebChromeClient) webView.getWebChromeClient();
                if (client != null) {
                    client.onHideCustomView();
                    return true;
                }
            }

            webView.evaluateJavascript(
                "if (window.handleAndroidBack && window.handleAndroidBack()) { 'HANDLED'; } else { 'UNHANDLED'; }",
                value -> {
                    if (value == null || !value.contains("HANDLED")) {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    }
                }
            );
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemUI();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}

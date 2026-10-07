package com.andplay.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import androidx.annotation.Nullable;
import com.andplay.app.account.AccountManager;
import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class UserActivity extends Activity {

    public static final String EXTRA_MODE_CHANGED = "mode_changed";

    private static final int RC_GOOGLE_SIGN_IN = 9001;
    private static final String GOOGLE_WEB_CLIENT_ID = "949615938522-judfcp611kbuvvat8jkl95lhog9docom.apps.googleusercontent.com";

    private GoogleSignInClient googleSignInClient;

    private WebSocket tvPairingWebSocket;
    private final Handler pairingHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private AlertDialog tvPairingDialog;

    private TextView userAvatarDisplay;
    private TextView userProfileName;
    private TextView userProfileBadge;
    private TextView userProfileEmail;
    private TextView userProfileSyncInfo;

    private View userGuestActions;
    private View userLoggedInActions;
    private View btnUserGoogle;
    private View btnUserQrCode;
    private View btnUserLogin;
    private View btnUserRegister;
    private View btnUserSyncNow;
    private View btnUserSignOut;

    private View cardModeSelection;
    private View btnOpenModeModal;
    private TextView txtCurrentModeTitle;
    private TextView tagCurrentModeBadge;
    private TextView txtCurrentModeDesc;

    private TextView statMoviesVal;
    private TextView statSeriesVal;
    private TextView statFavsVal;
    private View btnUserClearCache;

    private static final int REQ_MODE_SELECTION = 2001;
    private boolean modeChanged = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        initGoogleSignIn();
        initViews();
        setupListeners();
        handleAuthRedirect(getIntent());
        updateUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthRedirect(intent);
    }

    private void initViews() {
        userAvatarDisplay = findViewById(R.id.userAvatarDisplay);
        userProfileName = findViewById(R.id.userProfileName);
        userProfileBadge = findViewById(R.id.userProfileBadge);
        userProfileEmail = findViewById(R.id.userProfileEmail);
        userProfileSyncInfo = findViewById(R.id.userProfileSyncInfo);

        userGuestActions = findViewById(R.id.userGuestActions);
        userLoggedInActions = findViewById(R.id.userLoggedInActions);
        btnUserGoogle = findViewById(R.id.btnUserGoogle);
        btnUserQrCode = findViewById(R.id.btnUserQrCode);
        btnUserLogin = findViewById(R.id.btnUserLogin);
        btnUserRegister = findViewById(R.id.btnUserRegister);
        btnUserSyncNow = findViewById(R.id.btnUserSyncNow);
        btnUserSignOut = findViewById(R.id.btnUserSignOut);

        cardModeSelection = findViewById(R.id.cardModeSelection);
        btnOpenModeModal = findViewById(R.id.btnOpenModeModal);
        txtCurrentModeTitle = findViewById(R.id.txtCurrentModeTitle);
        tagCurrentModeBadge = findViewById(R.id.tagCurrentModeBadge);
        txtCurrentModeDesc = findViewById(R.id.txtCurrentModeDesc);

        statMoviesVal = findViewById(R.id.statMoviesVal);
        statSeriesVal = findViewById(R.id.statSeriesVal);
        statFavsVal = findViewById(R.id.statFavsVal);
        btnUserClearCache = findViewById(R.id.btnUserClearCache);
    }

    private void setupListeners() {
        // Clicar no cabeçalho ou cards de modo abre o modal com corte diagonal
        if (cardModeSelection != null) {
            cardModeSelection.setOnClickListener(v -> openModeSelectionModal());
            cardModeSelection.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    openModeSelectionModal();
                    return true;
                }
                return false;
            });
            setupFocusAnimation(cardModeSelection);
        }

        if (btnOpenModeModal != null) {
            btnOpenModeModal.setOnClickListener(v -> openModeSelectionModal());
            setupFocusAnimation(btnOpenModeModal);
        }


        setupFocusAnimation(btnUserGoogle);
        setupFocusAnimation(btnUserQrCode);
        setupFocusAnimation(btnUserLogin);
        setupFocusAnimation(btnUserRegister);
        setupFocusAnimation(btnUserSyncNow);
        setupFocusAnimation(btnUserSignOut);
        setupFocusAnimation(btnUserClearCache);

        // Ações de Conta
        if (btnUserGoogle != null) {
            btnUserGoogle.setOnClickListener(v -> startGoogleSignIn());
        }
        if (btnUserQrCode != null) {
            btnUserQrCode.setOnClickListener(v -> showQrCodeLoginDialog());
        }
        btnUserLogin.setOnClickListener(v -> showAuthDialog(false));
        btnUserRegister.setOnClickListener(v -> showAuthDialog(true));

        btnUserSyncNow.setOnClickListener(v -> {
            Toast.makeText(this, "Sincronizando com Supabase Cloud...", Toast.LENGTH_SHORT).show();
            AccountManager.getInstance(this).syncAll((success, favCount, progCount) -> {
                if (success) {
                    Toast.makeText(this, "Sincronizado: " + favCount + " favoritos e " + progCount + " no histórico.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Falha na sincronização. Verifique a conexão.", Toast.LENGTH_SHORT).show();
                }
                modeChanged = true;
                updateUi();
            });
        });

        btnUserSignOut.setOnClickListener(v -> {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Desconectar")
                    .setMessage("Deseja sair da sua conta EPlay neste dispositivo?")
                    .setPositiveButton("Sair", (dialog, which) -> {
                        AccountManager.getInstance(this).signOut();
                        Toast.makeText(this, "Você foi desconectado.", Toast.LENGTH_SHORT).show();
                        modeChanged = true;
                        updateUi();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });

        btnUserClearCache.setOnClickListener(v -> {
            Toast.makeText(this, "Limpando cache de imagens...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                Glide.get(getApplicationContext()).clearDiskCache();
                runOnUiThread(() -> {
                    Glide.get(getApplicationContext()).clearMemory();
                    Toast.makeText(this, "Cache de imagens liberado.", Toast.LENGTH_SHORT).show();
                });
            }).start();
        });
    }

    private void setupFocusAnimation(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(120).start();
        });
    }

    private void selectViewMode(String mode) {
        String current = AccountManager.getInstance(this).getViewMode();
        if (!mode.equals(current)) {
            AccountManager.getInstance(this).setViewMode(mode);
            modeChanged = true;
            updateUi();
            String label = AccountManager.VIEW_MODE_CINEMA.equals(mode)
                    ? "🎬 Modo Cinema (Filmes & Séries) ativado como padrão!"
                    : "📺 Modo TV Ao Vivo ativado como padrão!";
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUi() {
        AccountManager am = AccountManager.getInstance(this);
        boolean signedIn = am.isSignedIn();

        if (signedIn) {
            userProfileName.setText(am.getDisplayName());
            userProfileBadge.setText("★ CONECTADO");
            userProfileEmail.setText(am.getEmail());
            userAvatarDisplay.setText(am.getAvatarEmoji());

            long lastSync = am.getLastSyncTimestamp();
            if (lastSync > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
                userProfileSyncInfo.setText("Última sincronização: " + sdf.format(new Date(lastSync)));
            } else {
                userProfileSyncInfo.setText("Supabase Cloud • Sincronização em tempo real");
            }

            userGuestActions.setVisibility(View.GONE);
            userLoggedInActions.setVisibility(View.VISIBLE);
            btnUserSyncNow.requestFocus();
        } else {
            userProfileName.setText("Usuário Convidado");
            userProfileBadge.setText("MODO OFFLINE");
            userProfileEmail.setText("Entre na sua conta para sincronizar favoritos e histórico com a versão Web");
            userProfileSyncInfo.setText("Supabase Cloud • Dados locais apenas");
            userAvatarDisplay.setText("👤");

            userGuestActions.setVisibility(View.VISIBLE);
            userLoggedInActions.setVisibility(View.GONE);
            btnUserLogin.requestFocus();
        }

        // Modo de visualização ativo
        String mode = am.getViewMode();
        boolean isCinema = AccountManager.VIEW_MODE_CINEMA.equals(mode);
        if (txtCurrentModeTitle != null) {
            txtCurrentModeTitle.setText(isCinema ? "🎬 Modo Cinema (Filmes & Séries)" : "📺 Modo TV Ao Vivo");
        }
        if (txtCurrentModeDesc != null) {
            txtCurrentModeDesc.setText(isCinema
                    ? "Focado em streaming on-demand: banner em destaque, continuar assistindo, catálogo completo de filmes e séries em alta."
                    : "Central com PiP do canal ao vivo, grade de canais abertos e fechados e trilho de esportes em tempo real.");
        }

        // Estatísticas
        int favCount = am.getFavorites().size();
        statFavsVal.setText(String.valueOf(favCount));

        int movieCount = getRecentMovieCount();
        statMoviesVal.setText(String.valueOf(movieCount));

        int seriesCount = getRecentSeriesCount();
        statSeriesVal.setText(String.valueOf(seriesCount));
    }

    private int getRecentMovieCount() {
        try {
            SharedPreferences prefs = getSharedPreferences("andplay_recent_movies", Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            if (!raw.isEmpty()) {
                return raw.split(",").length;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int getRecentSeriesCount() {
        try {
            SharedPreferences prefs = getSharedPreferences("andplay_recent_series", Context.MODE_PRIVATE);
            String raw = prefs.getString("history", "");
            if (!raw.isEmpty()) {
                return raw.split(",").length;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void showAuthDialog(boolean isRegister) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle(isRegister ? "✨ Criar Nova Conta EPlay" : "🔑 Entrar na Conta EPlay");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText etEmail = new EditText(this);
        etEmail.setHint("Seu e-mail");
        etEmail.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        layout.addView(etEmail);

        final EditText etPassword = new EditText(this);
        etPassword.setHint("Sua senha");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etPassword);

        builder.setView(layout);

        builder.setPositiveButton(isRegister ? "Cadastrar" : "Entrar", (dialog, which) -> {
            String email = etEmail.getText().toString().trim();
            String pass = etPassword.getText().toString().trim();

            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha e-mail e senha.", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "Conectando ao Supabase...", Toast.LENGTH_SHORT).show();
            AccountManager.AuthCallback cb = new AccountManager.AuthCallback() {
                @Override
                public void onSuccess(String uEmail, String uName) {
                    Toast.makeText(UserActivity.this, "Conectado como " + uName + "!", Toast.LENGTH_LONG).show();
                    modeChanged = true;
                    updateUi();

                    // Se for o primeiro login/acesso e ainda não escolheu o modo, abre a tela de escolha
                    if (!AccountManager.getInstance(UserActivity.this).hasChosenInitialMode()) {
                        openModeSelectionModal();
                    }
                }

                @Override
                public void onError(String message) {
                    Toast.makeText(UserActivity.this, "Erro: " + message, Toast.LENGTH_LONG).show();
                }
            };

            if (isRegister) {
                AccountManager.getInstance(this).signUp(email, pass, cb);
            } else {
                AccountManager.getInstance(this).signIn(email, pass, cb);
            }
        });

        if (!isRegister) {
            builder.setNeutralButton("Esqueci a Senha", (dialog, which) -> {
                String email = etEmail.getText().toString().trim();
                if (email.isEmpty()) {
                    Toast.makeText(this, "Digite seu e-mail no campo acima primeiro.", Toast.LENGTH_LONG).show();
                    return;
                }
                AccountManager.getInstance(this).recoverPassword(email, (success, message) -> {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            });
        }

        TextView tvGoogle = new TextView(this);
        tvGoogle.setText("🌐 Ou clique aqui para entrar com Google (Nativo)");
        tvGoogle.setTextColor(Color.parseColor("#38BDF8"));
        tvGoogle.setTextSize(13f);
        tvGoogle.setPadding(0, 24, 0, 10);
        tvGoogle.setFocusable(true);
        tvGoogle.setClickable(true);
        layout.addView(tvGoogle);

        builder.setNegativeButton("Cancelar", null);
        AlertDialog dialog = builder.create();
        tvGoogle.setOnClickListener(v -> {
            dialog.dismiss();
            startGoogleSignIn();
        });
        dialog.show();
    }

    private void initGoogleSignIn() {
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(GOOGLE_WEB_CLIENT_ID)
                    .requestEmail()
                    .build();
            googleSignInClient = GoogleSignIn.getClient(this, gso);
        } catch (Exception e) {
            Log.w("UserActivity", "Não foi possível inicializar GoogleSignInClient: " + e.getMessage());
        }
    }

    private void startGoogleSignIn() {
        if (googleSignInClient == null) {
            initGoogleSignIn();
        }

        try {
            if (googleSignInClient != null) {
                googleSignInClient.signOut();
                Intent signInIntent = googleSignInClient.getSignInIntent();
                startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN);
                return;
            }
        } catch (Exception e) {
            Log.w("UserActivity", "Falha ao iniciar Google Sign-In nativo: " + e.getMessage());
        }

        fallbackGoogleOAuthBrowser();
    }

    private void handleGoogleSignInResult(Intent data) {
        if (data == null) return;
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            if (account != null && account.getIdToken() != null) {
                String idToken = account.getIdToken();
                String displayName = account.getDisplayName();
                Toast.makeText(this, "Conectando ao EPlay com conta Google...", Toast.LENGTH_SHORT).show();

                AccountManager.getInstance(this).signInWithGoogleIdToken(idToken, new AccountManager.AuthCallback() {
                    @Override
                    public void onSuccess(String uEmail, String uName) {
                        Toast.makeText(UserActivity.this, "Conectado como " + uName + "!", Toast.LENGTH_LONG).show();
                        modeChanged = true;
                        updateUi();
                        if (!AccountManager.getInstance(UserActivity.this).hasChosenInitialMode()) {
                            openModeSelectionModal();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(UserActivity.this, "Erro ao autenticar: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Toast.makeText(this, "Não foi possível obter o token do Google.", Toast.LENGTH_SHORT).show();
            }
        } catch (ApiException e) {
            int code = e.getStatusCode();
            if (code == 12501 || code == 12502) {
                return;
            }
            Log.e("UserActivity", "Erro no Google Sign-In: " + code, e);
            Toast.makeText(this, "Serviços Google indisponíveis na TV (código " + code + "). Conecte pelo celular!", Toast.LENGTH_LONG).show();
            showQrCodeLoginDialog();
        }
    }

    private void showQrCodeLoginDialog() {
        // Gera código de pareamento de 4 dígitos (ex: 4819)
        String pairCode = String.format(Locale.US, "%04d", 1000 + new Random().nextInt(9000));
        String pairUrl = "https://andsouzam.github.io/AndPlay/tv-login.html?code=" + pairCode;

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_login, null);
        builder.setView(dialogView);

        ImageView ivQr = dialogView.findViewById(R.id.dialogQrImage);
        TextView tvCode = dialogView.findViewById(R.id.dialogQrCodeText);
        TextView tvUrl = dialogView.findViewById(R.id.dialogQrUrlText);
        TextView tvStatus = dialogView.findViewById(R.id.dialogQrStatusText);
        ProgressBar pbProgress = dialogView.findViewById(R.id.dialogQrProgress);
        View btnClose = dialogView.findViewById(R.id.dialogQrBtnClose);

        tvCode.setText(pairCode);
        tvUrl.setText("andsouzam.github.io/AndPlay/tv-login.html?code=" + pairCode);

        Bitmap qrBmp = generateQrCodeBitmap(pairUrl, 512, 512);
        if (qrBmp != null) {
            ivQr.setImageBitmap(qrBmp);
        }

        tvPairingDialog = builder.create();
        if (tvPairingDialog.getWindow() != null) {
            tvPairingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnClose.setOnClickListener(v -> tvPairingDialog.dismiss());
        btnClose.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(120).start();
        });

        tvPairingDialog.setOnDismissListener(d -> {
            pairingHandler.removeCallbacksAndMessages(null);
            if (tvPairingWebSocket != null) {
                try {
                    tvPairingWebSocket.close(1000, "Dialog closed");
                } catch (Exception ignored) {}
                tvPairingWebSocket = null;
            }
        });

        // Inicia conexão WebSocket com Supabase Realtime
        startPairingListener(pairCode, tvStatus, pbProgress);

        tvPairingDialog.show();
        btnClose.requestFocus();
    }

    private void startPairingListener(String pairCode, TextView tvStatus, ProgressBar pbProgress) {
        if (tvPairingWebSocket != null) {
            try {
                tvPairingWebSocket.close(1000, "New session");
            } catch (Exception ignored) {}
            tvPairingWebSocket = null;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url("wss://zfawwhqogtynuygniskz.supabase.co/realtime/v1/websocket?apikey=sb_publishable_naUBrBzRCU_SQbSiNtpQQQ_7Q8h1VoJ&vsn=1.0.0")
                .build();

        tvPairingWebSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // Entra no canal tv_pair_<pairCode>
                JsonObject joinPayload = new JsonObject();
                JsonObject config = new JsonObject();
                JsonObject broadcast = new JsonObject();
                broadcast.addProperty("self", false);
                config.add("broadcast", broadcast);
                joinPayload.add("config", config);

                JsonObject joinMsg = new JsonObject();
                joinMsg.addProperty("topic", "realtime:tv_pair_" + pairCode);
                joinMsg.addProperty("event", "phx_join");
                joinMsg.add("payload", joinPayload);
                joinMsg.addProperty("ref", "join_1");

                webSocket.send(joinMsg.toString());

                // Inicia batimento cardíaco (heartbeat) a cada 25 segundos
                heartbeatRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (tvPairingWebSocket != null) {
                            JsonObject hb = new JsonObject();
                            hb.addProperty("topic", "phoenix");
                            hb.addProperty("event", "heartbeat");
                            hb.add("payload", new JsonObject());
                            hb.addProperty("ref", "hb_" + System.currentTimeMillis());
                            tvPairingWebSocket.send(hb.toString());
                            pairingHandler.postDelayed(this, 25000);
                        }
                    }
                };
                pairingHandler.postDelayed(heartbeatRunnable, 25000);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                    String event = msg.has("event") ? msg.get("event").getAsString() : "";
                    if ("broadcast".equals(event) && msg.has("payload")) {
                        JsonObject pl = msg.getAsJsonObject("payload");
                        if (pl.has("payload")) {
                            JsonObject data = pl.getAsJsonObject("payload");
                            String accessToken = data.has("accessToken") ? data.get("accessToken").getAsString()
                                    : (data.has("access_token") ? data.get("access_token").getAsString() : null);
                            String refreshToken = data.has("refreshToken") ? data.get("refreshToken").getAsString()
                                    : (data.has("refresh_token") ? data.get("refresh_token").getAsString() : null);
                            String displayName = data.has("displayName") ? data.get("displayName").getAsString()
                                    : (data.has("display_name") ? data.get("display_name").getAsString() : "Usuário");

                            if (accessToken != null && !accessToken.isEmpty()) {
                                runOnUiThread(() -> {
                                    if (tvStatus != null) {
                                        tvStatus.setText("✅ Celular conectado! Finalizando login...");
                                        tvStatus.setTextColor(Color.parseColor("#4ADE80"));
                                    }
                                    if (pbProgress != null) {
                                        pbProgress.setVisibility(View.GONE);
                                    }
                                    AccountManager.getInstance(UserActivity.this).saveSessionFromTokens(
                                            accessToken, refreshToken, new AccountManager.AuthCallback() {
                                                @Override
                                                public void onSuccess(String uEmail, String uName) {
                                                    Toast.makeText(UserActivity.this, "🎉 TV conectada com sucesso como " + uName + "!", Toast.LENGTH_LONG).show();
                                                    modeChanged = true;
                                                    updateUi();
                                                    pairingHandler.postDelayed(() -> {
                                                        if (tvPairingDialog != null && tvPairingDialog.isShowing()) {
                                                            tvPairingDialog.dismiss();
                                                        }
                                                        if (!AccountManager.getInstance(UserActivity.this).hasChosenInitialMode()) {
                                                            openModeSelectionModal();
                                                        }
                                                    }, 1200);
                                                }

                                                @Override
                                                public void onError(String message) {
                                                    if (tvStatus != null) {
                                                        tvStatus.setText("❌ Erro ao autenticar: " + message);
                                                        tvStatus.setTextColor(Color.parseColor("#EF4444"));
                                                    }
                                                }
                                            });
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("UserActivity", "Erro no processamento da mensagem de pareamento", e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w("UserActivity", "WebSocket pareamento falhou: " + t.getMessage());
            }
        });
    }

    private Bitmap generateQrCodeBitmap(String content, int width, int height) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height);
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            Log.e("UserActivity", "Erro ao gerar QR Code", e);
            return null;
        }
    }

    private void fallbackGoogleOAuthBrowser() {
        try {
            String redirectUrl = "andplay://auth-callback";
            String authUrl = "https://zfawwhqogtynuygniskz.supabase.co/auth/v1/authorize?provider=google&redirect_to="
                    + Uri.encode(redirectUrl);

            Toast.makeText(this, "Abrindo login do Google no navegador...", Toast.LENGTH_LONG).show();

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir o navegador: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleAuthRedirect(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();

        String fragment = uri.getFragment();
        String query = uri.getQuery();
        String rawParams = (fragment != null && !fragment.isEmpty()) ? fragment : query;

        if (rawParams != null && rawParams.contains("error")) {
            String errDesc = extractParam(rawParams, "error_description");
            if (errDesc == null) errDesc = extractParam(rawParams, "error");
            if (errDesc != null) {
                Toast.makeText(this, "Erro no login Google: " + errDesc.replace('+', ' '), Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (rawParams != null && rawParams.contains("access_token")) {
            String accessToken = extractParam(rawParams, "access_token");
            String refreshToken = extractParam(rawParams, "refresh_token");

            if (accessToken != null && !accessToken.isEmpty()) {
                Toast.makeText(this, "Finalizando login com Google...", Toast.LENGTH_SHORT).show();
                AccountManager.getInstance(this).saveSessionFromTokens(accessToken, refreshToken, new AccountManager.AuthCallback() {
                    @Override
                    public void onSuccess(String uEmail, String uName) {
                        Toast.makeText(UserActivity.this, "Conectado como " + uName + "!", Toast.LENGTH_LONG).show();
                        modeChanged = true;
                        updateUi();
                        if (!AccountManager.getInstance(UserActivity.this).hasChosenInitialMode()) {
                            openModeSelectionModal();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(UserActivity.this, "Erro ao autenticar: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    private String extractParam(String data, String key) {
        if (data == null) return null;
        for (String pair : data.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return Uri.decode(parts[1]);
            }
        }
        return null;
    }

    private void openModeSelectionModal() {
        Intent intent = new Intent(this, ModeSelectionActivity.class);
        startActivityForResult(intent, REQ_MODE_SELECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            handleGoogleSignInResult(data);
            return;
        }
        if (requestCode == REQ_MODE_SELECTION && resultCode == RESULT_OK && data != null) {
            String chosenMode = data.getStringExtra(ModeSelectionActivity.EXTRA_SELECTED_MODE);
            if (chosenMode != null) {
                AccountManager.getInstance(this).setViewMode(chosenMode);
            }
            modeChanged = true;
            updateUi();
            // Não é necessário confirmação para troca, apenas recarrega o app com a interface escolhida
            Intent result = new Intent();
            result.putExtra(EXTRA_MODE_CHANGED, true);
            result.putExtra("selected_mode", chosenMode);
            setResult(RESULT_OK, result);
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Intent result = new Intent();
            result.putExtra(EXTRA_MODE_CHANGED, modeChanged);
            setResult(RESULT_OK, result);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

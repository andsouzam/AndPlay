package com.andplay.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.andplay.app.account.AccountManager;
import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class UserActivity extends BaseActivity {

    public static final String EXTRA_MODE_CHANGED = "mode_changed";

    private static final int RC_GOOGLE_SIGN_IN = 9001;
    private static final String GOOGLE_WEB_CLIENT_ID = "949615938522-judfcp611kbuvvat8jkl95lhog9docom.apps.googleusercontent.com";

    private GoogleSignInClient googleSignInClient;

    private WebSocket tvPairingWebSocket;
    private final Handler pairingHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private AlertDialog tvPairingDialog;

    // Header & Hero
    private TextView userAvatarDisplay;
    private View userAvatarEditBtn;
    private TextView userProfileName;
    private View btnEditName;
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

    // 5 Metrics
    private TextView statWatchTimeVal;
    private TextView statMoviesVal;
    private TextView statSeriesVal;
    private TextView statFavsVal;
    private TextView statLiveVal;

    // 4 Tabs
    private TextView tabBtnAccount;
    private TextView tabBtnPreferences;
    private TextView tabBtnShortcuts;
    private TextView tabBtnStorage;

    // 4 Panels
    private View panelAccount;
    private View panelPreferences;
    private View panelShortcuts;
    private View panelStorage;

    // Panel 1: Minha Conta
    private View userAccountConnectedBlock;
    private TextView userConnectedEmail;
    private TextView userConnectedId;
    private TextView userConnectedSyncStatus;
    private View btnUserChangePassword;
    private View btnUserSignOutTab;
    private View btnUserDeleteData;
    private View btnUserDeleteAccount;
    private View userAccountAuthBlock;
    private View btnTabLogin;
    private View btnTabRegister;
    private View btnTabQr;

    // Panel 2: Preferências
    private Switch switchSkipIntro;
    private Switch switchAutoNext;
    private TextView txtPrefCurrentMode;
    private View btnPrefChangeMode;
    private Switch switchAdultContent;
    private TextView btnPrefQuality;
    private TextView btnPrefAudio;

    // Panel 3: Meus Conteúdos
    private View shortcutCardWatched;
    private View shortcutCardFavorites;
    private View shortcutCardLiveTv;

    // Panel 4: Armazenamento & Dados
    private TextView txtStorageUsage;
    private TextView txtAppVersion;
    private View btnStorageClearCache;
    private View btnStorageExportFavs;
    private View btnStorageResetAll;

    private static final int REQ_MODE_SELECTION = 2001;
    private boolean modeChanged = false;
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        initGoogleSignIn();
        initViews();
        setupListeners();
        handleAuthRedirect(getIntent());
        switchTab(0);
        updateUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthRedirect(intent);
    }

    private void initViews() {
        View userBackHint = findViewById(R.id.userBackHint);
        if (userBackHint != null) {
            userBackHint.setOnClickListener(v -> closeActivity());
        }

        // Hero
        userAvatarDisplay = findViewById(R.id.userAvatarDisplay);
        userAvatarEditBtn = findViewById(R.id.userAvatarEditBtn);
        userProfileName = findViewById(R.id.userProfileName);
        btnEditName = findViewById(R.id.btnEditName);
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

        // 5 Metrics
        statWatchTimeVal = findViewById(R.id.statWatchTimeVal);
        statMoviesVal = findViewById(R.id.statMoviesVal);
        statSeriesVal = findViewById(R.id.statSeriesVal);
        statFavsVal = findViewById(R.id.statFavsVal);
        statLiveVal = findViewById(R.id.statLiveVal);

        // 4 Tabs
        tabBtnAccount = findViewById(R.id.tabBtnAccount);
        tabBtnPreferences = findViewById(R.id.tabBtnPreferences);
        tabBtnShortcuts = findViewById(R.id.tabBtnShortcuts);
        tabBtnStorage = findViewById(R.id.tabBtnStorage);

        // 4 Panels
        panelAccount = findViewById(R.id.panelAccount);
        panelPreferences = findViewById(R.id.panelPreferences);
        panelShortcuts = findViewById(R.id.panelShortcuts);
        panelStorage = findViewById(R.id.panelStorage);

        // Panel 1: Minha Conta
        userAccountConnectedBlock = findViewById(R.id.userAccountConnectedBlock);
        userConnectedEmail = findViewById(R.id.userConnectedEmail);
        userConnectedId = findViewById(R.id.userConnectedId);
        userConnectedSyncStatus = findViewById(R.id.userConnectedSyncStatus);
        btnUserChangePassword = findViewById(R.id.btnUserChangePassword);
        btnUserSignOutTab = findViewById(R.id.btnUserSignOutTab);
        btnUserDeleteData = findViewById(R.id.btnUserDeleteData);
        btnUserDeleteAccount = findViewById(R.id.btnUserDeleteAccount);
        userAccountAuthBlock = findViewById(R.id.userAccountAuthBlock);
        btnTabLogin = findViewById(R.id.btnTabLogin);
        btnTabRegister = findViewById(R.id.btnTabRegister);
        btnTabQr = findViewById(R.id.btnTabQr);

        // Panel 2: Preferências
        switchSkipIntro = findViewById(R.id.switchSkipIntro);
        switchAutoNext = findViewById(R.id.switchAutoNext);
        txtPrefCurrentMode = findViewById(R.id.txtPrefCurrentMode);
        btnPrefChangeMode = findViewById(R.id.btnPrefChangeMode);
        switchAdultContent = findViewById(R.id.switchAdultContent);
        btnPrefQuality = findViewById(R.id.btnPrefQuality);
        btnPrefAudio = findViewById(R.id.btnPrefAudio);

        // Panel 3: Meus Conteúdos
        shortcutCardWatched = findViewById(R.id.shortcutCardWatched);
        shortcutCardFavorites = findViewById(R.id.shortcutCardFavorites);
        shortcutCardLiveTv = findViewById(R.id.shortcutCardLiveTv);

        // Panel 4: Armazenamento & Dados
        txtStorageUsage = findViewById(R.id.txtStorageUsage);
        txtAppVersion = findViewById(R.id.txtAppVersion);
        if (txtAppVersion != null) {
            String ver = "2.6.4";
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            txtAppVersion.setText("EPlay Android v" + ver + " • Pro Build");
        }
        btnStorageClearCache = findViewById(R.id.btnStorageClearCache);
        btnStorageExportFavs = findViewById(R.id.btnStorageExportFavs);
        btnStorageResetAll = findViewById(R.id.btnStorageResetAll);
    }

    private void setupListeners() {
        // Focus animations
        setupFocusAnimation(userAvatarDisplay);
        setupFocusAnimation(userAvatarEditBtn);
        setupFocusAnimation(userProfileName);
        setupFocusAnimation(btnEditName);
        setupFocusAnimation(btnUserGoogle);
        setupFocusAnimation(btnUserQrCode);
        setupFocusAnimation(btnUserLogin);
        setupFocusAnimation(btnUserRegister);
        setupFocusAnimation(btnUserSyncNow);
        setupFocusAnimation(btnUserSignOut);

        setupFocusAnimation(tabBtnAccount);
        setupFocusAnimation(tabBtnPreferences);
        setupFocusAnimation(tabBtnShortcuts);
        setupFocusAnimation(tabBtnStorage);

        setupFocusAnimation(btnUserChangePassword);
        setupFocusAnimation(btnUserSignOutTab);
        setupFocusAnimation(btnUserDeleteData);
        setupFocusAnimation(btnUserDeleteAccount);
        setupFocusAnimation(btnTabLogin);
        setupFocusAnimation(btnTabRegister);
        setupFocusAnimation(btnTabQr);

        setupFocusAnimation(btnPrefChangeMode);
        setupFocusAnimation(btnPrefQuality);
        setupFocusAnimation(btnPrefAudio);

        setupFocusAnimation(shortcutCardWatched);
        setupFocusAnimation(shortcutCardFavorites);
        setupFocusAnimation(shortcutCardLiveTv);

        setupFocusAnimation(btnStorageClearCache);
        setupFocusAnimation(btnStorageExportFavs);
        setupFocusAnimation(btnStorageResetAll);

        // Avatar & Name
        if (userAvatarDisplay != null) {
            userAvatarDisplay.setOnClickListener(v -> showAvatarPickerDialog());
        }
        if (userAvatarEditBtn != null) {
            userAvatarEditBtn.setOnClickListener(v -> showAvatarPickerDialog());
        }
        if (userProfileName != null) {
            userProfileName.setOnClickListener(v -> showEditNameDialog());
        }
        if (btnEditName != null) {
            btnEditName.setOnClickListener(v -> showEditNameDialog());
        }

        // Tabs
        if (tabBtnAccount != null) tabBtnAccount.setOnClickListener(v -> switchTab(0));
        if (tabBtnPreferences != null) tabBtnPreferences.setOnClickListener(v -> switchTab(1));
        if (tabBtnShortcuts != null) tabBtnShortcuts.setOnClickListener(v -> switchTab(2));
        if (tabBtnStorage != null) tabBtnStorage.setOnClickListener(v -> switchTab(3));

        // Hero Actions
        if (btnUserGoogle != null) btnUserGoogle.setOnClickListener(v -> startGoogleSignIn());
        if (btnUserQrCode != null) btnUserQrCode.setOnClickListener(v -> showQrCodeLoginDialog());
        if (btnUserLogin != null) btnUserLogin.setOnClickListener(v -> showAuthDialog(false));
        if (btnUserRegister != null) btnUserRegister.setOnClickListener(v -> showAuthDialog(true));
        if (btnTabLogin != null) btnTabLogin.setOnClickListener(v -> showAuthDialog(false));
        if (btnTabRegister != null) btnTabRegister.setOnClickListener(v -> showAuthDialog(true));
        if (btnTabQr != null) btnTabQr.setOnClickListener(v -> showQrCodeLoginDialog());

        if (btnUserSyncNow != null) {
            btnUserSyncNow.setOnClickListener(v -> syncCloudData());
        }
        if (btnUserSignOut != null) {
            btnUserSignOut.setOnClickListener(v -> confirmSignOut());
        }
        if (btnUserSignOutTab != null) {
            btnUserSignOutTab.setOnClickListener(v -> confirmSignOut());
        }

        if (btnUserChangePassword != null) {
            btnUserChangePassword.setOnClickListener(v -> {
                String email = AccountManager.getInstance(this).getEmail();
                if (email != null && !email.isEmpty()) {
                    AccountManager.getInstance(this).recoverPassword(email, (success, message) -> {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    });
                } else {
                    Toast.makeText(this, "Nenhum e-mail associado à sessão.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Privacy & Deletion Actions
        if (btnUserDeleteData != null) {
            btnUserDeleteData.setOnClickListener(v -> confirmDeleteUserData());
        }
        if (btnUserDeleteAccount != null) {
            btnUserDeleteAccount.setOnClickListener(v -> confirmDeleteUserAccount());
        }

        // Panel 2: Preferências
        if (switchSkipIntro != null) {
            switchSkipIntro.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AccountManager.getInstance(this).setSkipIntroEnabled(isChecked);
                Toast.makeText(this, isChecked ? "Pular aberturas ativado." : "Pular aberturas desativado.", Toast.LENGTH_SHORT).show();
            });
        }
        if (switchAutoNext != null) {
            switchAutoNext.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AccountManager.getInstance(this).setAutoNextEnabled(isChecked);
                Toast.makeText(this, isChecked ? "Próximo episódio automático ativado." : "Próximo episódio automático desativado.", Toast.LENGTH_SHORT).show();
            });
        }
        if (switchAdultContent != null) {
            switchAdultContent.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AccountManager.getInstance(this).setAdultContentEnabled(isChecked);
                Toast.makeText(this, isChecked ? "Conteúdo adulto ativado." : "Conteúdo adulto desativado.", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnPrefChangeMode != null) {
            btnPrefChangeMode.setOnClickListener(v -> openModeSelectionModal());
        }
        if (btnPrefQuality != null) {
            btnPrefQuality.setOnClickListener(v -> showQualityPickerDialog());
        }
        if (btnPrefAudio != null) {
            btnPrefAudio.setOnClickListener(v -> showAudioPickerDialog());
        }

        // Panel 3: Atalhos
        if (shortcutCardWatched != null) {
            shortcutCardWatched.setOnClickListener(v -> {
                Intent r = new Intent();
                r.putExtra("shortcut", "watched");
                setResult(RESULT_OK, r);
                finish();
            });
        }
        if (shortcutCardFavorites != null) {
            shortcutCardFavorites.setOnClickListener(v -> {
                Intent r = new Intent();
                r.putExtra("shortcut", "favorites");
                setResult(RESULT_OK, r);
                finish();
            });
        }
        if (shortcutCardLiveTv != null) {
            shortcutCardLiveTv.setOnClickListener(v -> {
                Intent r = new Intent();
                r.putExtra("shortcut", "livetv");
                setResult(RESULT_OK, r);
                finish();
            });
        }

        // Panel 4: Armazenamento
        if (btnStorageClearCache != null) {
            btnStorageClearCache.setOnClickListener(v -> clearAppCaches());
        }
        if (btnStorageExportFavs != null) {
            btnStorageExportFavs.setOnClickListener(v -> exportFavoritesJson());
        }
        if (btnStorageResetAll != null) {
            btnStorageResetAll.setOnClickListener(v -> confirmResetLocalData());
        }
    }

    private void switchTab(int index) {
        currentTab = index;
        if (tabBtnAccount != null) tabBtnAccount.setSelected(index == 0);
        if (tabBtnPreferences != null) tabBtnPreferences.setSelected(index == 1);
        if (tabBtnShortcuts != null) tabBtnShortcuts.setSelected(index == 2);
        if (tabBtnStorage != null) tabBtnStorage.setSelected(index == 3);

        if (panelAccount != null) panelAccount.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (panelPreferences != null) panelPreferences.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (panelShortcuts != null) panelShortcuts.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (panelStorage != null) panelStorage.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        if (index == 3) {
            updateStorageUsage();
        }
    }

    private void showAvatarPickerDialog() {
        final String[] emojis = {"👤", "🍿", "🎬", "🚀", "🦁", "👑", "⚡", "🕶️"};
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Escolha seu Avatar");
        b.setItems(emojis, (dialog, which) -> {
            String chosen = emojis[which];
            AccountManager.getInstance(this).setAvatarEmoji(chosen);
            if (userAvatarDisplay != null) {
                userAvatarDisplay.setText(chosen);
            }
            Toast.makeText(this, "Avatar atualizado: " + chosen, Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void showEditNameDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Nome de Exibição");
        final EditText input = new EditText(this);
        input.setText(AccountManager.getInstance(this).getDisplayName());
        input.setSelection(input.getText().length());
        input.setHint("Seu nome ou apelido");
        input.setTextColor(Color.WHITE);
        b.setView(input);
        b.setPositiveButton("Salvar", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                AccountManager.getInstance(this).setDisplayName(newName);
                if (userProfileName != null) {
                    userProfileName.setText(newName);
                }
                Toast.makeText(this, "Nome atualizado!", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void showQualityPickerDialog() {
        final String[] labels = {"Automático (Padrão)", "4K Ultra HD (Prioritário)", "Full HD (1080p)", "HD (720p)"};
        final String[] values = {"auto", "4k", "1080p", "720p"};
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Qualidade Preferida de Vídeo");
        b.setItems(labels, (dialog, which) -> {
            AccountManager.getInstance(this).setVideoQualityPreference(values[which]);
            if (btnPrefQuality != null) {
                btnPrefQuality.setText(labels[which] + " ▾");
            }
            Toast.makeText(this, "Qualidade definida: " + labels[which], Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void showAudioPickerDialog() {
        final String[] labels = {"Dublado (Português - Padrão)", "Legendado (Áudio Original)"};
        final String[] values = {"dub", "leg"};
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Preferência de Áudio");
        b.setItems(labels, (dialog, which) -> {
            AccountManager.getInstance(this).setAudioPreference(values[which]);
            if (btnPrefAudio != null) {
                btnPrefAudio.setText(labels[which] + " ▾");
            }
            Toast.makeText(this, "Preferência de áudio salva: " + labels[which], Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void confirmDeleteUserData() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🗑️ Deletar Dados Salvos")
                .setMessage("Deseja realmente apagar TODOS os seus dados salvos?\n\nIsso removerá todo o histórico de filmes e séries assistidos, favoritos salvos e progresso de reprodução, tanto deste dispositivo quanto da nuvem.\n\nSua conta continuará ativa.")
                .setPositiveButton("Sim, Apagar Dados", (dialog, which) -> {
                    Toast.makeText(this, "Apagando dados...", Toast.LENGTH_SHORT).show();
                    AccountManager.getInstance(this).deleteUserData((success, message) -> {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        modeChanged = true;
                        updateUi();
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmDeleteUserAccount() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("⚠️ Excluir Conta Permanentemente")
                .setMessage("ATENÇÃO: Deseja realmente EXCLUIR SUA CONTA permanentemente?\n\nEsta ação é irreversível. Todos os seus dados, histórico, favoritos, preferências e seu acesso serão apagados para sempre.")
                .setPositiveButton("Continuar", (dialog, which) -> {
                    AlertDialog.Builder confirmBuilder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                    confirmBuilder.setTitle("Confirmação de Segurança");
                    confirmBuilder.setMessage("Digite DELETAR no campo abaixo para confirmar a exclusão definitiva da sua conta:");
                    final EditText confirmInput = new EditText(this);
                    confirmInput.setHint("DELETAR");
                    confirmInput.setTextColor(Color.WHITE);
                    confirmBuilder.setView(confirmInput);
                    confirmBuilder.setPositiveButton("Excluir Minha Conta", (d2, w2) -> {
                        String typed = confirmInput.getText().toString().trim();
                        if ("DELETAR".equalsIgnoreCase(typed)) {
                            Toast.makeText(this, "Excluindo conta...", Toast.LENGTH_SHORT).show();
                            AccountManager.getInstance(this).deleteUserAccount((success, message) -> {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                                modeChanged = true;
                                updateUi();
                            });
                        } else {
                            Toast.makeText(this, "Exclusão cancelada. O texto digitado não confere.", Toast.LENGTH_SHORT).show();
                        }
                    });
                    confirmBuilder.setNegativeButton("Cancelar", null);
                    confirmBuilder.show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmSignOut() {
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
    }

    private void syncCloudData() {
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
    }

    private void clearAppCaches() {
        Toast.makeText(this, "Limpando caches locais...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Glide.get(getApplicationContext()).clearDiskCache();
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                try {
                    Glide.get(getApplicationContext()).clearMemory();
                } catch (Exception ignored) {}
                Toast.makeText(this, "Cache de imagens e catálogos liberado.", Toast.LENGTH_SHORT).show();
                updateStorageUsage();
            });
        }).start();
    }

    private void exportFavoritesJson() {
        try {
            List<com.andplay.app.model.FavoriteItem> favs = AccountManager.getInstance(this).getFavorites();
            String json = new Gson().toJson(favs);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Favoritos EPlay");
            shareIntent.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(shareIntent, "Exportar Favoritos (JSON)"));
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao exportar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmResetLocalData() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("⚠️ Redefinir Dados Locais")
                .setMessage("Deseja redefinir os dados salvos localmente neste aparelho? Sua conta e favoritos na nuvem permanecerão intactos.")
                .setPositiveButton("Redefinir", (dialog, which) -> {
                    AccountManager.getInstance(this).deleteUserData((success, message) -> {
                        Toast.makeText(this, "Dados locais redefinidos.", Toast.LENGTH_SHORT).show();
                        modeChanged = true;
                        updateUi();
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateStorageUsage() {
        new Thread(() -> {
            long totalBytes = 0;
            try {
                File cacheDir = getCacheDir();
                totalBytes += getDirSize(cacheDir);
                File extCache = getExternalCacheDir();
                if (extCache != null) totalBytes += getDirSize(extCache);
                File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
                if (prefsDir.exists()) totalBytes += getDirSize(prefsDir);
            } catch (Exception ignored) {}

            long finalBytes = totalBytes;
            runOnUiThread(() -> {
                if (txtStorageUsage != null) {
                    if (finalBytes > 1048576) {
                        txtStorageUsage.setText(String.format(Locale.getDefault(), "%.2f MB", finalBytes / (1024.0 * 1024.0)));
                    } else {
                        txtStorageUsage.setText(String.format(Locale.getDefault(), "%.1f KB", finalBytes / 1024.0));
                    }
                }
            });
        }).start();
    }

    private long getDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? getDirSize(f) : f.length();
            }
        }
        return size;
    }

    private void setupFocusAnimation(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.05f : 1.0f).scaleY(hasFocus ? 1.05f : 1.0f).setDuration(120).start();
        });
    }

    private void updateUi() {
        AccountManager am = AccountManager.getInstance(this);
        boolean signedIn = am.isSignedIn();

        // Avatar
        if (userAvatarDisplay != null) {
            userAvatarDisplay.setText(am.getAvatarEmoji());
        }

        if (signedIn) {
            if (userProfileName != null) userProfileName.setText(am.getDisplayName());
            if (userProfileBadge != null) userProfileBadge.setText("★ CONECTADO");
            if (userProfileEmail != null) userProfileEmail.setText(am.getEmail());

            long lastSync = am.getLastSyncTimestamp();
            if (userProfileSyncInfo != null) {
                if (lastSync > 0) {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
                    userProfileSyncInfo.setText("Última sincronização: " + sdf.format(new Date(lastSync)));
                } else {
                    userProfileSyncInfo.setText("Supabase Cloud • Sincronização em tempo real");
                }
            }

            if (userGuestActions != null) userGuestActions.setVisibility(View.GONE);
            if (userLoggedInActions != null) userLoggedInActions.setVisibility(View.VISIBLE);

            if (userAccountConnectedBlock != null) userAccountConnectedBlock.setVisibility(View.VISIBLE);
            if (userAccountAuthBlock != null) userAccountAuthBlock.setVisibility(View.GONE);

            if (userConnectedEmail != null) userConnectedEmail.setText(am.getEmail());
            if (userConnectedId != null) userConnectedId.setText(am.getUserId().isEmpty() ? "-" : am.getUserId());
            if (userConnectedSyncStatus != null) userConnectedSyncStatus.setText("Sincronizado");
        } else {
            if (userProfileName != null) userProfileName.setText("Usuário Convidado");
            if (userProfileBadge != null) userProfileBadge.setText("MODO OFFLINE");
            if (userProfileEmail != null) userProfileEmail.setText("Entre na sua conta para sincronizar favoritos e histórico com a versão Web");
            if (userProfileSyncInfo != null) userProfileSyncInfo.setText("Supabase Cloud • Dados locais apenas");

            if (userGuestActions != null) userGuestActions.setVisibility(View.VISIBLE);
            if (userLoggedInActions != null) userLoggedInActions.setVisibility(View.GONE);

            if (userAccountConnectedBlock != null) userAccountConnectedBlock.setVisibility(View.GONE);
            if (userAccountAuthBlock != null) userAccountAuthBlock.setVisibility(View.VISIBLE);
        }

        // 5 Métricas
        if (statWatchTimeVal != null) {
            statWatchTimeVal.setText(am.getWatchTimeFormatted());
        }
        if (statFavsVal != null) {
            statFavsVal.setText(String.valueOf(am.getFavorites().size()));
        }
        if (statMoviesVal != null) {
            statMoviesVal.setText(String.valueOf(getRecentMovieCount()));
        }
        if (statSeriesVal != null) {
            statSeriesVal.setText(String.valueOf(getRecentSeriesCount()));
        }
        if (statLiveVal != null) {
            statLiveVal.setText(String.valueOf(am.getLiveChannelCount()));
        }

        // Preferências
        String mode = am.getViewMode();
        boolean isCinema = AccountManager.VIEW_MODE_CINEMA.equals(mode);
        if (txtPrefCurrentMode != null) {
            txtPrefCurrentMode.setText(isCinema
                    ? "🎬 Modo Cinema (Filmes & Séries On-Demand)"
                    : "📺 Modo TV Ao Vivo (Central com PiP)");
        }

        if (switchSkipIntro != null) {
            switchSkipIntro.setChecked(am.isSkipIntroEnabled());
        }
        if (switchAutoNext != null) {
            switchAutoNext.setChecked(am.isAutoNextEnabled());
        }
        if (switchAdultContent != null) {
            switchAdultContent.setChecked(am.isAdultContentEnabled());
        }

        if (btnPrefQuality != null) {
            String q = am.getVideoQualityPreference();
            String qLabel = "4k".equals(q) ? "4K Ultra HD (Prioritário)"
                    : ("1080p".equals(q) ? "Full HD (1080p)"
                    : ("720p".equals(q) ? "HD (720p)" : "Automático (Padrão)"));
            btnPrefQuality.setText(qLabel + " ▾");
        }

        if (btnPrefAudio != null) {
            String a = am.getAudioPreference();
            String aLabel = "leg".equals(a) ? "Legendado (Original)" : "Dublado (Português - Padrão)";
            btnPrefAudio.setText(aLabel + " ▾");
        }

        updateStorageUsage();
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

    private void closeActivity() {
        Intent result = new Intent();
        result.putExtra(EXTRA_MODE_CHANGED, modeChanged);
        setResult(RESULT_OK, result);
        finish();
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
            closeActivity();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

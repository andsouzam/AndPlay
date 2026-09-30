package com.andplay.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.andplay.app.account.AccountManager;
import com.bumptech.glide.Glide;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UserActivity extends AppCompatActivity {

    public static final String EXTRA_MODE_CHANGED = "mode_changed";

    private TextView userAvatarDisplay;
    private TextView userProfileName;
    private TextView userProfileBadge;
    private TextView userProfileEmail;
    private TextView userProfileSyncInfo;

    private View userGuestActions;
    private View userLoggedInActions;
    private View btnUserLogin;
    private View btnUserRegister;
    private View btnUserSyncNow;
    private View btnUserSignOut;

    private LinearLayout cardModeTv;
    private TextView tagModeTvActive;
    private LinearLayout cardModeCinema;
    private TextView tagModeCinemaActive;

    private TextView statMoviesVal;
    private TextView statSeriesVal;
    private TextView statFavsVal;
    private View btnUserClearCache;

    private boolean modeChanged = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        initViews();
        setupListeners();
        updateUi();
    }

    private void initViews() {
        userAvatarDisplay = findViewById(R.id.userAvatarDisplay);
        userProfileName = findViewById(R.id.userProfileName);
        userProfileBadge = findViewById(R.id.userProfileBadge);
        userProfileEmail = findViewById(R.id.userProfileEmail);
        userProfileSyncInfo = findViewById(R.id.userProfileSyncInfo);

        userGuestActions = findViewById(R.id.userGuestActions);
        userLoggedInActions = findViewById(R.id.userLoggedInActions);
        btnUserLogin = findViewById(R.id.btnUserLogin);
        btnUserRegister = findViewById(R.id.btnUserRegister);
        btnUserSyncNow = findViewById(R.id.btnUserSyncNow);
        btnUserSignOut = findViewById(R.id.btnUserSignOut);

        cardModeTv = findViewById(R.id.cardModeTv);
        tagModeTvActive = findViewById(R.id.tagModeTvActive);
        cardModeCinema = findViewById(R.id.cardModeCinema);
        tagModeCinemaActive = findViewById(R.id.tagModeCinemaActive);

        statMoviesVal = findViewById(R.id.statMoviesVal);
        statSeriesVal = findViewById(R.id.statSeriesVal);
        statFavsVal = findViewById(R.id.statFavsVal);
        btnUserClearCache = findViewById(R.id.btnUserClearCache);
    }

    private void setupListeners() {
        // Modo TV
        cardModeTv.setOnClickListener(v -> selectViewMode(AccountManager.VIEW_MODE_TV));
        cardModeTv.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                selectViewMode(AccountManager.VIEW_MODE_TV);
                return true;
            }
            return false;
        });

        // Modo Cinema
        cardModeCinema.setOnClickListener(v -> selectViewMode(AccountManager.VIEW_MODE_CINEMA));
        cardModeCinema.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                selectViewMode(AccountManager.VIEW_MODE_CINEMA);
                return true;
            }
            return false;
        });

        // Animação de foco
        setupFocusAnimation(cardModeTv);
        setupFocusAnimation(cardModeCinema);
        setupFocusAnimation(btnUserLogin);
        setupFocusAnimation(btnUserRegister);
        setupFocusAnimation(btnUserSyncNow);
        setupFocusAnimation(btnUserSignOut);
        setupFocusAnimation(btnUserClearCache);

        // Ações de Conta
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
        tagModeTvActive.setVisibility(isCinema ? View.GONE : View.VISIBLE);
        tagModeCinemaActive.setVisibility(isCinema ? View.VISIBLE : View.GONE);
        cardModeTv.setSelected(!isCinema);
        cardModeCinema.setSelected(isCinema);

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

        builder.setNegativeButton("Cancelar", null);
        AlertDialog dialog = builder.create();
        dialog.show();
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

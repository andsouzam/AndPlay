package com.andplay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.andplay.app.account.AccountManager;
import com.andplay.app.view.DiagonalDividerView;
import com.andplay.app.view.DiagonalPaneView;

/**
 * Banner Fullscreen com divisão diagonal entre Modo Cinema e Modo TV.
 * Exibido no primeiro login/acesso e quando invocado pelo Perfil do Usuário.
 */
public class ModeSelectionActivity extends Activity {

    public static final String EXTRA_IS_FIRST_ACCESS = "is_first_access";
    public static final String EXTRA_SELECTED_MODE = "selected_mode";

    private DiagonalPaneView paneCinema;
    private DiagonalPaneView paneTv;
    private DiagonalDividerView diagonalDivider;

    private View cinemaLightOverlay;
    private View cinemaShadowScrim;
    private View cinemaContent;
    private TextView btnSelectCinema;

    private View tvLightOverlay;
    private View tvShadowScrim;
    private View tvContent;
    private TextView btnSelectTv;

    private String selectedMode = AccountManager.VIEW_MODE_CINEMA;
    private boolean isFirstAccess = false;
    private boolean isConfirming = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_selection);

        isFirstAccess = getIntent().getBooleanExtra(EXTRA_IS_FIRST_ACCESS, false);

        initViews();
        setupPanels();

        // Modo inicial: verifica a preferência atual
        String initialMode = AccountManager.getInstance(this).getViewMode();
        if (initialMode != null && !initialMode.isEmpty()) {
            selectedMode = initialMode;
        } else {
            selectedMode = AccountManager.VIEW_MODE_CINEMA;
        }

        selectMode(selectedMode, false);
    }

    private void initViews() {
        paneCinema = findViewById(R.id.paneCinema);
        paneTv = findViewById(R.id.paneTv);
        diagonalDivider = findViewById(R.id.diagonalDivider);

        cinemaLightOverlay = findViewById(R.id.cinemaLightOverlay);
        cinemaShadowScrim = findViewById(R.id.cinemaShadowScrim);
        cinemaContent = findViewById(R.id.cinemaContent);
        btnSelectCinema = findViewById(R.id.btnSelectCinema);

        tvLightOverlay = findViewById(R.id.tvLightOverlay);
        tvShadowScrim = findViewById(R.id.tvShadowScrim);
        tvContent = findViewById(R.id.tvContent);
        btnSelectTv = findViewById(R.id.btnSelectTv);
    }

    private void setupPanels() {
        paneCinema.setLeftSide(true);
        paneTv.setLeftSide(false);

        // Click no lado Cinema
        paneCinema.setOnClickListener(v -> {
            if (AccountManager.VIEW_MODE_CINEMA.equals(selectedMode)) {
                confirmSelection();
            } else {
                selectMode(AccountManager.VIEW_MODE_CINEMA, true);
            }
        });

        // Click no lado TV
        paneTv.setOnClickListener(v -> {
            if (AccountManager.VIEW_MODE_TV.equals(selectedMode)) {
                confirmSelection();
            } else {
                selectMode(AccountManager.VIEW_MODE_TV, true);
            }
        });

        if (btnSelectCinema != null) {
            btnSelectCinema.setOnClickListener(v -> {
                selectedMode = AccountManager.VIEW_MODE_CINEMA;
                confirmSelection();
            });
        }

        if (btnSelectTv != null) {
            btnSelectTv.setOnClickListener(v -> {
                selectedMode = AccountManager.VIEW_MODE_TV;
                confirmSelection();
            });
        }

        // Toque na tela verifica o ponto em relação ao corte diagonal
        View splitContainer = findViewById(R.id.splitPanelsContainer);
        if (splitContainer != null) {
            splitContainer.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float x = event.getX();
                    float y = event.getY();
                    if (paneCinema.containsPoint(x, y)) {
                        if (AccountManager.VIEW_MODE_CINEMA.equals(selectedMode)) {
                            confirmSelection();
                        } else {
                            selectMode(AccountManager.VIEW_MODE_CINEMA, true);
                        }
                        return true;
                    } else if (paneTv.containsPoint(x, y)) {
                        if (AccountManager.VIEW_MODE_TV.equals(selectedMode)) {
                            confirmSelection();
                        } else {
                            selectMode(AccountManager.VIEW_MODE_TV, true);
                        }
                        return true;
                    }
                }
                return false;
            });
        }
    }

    private void selectMode(String mode, boolean animate) {
        selectedMode = mode;
        boolean isCinema = AccountManager.VIEW_MODE_CINEMA.equals(mode);

        if (diagonalDivider != null) {
            int glowColor = isCinema ? Color.parseColor("#FFD700") : Color.parseColor("#00E5FF");
            diagonalDivider.setGlowColor(glowColor, animate);
        }

        long duration = animate ? 240 : 0;

        if (isCinema) {
            // CINEMA ATIVO: LUZ, REALCE DOURADO E DESTAQUE
            if (cinemaLightOverlay != null) {
                cinemaLightOverlay.animate().alpha(1.0f).setDuration(duration).start();
            }
            if (cinemaShadowScrim != null) {
                cinemaShadowScrim.animate().alpha(0.0f).setDuration(duration).start();
            }
            if (cinemaContent != null) {
                cinemaContent.animate().alpha(1.0f).scaleX(1.03f).scaleY(1.03f).setDuration(duration).start();
            }
            if (btnSelectCinema != null) {
                btnSelectCinema.setText("▶ ATIVAR MODO CINEMA");
                btnSelectCinema.setBackgroundResource(R.drawable.badge_gold);
                btnSelectCinema.setTextColor(Color.BLACK);
            }

            // TV INATIVO: SOMBRA ESCURA, DESLIGANDO
            if (tvLightOverlay != null) {
                tvLightOverlay.animate().alpha(0.0f).setDuration(duration).start();
            }
            if (tvShadowScrim != null) {
                tvShadowScrim.animate().alpha(0.85f).setDuration(duration).start();
            }
            if (tvContent != null) {
                tvContent.animate().alpha(0.32f).scaleX(0.97f).scaleY(0.97f).setDuration(duration).start();
            }
            if (btnSelectTv != null) {
                btnSelectTv.setText("SELECIONAR MODO TV");
                btnSelectTv.setBackgroundResource(R.drawable.pill_focus_bg);
                btnSelectTv.setTextColor(Color.WHITE);
            }

            paneCinema.requestFocus();
        } else {
            // TV ATIVO: LUZ, REALCE AZUL ELÉTRICO E DESTAQUE
            if (tvLightOverlay != null) {
                tvLightOverlay.animate().alpha(1.0f).setDuration(duration).start();
            }
            if (tvShadowScrim != null) {
                tvShadowScrim.animate().alpha(0.0f).setDuration(duration).start();
            }
            if (tvContent != null) {
                tvContent.animate().alpha(1.0f).scaleX(1.03f).scaleY(1.03f).setDuration(duration).start();
            }
            if (btnSelectTv != null) {
                btnSelectTv.setText("▶ ATIVAR MODO TV");
                btnSelectTv.setBackgroundResource(R.drawable.badge_blue);
                btnSelectTv.setTextColor(Color.BLACK);
            }

            // CINEMA INATIVO: SOMBRA ESCURA, DESLIGANDO
            if (cinemaLightOverlay != null) {
                cinemaLightOverlay.animate().alpha(0.0f).setDuration(duration).start();
            }
            if (cinemaShadowScrim != null) {
                cinemaShadowScrim.animate().alpha(0.85f).setDuration(duration).start();
            }
            if (cinemaContent != null) {
                cinemaContent.animate().alpha(0.32f).scaleX(0.97f).scaleY(0.97f).setDuration(duration).start();
            }
            if (btnSelectCinema != null) {
                btnSelectCinema.setText("SELECIONAR MODO CINEMA");
                btnSelectCinema.setBackgroundResource(R.drawable.pill_focus_bg);
                btnSelectCinema.setTextColor(Color.WHITE);
            }

            paneTv.requestFocus();
        }
    }

    private void confirmSelection() {
        if (isConfirming) return;
        isConfirming = true;

        // Salva a escolha imediatamente sem exigir diálogo extra de confirmação
        AccountManager.getInstance(this).setViewMode(selectedMode);
        AccountManager.getInstance(this).setHasChosenInitialMode(true);

        View activeContent = AccountManager.VIEW_MODE_CINEMA.equals(selectedMode) ? cinemaContent : tvContent;
        if (activeContent != null) {
            activeContent.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction(() -> {
                Intent result = new Intent();
                result.putExtra(EXTRA_SELECTED_MODE, selectedMode);
                setResult(RESULT_OK, result);
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }).start();
        } else {
            Intent result = new Intent();
            result.putExtra(EXTRA_SELECTED_MODE, selectedMode);
            setResult(RESULT_OK, result);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!AccountManager.VIEW_MODE_CINEMA.equals(selectedMode)) {
                selectMode(AccountManager.VIEW_MODE_CINEMA, true);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!AccountManager.VIEW_MODE_TV.equals(selectedMode)) {
                selectMode(AccountManager.VIEW_MODE_TV, true);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            confirmSelection();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFirstAccess) {
                // No primeiro acesso, voltar confirma o modo atualmente focado
                confirmSelection();
                return true;
            } else {
                setResult(RESULT_CANCELED);
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}

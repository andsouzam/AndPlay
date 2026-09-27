package com.andplay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.andplay.app.api.ApiClient;
import com.andplay.app.model.SportsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hub de esportes — tela completa com sidebar de ligas e calendário/tabela.
 *
 * NAVEGAÇÃO D-PAD:
 *  ↑/↓  no conteúdo  → scrollar itens
 *  ←    no conteúdo  → vai para sub-tab anterior (ou liga anterior) 
 *  →    no conteúdo  → vai para próximo sub-tab (ou próxima liga)
 *  ↑/↓  na sidebar   → muda liga
 *  →    na sidebar   → entra no conteúdo
 *  BACK               → fecha
 *
 * MODELO DE PÁGINAS (virtual):
 *   pageIndex = leagueIdx * 2 + (isCal ? 0 : 1)
 *   ← decrece pageIndex; → incrementa pageIndex
 */
public class SportsHubActivity extends Activity {

    // ── Views ────────────────────────────────────────────────────────────────
    private RecyclerView leagueTabsRv;
    private RecyclerView hubContentRv;
    private TextView subTabSchedule;
    private TextView subTabStandings;
    private TextView hubLeagueName;
    private ProgressBar hubProgress;
    private TextView hubEmptyMsg;

    // ── Adapters ─────────────────────────────────────────────────────────────
    private LeagueTabAdapter leagueTabAdapter;
    private ScheduleAdapter  scheduleAdapter;
    private StandingsAdapter standingsAdapter;

    // ── State ────────────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<SportsEvent> liveEvents;
    private int  currentLeagueIdx    = 0;
    private boolean isScheduleTabActive = true;

    // Cache por liga para não re-buscar ao navegar entre tabs
    private final List<ApiClient.ScheduleDay>  currentSchedule  = new ArrayList<>();
    private final List<ApiClient.StandingEntry> currentStandings = new ArrayList<>();

    // Índice da liga cujos dados estão em cache
    private int cachedLeagueIdx = -1;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sports_hub);

        // Receber live events do MainActivity para EPG match
        try {
            //noinspection unchecked
            liveEvents = (List<SportsEvent>) getIntent().getSerializableExtra("live_events");
        } catch (Exception ignored) {}

        // Views
        leagueTabsRv    = findViewById(R.id.leagueTabsRv);
        hubContentRv    = findViewById(R.id.hubContentRv);
        subTabSchedule  = findViewById(R.id.subTabSchedule);
        subTabStandings = findViewById(R.id.subTabStandings);
        hubLeagueName   = findViewById(R.id.hubLeagueName);
        hubProgress     = findViewById(R.id.hubProgress);
        hubEmptyMsg     = findViewById(R.id.hubEmptyMsg);

        setupSidebar();
        setupSubTabs();
        setupContentRv();

        // Carregar primeira liga
        loadLeagueData(0, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private void setupSidebar() {
        leagueTabsRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        leagueTabAdapter = new LeagueTabAdapter();
        leagueTabsRv.setAdapter(leagueTabAdapter);

        // D-pad DIREITA na sidebar → foca conteúdo
        leagueTabsRv.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                hubContentRv.requestFocus();
                return true;
            }
            return false;
        });
    }

    private void setupSubTabs() {
        subTabSchedule.setOnClickListener(v -> switchToPage(currentLeagueIdx, true));
        subTabStandings.setOnClickListener(v -> switchToPage(currentLeagueIdx, false));

        subTabSchedule.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                switchToPage(currentLeagueIdx, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchToPage(currentLeagueIdx, false);
                subTabStandings.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                leagueTabsRv.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hubContentRv.requestFocus();
                return true;
            }
            return false;
        });

        subTabStandings.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                switchToPage(currentLeagueIdx, false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Avançar para próxima liga
                navigateNextPage();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchToPage(currentLeagueIdx, true);
                subTabSchedule.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hubContentRv.requestFocus();
                return true;
            }
            return false;
        });

        subTabSchedule.setOnFocusChangeListener((v, f) -> animateTab(v, f));
        subTabStandings.setOnFocusChangeListener((v, f) -> animateTab(v, f));
    }

    private void setupContentRv() {
        hubContentRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        scheduleAdapter  = new ScheduleAdapter();
        standingsAdapter = new StandingsAdapter();
        hubContentRv.setAdapter(scheduleAdapter);

        // D-pad ESQUERDA/DIREITA no conteúdo → navega entre páginas
        hubContentRv.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                navigatePrevPage();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                navigateNextPage();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                // Se no primeiro item, sobe para sub-tabs
                LinearLayoutManager lm = (LinearLayoutManager) hubContentRv.getLayoutManager();
                if (lm != null && lm.findFirstCompletelyVisibleItemPosition() == 0) {
                    subTabSchedule.requestFocus();
                    return true;
                }
            }
            return false;
        });
    }

    // ── Navegação de páginas ─────────────────────────────────────────────────

    /**
     * Mapa virtual de páginas:
     *   pageIndex = leagueIdx * 2 + (isCal ? 0 : 1)
     */
    private void navigateNextPage() {
        int pageIdx = currentLeagueIdx * 2 + (isScheduleTabActive ? 0 : 1);
        int maxPage = ApiClient.HUB_LEAGUES.length * 2 - 1;
        if (pageIdx >= maxPage) return;
        pageIdx++;
        int newLeague = pageIdx / 2;
        boolean newCal = (pageIdx % 2 == 0);
        switchToPage(newLeague, newCal);
    }

    private void navigatePrevPage() {
        int pageIdx = currentLeagueIdx * 2 + (isScheduleTabActive ? 0 : 1);
        if (pageIdx <= 0) return;
        pageIdx--;
        int newLeague = pageIdx / 2;
        boolean newCal = (pageIdx % 2 == 0);
        switchToPage(newLeague, newCal);
    }

    /**
     * Troca para a página especificada: liga + tab.
     * Se a liga mudou → busca dados novos.
     * Se só o tab mudou → troca o adapter.
     */
    private void switchToPage(int leagueIdx, boolean schedulTab) {
        boolean leagueChanged = (leagueIdx != currentLeagueIdx);
        currentLeagueIdx    = leagueIdx;
        isScheduleTabActive = schedulTab;

        // Atualiza sidebar highlight
        leagueTabAdapter.notifyDataSetChanged();
        // Scroll sidebar para item visível
        leagueTabsRv.scrollToPosition(currentLeagueIdx);

        updateSubTabsUI();
        updateLeagueNameHeader();

        if (leagueChanged || cachedLeagueIdx != leagueIdx) {
            loadLeagueData(leagueIdx, schedulTab);
        } else {
            // Só trocar adapter sem re-buscar
            applyCurrentTab();
        }
    }

    private void updateLeagueNameHeader() {
        if (currentLeagueIdx < ApiClient.HUB_LEAGUES.length) {
            String[] l = ApiClient.HUB_LEAGUES[currentLeagueIdx];
            hubLeagueName.setText(l[2] + "  " + l[1]);
        }
    }

    // ── Carregamento de dados ────────────────────────────────────────────────

    private void loadLeagueData(int leagueIdx, boolean scheduleTab) {
        String espnSlug = ApiClient.HUB_LEAGUES[leagueIdx][0];

        hubProgress.setVisibility(View.VISIBLE);
        hubContentRv.setVisibility(View.GONE);
        hubEmptyMsg.setVisibility(View.GONE);

        executor.execute(() -> {
            List<ApiClient.ScheduleDay>   schedule  = ApiClient.getFullLeagueSchedule(espnSlug, liveEvents);
            List<ApiClient.StandingEntry> standings = ApiClient.getStandingsBySlug(espnSlug);

            runOnUiThread(() -> {
                // Verifica se ainda é a liga que o user quer
                if (currentLeagueIdx != leagueIdx) return;

                cachedLeagueIdx = leagueIdx;
                currentSchedule.clear();
                if (schedule != null)  currentSchedule.addAll(schedule);
                currentStandings.clear();
                if (standings != null) currentStandings.addAll(standings);

                hubProgress.setVisibility(View.GONE);
                hubContentRv.setVisibility(View.VISIBLE);
                applyCurrentTab();
            });
        });
    }

    private void applyCurrentTab() {
        if (isScheduleTabActive) {
            scheduleAdapter.updateData(currentSchedule);
            hubContentRv.setAdapter(scheduleAdapter);
            updateEmptyState(currentSchedule.isEmpty());
            scrollToToday();
        } else {
            hubContentRv.setAdapter(standingsAdapter);
            standingsAdapter.notifyDataSetChanged();
            updateEmptyState(currentStandings.isEmpty());
            hubContentRv.scrollToPosition(0);
        }
    }

    private void scrollToToday() {
        if (currentSchedule.isEmpty()) return;
        int todayPos = scheduleAdapter.findTodayPosition();
        if (todayPos >= 0) hubContentRv.scrollToPosition(todayPos);
    }

    private void updateEmptyState(boolean empty) {
        hubEmptyMsg.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private void updateSubTabsUI() {
        // Aba ativa: cor azul, underline visual via background
        int activeColor   = Color.parseColor("#4FC3F7");
        int inactiveColor = Color.parseColor("#70FFFFFF");
        int activeBg      = Color.parseColor("#1A4FC3F7");

        subTabSchedule.setTextColor(isScheduleTabActive  ? activeColor : inactiveColor);
        subTabStandings.setTextColor(!isScheduleTabActive ? activeColor : inactiveColor);
        subTabSchedule.setBackgroundColor(isScheduleTabActive  ? activeBg : Color.TRANSPARENT);
        subTabStandings.setBackgroundColor(!isScheduleTabActive ? activeBg : Color.TRANSPARENT);
        subTabSchedule.setTypeface(null, isScheduleTabActive  ? Typeface.BOLD : Typeface.NORMAL);
        subTabStandings.setTypeface(null, !isScheduleTabActive ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void animateTab(View v, boolean focus) {
        v.animate().scaleX(focus ? 1.06f : 1f).scaleY(focus ? 1.06f : 1f).setDuration(100).start();
        if (focus) v.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        else       updateSubTabsUI(); // restaura cor de acordo com estado ativo
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LEAGUE TAB ADAPTER (sidebar)
    // ═════════════════════════════════════════════════════════════════════════

    private class LeagueTabAdapter extends RecyclerView.Adapter<LeagueTabAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(View v) {
                super(v);
                tv = (TextView) v;
                v.setFocusable(true);
                v.setOnClickListener(v1 -> switchToPage(getAdapterPosition(), true));
                v.setOnKeyListener((v12, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_UP
                            && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        switchToPage(getAdapterPosition(), true);
                        return true;
                    }
                    return false;
                });
                v.setOnFocusChangeListener((v13, hasFocus) -> {
                    v13.animate().scaleX(hasFocus ? 1.04f : 1f).scaleY(hasFocus ? 1.04f : 1f).setDuration(100).start();
                    v13.setBackgroundColor(hasFocus ? Color.parseColor("#1A4FC3F7") : Color.TRANSPARENT);
                });
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_league_tab, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String[] l = ApiClient.HUB_LEAGUES[position];
            boolean active = (position == currentLeagueIdx);

            holder.tv.setText(l[2] + " " + l[1]);

            if (active) {
                // Item selecionado: fundo azul suave + texto mais brilhante + traço à esquerda
                holder.tv.setBackgroundColor(Color.parseColor("#1E4FC3F7"));
                holder.tv.setTextColor(Color.parseColor("#4FC3F7"));
                holder.tv.setTypeface(null, Typeface.BOLD);
                holder.tv.setPadding(dpToPx(10), holder.tv.getPaddingTop(),
                        holder.tv.getPaddingEnd(), holder.tv.getPaddingBottom());
            } else {
                holder.tv.setBackgroundColor(Color.TRANSPARENT);
                holder.tv.setTextColor(Color.parseColor("#AAFFFFFF"));
                holder.tv.setTypeface(null, Typeface.NORMAL);
                holder.tv.setPadding(dpToPx(14), holder.tv.getPaddingTop(),
                        holder.tv.getPaddingEnd(), holder.tv.getPaddingBottom());
            }
        }

        @Override public int getItemCount() { return ApiClient.HUB_LEAGUES.length; }

        private int dpToPx(int dp) {
            return (int)(dp * getResources().getDisplayMetrics().density);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCHEDULE ADAPTER (calendário)
    // ═════════════════════════════════════════════════════════════════════════

    private class ScheduleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_MATCH  = 1;

        private final List<Object> items = new ArrayList<>();

        void updateData(List<ApiClient.ScheduleDay> days) {
            items.clear();
            for (ApiClient.ScheduleDay day : days) {
                items.add(day);
                items.addAll(day.matches);
            }
            notifyDataSetChanged();
        }

        int findTodayPosition() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof ApiClient.ScheduleDay) {
                    if (((ApiClient.ScheduleDay) items.get(i)).isToday) return i;
                }
            }
            return 0; // fallback: início
        }

        @Override public int getItemViewType(int pos) {
            return items.get(pos) instanceof ApiClient.ScheduleDay ? TYPE_HEADER : TYPE_MATCH;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(makeHeaderView(parent));
            }
            return new MatchVH(makeMatchView(parent));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (getItemViewType(pos) == TYPE_HEADER) {
                ApiClient.ScheduleDay day = (ApiClient.ScheduleDay) items.get(pos);
                ((HeaderVH) holder).bind(day);
            } else {
                ApiClient.RoundMatch rm = (ApiClient.RoundMatch) items.get(pos);
                ((MatchVH) holder).bind(rm);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        // ── Header view ──────────────────────────────────────────────────────
        private View makeHeaderView(ViewGroup parent) {
            LinearLayout ll = new LinearLayout(parent.getContext());
            ll.setOrientation(LinearLayout.HORIZONTAL);
            ll.setGravity(Gravity.CENTER_VERTICAL);
            ll.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.setPadding(px(14), px(7), px(14), px(7));

            // Traço decorativo
            View dash = new View(parent.getContext());
            dash.setLayoutParams(new LinearLayout.LayoutParams(px(3), px(14)));
            ((LinearLayout.LayoutParams) dash.getLayoutParams()).setMarginEnd(px(8));
            dash.setBackgroundColor(Color.parseColor("#4FC3F7"));

            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tv.setTextColor(Color.parseColor("#4FC3F7"));
            tv.setTextSize(11);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setLetterSpacing(0.1f);
            tv.setTag("date_tv");

            ll.addView(dash);
            ll.addView(tv);
            return ll;
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tv;
            HeaderVH(View v) {
                super(v);
                tv = v.findViewWithTag("date_tv");
            }
            void bind(ApiClient.ScheduleDay day) {
                String label = day.dateLabel;
                if (day.isToday) label = "● " + label.toUpperCase();
                tv.setText(label.toUpperCase());
                // Fundo levemente mais forte para o dia de hoje
                itemView.setBackgroundColor(day.isToday
                        ? Color.parseColor("#0E4FC3F7")
                        : Color.parseColor("#06FFFFFF"));
            }
        }

        // ── Match view ───────────────────────────────────────────────────────
        private View makeMatchView(ViewGroup parent) {
            LinearLayout root = new LinearLayout(parent.getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.setPadding(px(14), px(9), px(14), px(9));
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);

            // Linha superior: [home] [score box] [away]
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Ícone transmissão (oculto por default)
            TextView tvIcon = new TextView(parent.getContext());
            tvIcon.setTextSize(11);
            tvIcon.setTextColor(Color.parseColor("#4FC3F7"));
            tvIcon.setTag("icon");
            tvIcon.setVisibility(View.GONE);
            LinearLayout.LayoutParams lpIcon = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpIcon.setMarginEnd(px(4));
            tvIcon.setLayoutParams(lpIcon);

            // Mandante
            TextView home = new TextView(parent.getContext());
            home.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            home.setGravity(Gravity.END);
            home.setTextColor(Color.WHITE);
            home.setTextSize(13);
            home.setSingleLine(true);
            home.setEllipsize(android.text.TextUtils.TruncateAt.END);
            home.setTag("home");

            // Box do placar
            TextView score = new TextView(parent.getContext());
            LinearLayout.LayoutParams lpScore = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpScore.setMarginStart(px(10));
            lpScore.setMarginEnd(px(10));
            score.setLayoutParams(lpScore);
            score.setGravity(Gravity.CENTER);
            score.setTextSize(13);
            score.setTypeface(null, Typeface.BOLD);
            score.setMinWidth(px(52));
            score.setPadding(px(6), px(2), px(6), px(2));
            score.setTag("score");

            // Visitante
            TextView away = new TextView(parent.getContext());
            away.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            away.setGravity(Gravity.START);
            away.setTextColor(Color.WHITE);
            away.setTextSize(13);
            away.setSingleLine(true);
            away.setEllipsize(android.text.TextUtils.TruncateAt.END);
            away.setTag("away");

            row.addView(tvIcon);
            row.addView(home);
            row.addView(score);
            row.addView(away);

            // Status/horário (centralizado)
            TextView status = new TextView(parent.getContext());
            status.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            status.setGravity(Gravity.CENTER);
            status.setTextSize(10);
            status.setTextColor(Color.parseColor("#80FFFFFF"));
            status.setPadding(0, px(3), 0, 0);
            status.setTag("status");

            root.addView(row);
            root.addView(status);

            // Focus visual
            root.setOnFocusChangeListener((v, f) -> {
                v.setBackgroundColor(f ? Color.parseColor("#22FFFFFF") : Color.TRANSPARENT);
                v.animate().scaleX(f ? 1.01f : 1f).scaleY(f ? 1.01f : 1f).setDuration(80).start();
            });

            return root;
        }

        class MatchVH extends RecyclerView.ViewHolder {
            TextView home, score, away, status, icon;
            MatchVH(View v) {
                super(v);
                home   = v.findViewWithTag("home");
                score  = v.findViewWithTag("score");
                away   = v.findViewWithTag("away");
                status = v.findViewWithTag("status");
                icon   = v.findViewWithTag("icon");
            }
            void bind(ApiClient.RoundMatch rm) {
                home.setText(rm.homeTeam != null ? rm.homeTeam : "");
                away.setText(rm.awayTeam != null ? rm.awayTeam : "");
                score.setText(rm.score != null ? rm.score : "vs");
                status.setText(rm.statusLabel != null ? rm.statusLabel : "");

                // Cor do score de acordo com estado
                if ("in".equalsIgnoreCase(rm.state)) {
                    score.setTextColor(Color.parseColor("#00E676"));
                    score.setBackgroundColor(Color.parseColor("#1200E676"));
                } else if ("post".equalsIgnoreCase(rm.state)) {
                    score.setTextColor(Color.parseColor("#BFBFBF"));
                    score.setBackgroundColor(Color.parseColor("#0AFFFFFF"));
                } else {
                    score.setTextColor(Color.parseColor("#FFC107"));
                    score.setBackgroundColor(Color.parseColor("#0AFFC107"));
                }

                // Ícone de transmissão
                if (rm.channelId != null && !rm.channelId.isEmpty()) {
                    icon.setText("📺");
                    icon.setVisibility(View.VISIBLE);
                } else {
                    icon.setVisibility(View.GONE);
                }

                // Click → lançar canal (só se tiver channelId)
                itemView.setOnClickListener(v -> handleMatchClick(rm));
                itemView.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_UP
                            && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        handleMatchClick(rm);
                        return true;
                    }
                    return false;
                });
            }
        }

        private void handleMatchClick(ApiClient.RoundMatch rm) {
            if (rm.channelId != null && !rm.channelId.isEmpty()) {
                Toast.makeText(SportsHubActivity.this,
                        "Abrindo transmissão…", Toast.LENGTH_SHORT).show();
                Intent result = new Intent();
                result.putExtra("launch_event_id", rm.channelId);
                setResult(RESULT_OK, result);
                finish();
            }
        }

        private int px(int dp) {
            return (int)(dp * getResources().getDisplayMetrics().density);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STANDINGS ADAPTER (tabela de classificação)
    // ═════════════════════════════════════════════════════════════════════════

    private class StandingsAdapter extends RecyclerView.Adapter<StandingsAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(makeRow(parent));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ApiClient.StandingEntry e = currentStandings.get(position);
            holder.pos.setText(String.valueOf(e.position));
            holder.name.setText(e.teamName != null ? e.teamName : "");
            holder.pts.setText(String.valueOf(e.points));
            holder.pj.setText(String.valueOf(e.played));
            holder.v.setText(String.valueOf(e.wins));
            holder.sg.setText(sgnStr(e.goalDiff));
            holder.gp.setText(String.valueOf(e.goalsFor));

            // Destaque para time assistido
            holder.itemView.setBackgroundColor(e.isHighlighted
                    ? Color.parseColor("#1A4FC3F7") : Color.TRANSPARENT);
        }

        @Override public int getItemCount() { return currentStandings.size(); }

        private String sgnStr(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

        private View makeRow(ViewGroup parent) {
            LinearLayout ll = new LinearLayout(parent.getContext());
            ll.setOrientation(LinearLayout.HORIZONTAL);
            ll.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.setPadding(px(14), px(10), px(14), px(10));
            ll.setGravity(Gravity.CENTER_VERTICAL);
            ll.setFocusable(true);
            ll.setFocusableInTouchMode(true);
            ll.setOnFocusChangeListener((v, f) ->
                    v.setBackgroundColor(f ? Color.parseColor("#22FFFFFF") : Color.TRANSPARENT));

            ll.addView(col(parent, 26, false, "#70FFFFFF", 10)); // pos
            ll.addView(col(parent,  0, true,  "#FFFFFF",   13)); // name
            ll.addView(col(parent, 30, false, "#FFD700",   13)); // pts bold
            ll.addView(col(parent, 26, false, "#AAAAAA",   11)); // pj
            ll.addView(col(parent, 24, false, "#AAAAAA",   11)); // v
            ll.addView(col(parent, 28, false, "#AAAAAA",   11)); // sg
            ll.addView(col(parent, 24, false, "#AAAAAA",   11)); // gp

            // pts bold
            ((TextView) ll.getChildAt(2)).setTypeface(null, Typeface.BOLD);

            return ll;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView pos, name, pts, pj, v, sg, gp;
            VH(View itemView) {
                super(itemView);
                LinearLayout ll = (LinearLayout) itemView;
                pos  = (TextView) ll.getChildAt(0);
                name = (TextView) ll.getChildAt(1);
                pts  = (TextView) ll.getChildAt(2);
                pj   = (TextView) ll.getChildAt(3);
                v    = (TextView) ll.getChildAt(4);
                sg   = (TextView) ll.getChildAt(5);
                gp   = (TextView) ll.getChildAt(6);
            }
        }

        private TextView col(ViewGroup parent, int widthDp, boolean weight, String hex, int textSp) {
            TextView tv = new TextView(parent.getContext());
            int w = widthDp == 0 ? 0 : px(widthDp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (weight) lp.weight = 1;
            tv.setLayoutParams(lp);
            tv.setTextColor(Color.parseColor(hex));
            tv.setTextSize(textSp);
            tv.setGravity(weight ? Gravity.START : Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return tv;
        }

        private int px(int dp) {
            return (int)(dp * getResources().getDisplayMetrics().density);
        }
    }
}

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
 * Hub de esportes — tela completa com sidebar lateral de ligas e painel de conteúdo.
 *
 * NAVEGAÇÃO D-PAD 2-COLUNAS (LATERAL):
 *  SIDEBAR (Esquerda):
 *    ↑/↓   → navega livremente entre as ligas (SEM recarregar a tela, sem perder foco)
 *    OK    → CONFIRMA a troca da liga selecionada
 *    →     → confirma a liga e entra nas sub-abas (Calendário / Tabela)
 *    BACK  → fecha a Activity
 *
 *  SUB-ABAS (Topo do conteúdo):
 *    ←     → da Tabela vai para Calendário; do Calendário volta para a sidebar
 *    →     → do Calendário vai para Tabela
 *    ↓     → desce para a lista de jogos ou classificação
 *    OK    → alterna a aba ativa
 *
 *  CONTEÚDO (Lista de jogos ou Classificação):
 *    ↑/↓   → sobe e desce normalmente item a item
 *    ↑     → apenas no PRIMEIRO item (topo) sobe para as sub-abas
 *    ←     → de QUALQUER item da lista, volta DIRETO para a sidebar na liga atual!
 *    BACK  → volta para a sidebar
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
    private LinearLayout standingsHeaderRow;

    // ── Adapters ─────────────────────────────────────────────────────────────
    private LeagueTabAdapter leagueTabAdapter;
    private ScheduleAdapter  scheduleAdapter;
    private StandingsAdapter standingsAdapter;

    // ── State ────────────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private List<SportsEvent> liveEvents;
    private int currentLeagueIdx = 0;
    private boolean isScheduleTabActive = true;

    // Cache da liga ativa para não re-buscar ao alternar entre abas
    private final List<ApiClient.ScheduleDay>  currentSchedule  = new ArrayList<>();
    private final List<ApiClient.StandingEntry> currentStandings = new ArrayList<>();
    private int cachedLeagueIdx = -1;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sports_hub);

        try {
            //noinspection unchecked
            liveEvents = (List<SportsEvent>) getIntent().getSerializableExtra("live_events");
        } catch (Exception ignored) {}

        leagueTabsRv       = findViewById(R.id.leagueTabsRv);
        hubContentRv       = findViewById(R.id.hubContentRv);
        subTabSchedule     = findViewById(R.id.subTabSchedule);
        subTabStandings    = findViewById(R.id.subTabStandings);
        hubLeagueName      = findViewById(R.id.hubLeagueName);
        hubProgress        = findViewById(R.id.hubProgress);
        hubEmptyMsg        = findViewById(R.id.hubEmptyMsg);
        standingsHeaderRow = findViewById(R.id.standingsHeaderRow);

        setupSidebar();
        setupSubTabs();
        setupContentRv();

        // Carregar primeira liga
        commitLeagueSelection(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Intercepta navegação D-pad no nível da Activity para garantir fluxo consistente
     * entre a Sidebar vertical e o Painel de Conteúdo.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            View focused = getCurrentFocus();

            boolean inSidebar = (focused != null) && (focused == leagueTabsRv
                    || (leagueTabsRv != null && leagueTabsRv.findContainingItemView(focused) != null));
            boolean inSubTabs = (focused == subTabSchedule || focused == subTabStandings);
            boolean inContent = (focused != null) && (focused == hubContentRv
                    || (hubContentRv != null && hubContentRv.findContainingItemView(focused) != null));

            // BACK: do conteúdo ou abas volta para sidebar; da sidebar fecha
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (!inSidebar) {
                    focusCurrentLeagueInSidebar();
                    return true;
                }
                finish();
                return true;
            }

            // ── CENÁRIO A: FOCO NA SIDEBAR ──
            if (inSidebar) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // Confirma a troca de liga com ENTER (sem sair da sidebar)
                    int pos = getFocusedSidebarPosition(focused);
                    if (pos >= 0) {
                        commitLeagueSelection(pos);
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Confirma a liga se mudou e entra nas sub-abas
                    int pos = getFocusedSidebarPosition(focused);
                    if (pos >= 0 && pos != currentLeagueIdx) {
                        commitLeagueSelection(pos);
                    }
                    focusActiveSubTab();
                    return true;
                }
                // UP / DOWN navegam livremente entre as ligas sem recarregar view nem resetar foco
                return super.dispatchKeyEvent(event);
            }

            // ── CENÁRIO B: FOCO NAS SUB-ABAS (Calendário / Tabela) ──
            if (inSubTabs) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (focused == subTabStandings) {
                        switchSubTab(true);
                        subTabSchedule.requestFocus();
                        return true;
                    } else {
                        focusCurrentLeagueInSidebar();
                        return true;
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (focused == subTabSchedule) {
                        switchSubTab(false);
                        subTabStandings.requestFocus();
                        return true;
                    }
                    return true; // Parede direita
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstContentItem();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    switchSubTab(focused == subTabSchedule);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            // ── CENÁRIO C: FOCO NO CONTEÚDO (Lista de Jogos / Tabela) ──
            if (inContent) {
                // DPAD_LEFT: de QUALQUER item da lista, volta direto para a sidebar na liga atual!
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusCurrentLeagueInSidebar();
                    return true;
                }
                // DPAD_RIGHT: consome para não perder foco
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return true;
                }
                // DPAD_UP: apenas quando o foco estiver no PRIMEIRO item (topo da lista) sobe para as sub-abas!
                // Em qualquer outro item, deixa o RecyclerView subir item a item normalmente!
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    View containing = hubContentRv.findContainingItemView(focused);
                    if (containing != null) {
                        int pos = hubContentRv.getChildAdapterPosition(containing);
                        if (pos <= 0) {
                            focusActiveSubTab();
                            return true;
                        }
                    }
                    // Se pos > 0, NÃO intercepta: o RecyclerView sobe item a item normalmente
                    return super.dispatchKeyEvent(event);
                }
                return super.dispatchKeyEvent(event);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int getFocusedSidebarPosition(View focused) {
        if (leagueTabsRv == null || focused == null) return -1;
        View child = leagueTabsRv.findContainingItemView(focused);
        if (child != null) {
            return leagueTabsRv.getChildAdapterPosition(child);
        }
        return -1;
    }

    private void focusActiveSubTab() {
        if (isScheduleTabActive && subTabSchedule != null) {
            subTabSchedule.requestFocus();
        } else if (subTabStandings != null) {
            subTabStandings.requestFocus();
        }
    }

    private void focusFirstContentItem() {
        if (hubContentRv != null && hubContentRv.getChildCount() > 0) {
            hubContentRv.requestFocus();
            View first = hubContentRv.getChildAt(0);
            if (first != null) first.requestFocus();
        }
    }

    private void focusCurrentLeagueInSidebar() {
        if (leagueTabsRv == null) return;
        leagueTabsRv.scrollToPosition(currentLeagueIdx);
        leagueTabsRv.postDelayed(() -> {
            RecyclerView.ViewHolder vh = leagueTabsRv.findViewHolderForAdapterPosition(currentLeagueIdx);
            if (vh != null && vh.itemView != null) {
                vh.itemView.requestFocus();
            } else {
                leagueTabsRv.requestFocus();
            }
        }, 50);
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private void setupSidebar() {
        leagueTabsRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        leagueTabAdapter = new LeagueTabAdapter();
        leagueTabsRv.setAdapter(leagueTabAdapter);
    }

    private void setupSubTabs() {
        subTabSchedule.setOnClickListener(v -> switchSubTab(true));
        subTabStandings.setOnClickListener(v -> switchSubTab(false));
        subTabSchedule.setOnFocusChangeListener((v, f) -> animateTab(v, f));
        subTabStandings.setOnFocusChangeListener((v, f) -> animateTab(v, f));
    }

    private void setupContentRv() {
        hubContentRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        scheduleAdapter  = new ScheduleAdapter();
        standingsAdapter = new StandingsAdapter();
        hubContentRv.setAdapter(scheduleAdapter);
    }

    // ── Alternância de Liga e Sub-Abas ──────────────────────────────────────

    /**
     * Confirma a troca de liga selecionada (acionado pelo ENTER ou DPAD DIREITO na sidebar).
     * Usa notifyItemChanged apenas nos 2 itens afetados para nunca resetar o foco da sidebar.
     */
    private void commitLeagueSelection(int newLeagueIdx) {
        if (newLeagueIdx < 0 || newLeagueIdx >= ApiClient.HUB_LEAGUES.length) return;
        int oldIdx = currentLeagueIdx;
        currentLeagueIdx = newLeagueIdx;

        // Atualiza apenas os dois itens afetados para preservar o foco intacto
        if (leagueTabAdapter != null) {
            leagueTabAdapter.notifyItemChanged(oldIdx);
            leagueTabAdapter.notifyItemChanged(currentLeagueIdx);
        }

        updateSubTabsUI();
        updateLeagueNameHeader();

        if (cachedLeagueIdx != currentLeagueIdx) {
            loadLeagueData(currentLeagueIdx);
        } else {
            applyCurrentTab();
        }
    }

    private void switchSubTab(boolean schedule) {
        if (isScheduleTabActive == schedule) return;
        isScheduleTabActive = schedule;
        updateSubTabsUI();
        applyCurrentTab();
    }

    private void updateLeagueNameHeader() {
        if (currentLeagueIdx >= 0 && currentLeagueIdx < ApiClient.HUB_LEAGUES.length) {
            String[] l = ApiClient.HUB_LEAGUES[currentLeagueIdx];
            hubLeagueName.setText(l[2] + "  " + l[1]);
        }
    }

    // ── Carregamento de dados ────────────────────────────────────────────────

    private void loadLeagueData(int leagueIdx) {
        String espnSlug = ApiClient.HUB_LEAGUES[leagueIdx][0];

        hubProgress.setVisibility(View.VISIBLE);
        hubContentRv.setVisibility(View.GONE);
        hubEmptyMsg.setVisibility(View.GONE);
        if (standingsHeaderRow != null) standingsHeaderRow.setVisibility(View.GONE);

        executor.execute(() -> {
            List<ApiClient.ScheduleDay>   schedule  = ApiClient.getFullLeagueSchedule(espnSlug, liveEvents);
            List<ApiClient.StandingEntry> standings = ApiClient.getStandingsBySlug(espnSlug);

            runOnUiThread(() -> {
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
            if (standingsHeaderRow != null) standingsHeaderRow.setVisibility(View.GONE);
            scheduleAdapter.updateData(currentSchedule);
            hubContentRv.setAdapter(scheduleAdapter);
            updateEmptyState(currentSchedule.isEmpty());
            scrollToInitialPosition();
        } else {
            boolean hasStandings = !currentStandings.isEmpty();
            if (standingsHeaderRow != null) {
                standingsHeaderRow.setVisibility(hasStandings ? View.VISIBLE : View.GONE);
            }
            hubContentRv.setAdapter(standingsAdapter);
            standingsAdapter.notifyDataSetChanged();
            updateEmptyState(!hasStandings);
            hubContentRv.scrollToPosition(0);
        }
    }

    /**
     * Rola para o primeiro evento ao vivo; se não houver ao vivo, rola para o primeiro
     * evento que ainda não começou. Jamais para em uma data com todos os eventos finalizados.
     */
    private void scrollToInitialPosition() {
        if (currentSchedule.isEmpty()) return;
        int targetPos = scheduleAdapter.findInitialScrollPosition();
        LinearLayoutManager lm = (LinearLayoutManager) hubContentRv.getLayoutManager();
        if (lm != null && targetPos >= 0) {
            lm.scrollToPositionWithOffset(targetPos, 0);
        }
    }

    private void updateEmptyState(boolean empty) {
        hubEmptyMsg.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private void updateSubTabsUI() {
        subTabSchedule.setSelected(isScheduleTabActive);
        subTabStandings.setSelected(!isScheduleTabActive);
        subTabSchedule.setTypeface(null, isScheduleTabActive ? Typeface.BOLD : Typeface.NORMAL);
        subTabStandings.setTypeface(null, !isScheduleTabActive ? Typeface.BOLD : Typeface.NORMAL);
        subTabSchedule.setTextColor(isScheduleTabActive ? 0xFFFFC107 : 0xAAFFFFFF);
        subTabStandings.setTextColor(!isScheduleTabActive ? 0xFFFFC107 : 0xAAFFFFFF);
    }

    private void animateTab(View v, boolean focus) {
        v.animate().scaleX(focus ? 1.05f : 1f).scaleY(focus ? 1.05f : 1f).setDuration(100).start();
        updateSubTabsUI();
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
                v.setOnClickListener(v1 -> {
                    commitLeagueSelection(getAdapterPosition());
                });
                v.setOnFocusChangeListener((v13, hasFocus) -> {
                    v13.animate().scaleX(hasFocus ? 1.04f : 1f).scaleY(hasFocus ? 1.04f : 1f).setDuration(100).start();
                    if (hasFocus) {
                        tv.setTextColor(0xFFFFFFFF);
                    } else {
                        boolean active = (getAdapterPosition() == currentLeagueIdx);
                        tv.setTextColor(active ? 0xFFFFC107 : 0xCCFFFFFF);
                    }
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
            holder.itemView.setSelected(active);
            holder.tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            holder.tv.setTextColor(active ? 0xFFFFC107 : 0xCCFFFFFF);
        }

        @Override public int getItemCount() { return ApiClient.HUB_LEAGUES.length; }
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

        /**
         * Encontra a posição de início do calendário:
         * 1. Primeiro evento ao vivo.
         * 2. Se não houver ao vivo, primeiro evento que ainda não começou.
         * 3. Jamais para em uma data com todos os eventos finalizados (exceto fim total do campeonato).
         */
        int findInitialScrollPosition() {
            if (items.isEmpty()) return 0;

            int firstLiveDayHeader = -1;
            int firstUpcomingDayHeader = -1;

            int currentDayHeaderPos = 0;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (item instanceof ApiClient.ScheduleDay) {
                    currentDayHeaderPos = i;
                } else if (item instanceof ApiClient.RoundMatch) {
                    ApiClient.RoundMatch rm = (ApiClient.RoundMatch) item;
                    boolean isLive = "in".equalsIgnoreCase(rm.state);
                    boolean isFinished = "post".equalsIgnoreCase(rm.state);
                    boolean isUpcoming = !isFinished && !isLive;

                    // 1ª prioridade: primeiro evento AO VIVO
                    if (isLive && firstLiveDayHeader < 0) {
                        firstLiveDayHeader = currentDayHeaderPos;
                    }
                    // 2ª prioridade: primeiro evento QUE AINDA NÃO COMEÇOU
                    if (isUpcoming && firstUpcomingDayHeader < 0) {
                        firstUpcomingDayHeader = currentDayHeaderPos;
                    }
                }
            }

            if (firstLiveDayHeader >= 0) {
                return firstLiveDayHeader;
            }
            if (firstUpcomingDayHeader >= 0) {
                return firstUpcomingDayHeader;
            }

            // Fallback apenas se todos os eventos do campeonato estiverem finalizados:
            // rola para o último dia disponível ao invés do primeiro
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i) instanceof ApiClient.ScheduleDay) {
                    return i;
                }
            }
            return 0;
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
            ll.setPadding(px(14), px(8), px(14), px(8));

            View dash = new View(parent.getContext());
            dash.setLayoutParams(new LinearLayout.LayoutParams(px(3), px(14)));
            ((LinearLayout.LayoutParams) dash.getLayoutParams()).setMarginEnd(px(8));
            dash.setBackgroundColor(Color.parseColor("#FFC107"));

            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tv.setTextColor(Color.parseColor("#FFC107"));
            tv.setTextSize(11);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setLetterSpacing(0.08f);
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
                itemView.setBackgroundColor(day.isToday
                        ? Color.parseColor("#12FFC107")
                        : Color.parseColor("#06FFFFFF"));
            }
        }

        // ── Match view ───────────────────────────────────────────────────────
        private View makeMatchView(ViewGroup parent) {
            LinearLayout root = new LinearLayout(parent.getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rootLp.setMargins(px(10), px(2), px(10), px(2));
            root.setLayoutParams(rootLp);
            root.setPadding(px(12), px(8), px(12), px(8));
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setBackgroundResource(R.drawable.sports_hub_row_bg);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvIcon = new TextView(parent.getContext());
            tvIcon.setTextSize(11);
            tvIcon.setTextColor(Color.parseColor("#00E676"));
            tvIcon.setTag("icon");
            tvIcon.setVisibility(View.GONE);
            LinearLayout.LayoutParams lpIcon = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpIcon.setMarginEnd(px(6));
            tvIcon.setLayoutParams(lpIcon);

            TextView home = new TextView(parent.getContext());
            home.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            home.setGravity(Gravity.END);
            home.setTextColor(Color.WHITE);
            home.setTextSize(13);
            home.setSingleLine(true);
            home.setEllipsize(android.text.TextUtils.TruncateAt.END);
            home.setTag("home");

            TextView score = new TextView(parent.getContext());
            LinearLayout.LayoutParams lpScore = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpScore.setMarginStart(px(12));
            lpScore.setMarginEnd(px(12));
            score.setLayoutParams(lpScore);
            score.setGravity(Gravity.CENTER);
            score.setTextSize(13);
            score.setTypeface(null, Typeface.BOLD);
            score.setMinWidth(px(56));
            score.setPadding(px(8), px(3), px(8), px(3));
            score.setTag("score");

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

            TextView status = new TextView(parent.getContext());
            status.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            status.setGravity(Gravity.CENTER);
            status.setTextSize(10);
            status.setTextColor(Color.parseColor("#80FFFFFF"));
            status.setPadding(0, px(2), 0, 0);
            status.setTag("status");

            root.addView(row);
            root.addView(status);

            root.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.02f : 1f).scaleY(f ? 1.02f : 1f).setDuration(80).start();
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

                if ("in".equalsIgnoreCase(rm.state)) {
                    score.setTextColor(Color.parseColor("#00E676"));
                    score.setBackgroundColor(Color.parseColor("#1800E676"));
                } else if ("post".equalsIgnoreCase(rm.state)) {
                    score.setTextColor(Color.parseColor("#BBBBBB"));
                    score.setBackgroundColor(Color.parseColor("#12FFFFFF"));
                } else {
                    score.setTextColor(Color.parseColor("#FFC107"));
                    score.setBackgroundColor(Color.parseColor("#14FFC107"));
                }

                if (rm.channelId != null && !rm.channelId.isEmpty()) {
                    icon.setText("📺 " + (rm.channelName != null ? rm.channelName : "AO VIVO"));
                    icon.setVisibility(View.VISIBLE);
                } else {
                    icon.setVisibility(View.GONE);
                }

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

            holder.itemView.setSelected(e.isHighlighted);
        }

        @Override public int getItemCount() { return currentStandings.size(); }

        private String sgnStr(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

        private View makeRow(ViewGroup parent) {
            LinearLayout ll = new LinearLayout(parent.getContext());
            ll.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(px(10), px(1), px(10), px(1));
            ll.setLayoutParams(rowLp);
            ll.setPadding(px(14), px(9), px(14), px(9));
            ll.setGravity(Gravity.CENTER_VERTICAL);
            ll.setFocusable(true);
            ll.setFocusableInTouchMode(true);
            ll.setBackgroundResource(R.drawable.sports_hub_row_bg);
            ll.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.01f : 1f).scaleY(f ? 1.01f : 1f).setDuration(80).start();
            });

            ll.addView(col(parent, 26, false, "#70FFFFFF", 10)); // pos
            ll.addView(col(parent,  0, true,  "#FFFFFF",   13)); // name
            ll.addView(col(parent, 30, false, "#FFD700",   13)); // pts bold
            ll.addView(col(parent, 26, false, "#AAAAAA",   11)); // pj
            ll.addView(col(parent, 24, false, "#AAAAAA",   11)); // v
            ll.addView(col(parent, 28, false, "#AAAAAA",   11)); // sg
            ll.addView(col(parent, 24, false, "#AAAAAA",   11)); // gp

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

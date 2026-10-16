package com.andplay.app.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaVersionHelper {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\((19\\d\\d|20\\d\\d)\\)");
    private static final Pattern LEADING_NUM_PATTERN = Pattern.compile("^[0-9]+\\s*[-–—]\\s*");
    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[.*?\\]");
    private static final Pattern PARENS_PATTERN = Pattern.compile("\\(.*?\\)");
    private static final Pattern FOUR_K_PATTERN = Pattern.compile("\\b4k\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLEAN_TAGS_PATTERN = Pattern.compile(
            "\\[\\s*(?:l|leg|legendado|dub|dublado|lan[cç]amentos?|hdr|dv|hybrid|cinema|rec|corrigido)\\s*\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_SPACES_PATTERN = Pattern.compile("\\s+");
    private static final Pattern HDR_DV_HYBRID_PATTERN = Pattern.compile("\\[(?:HDR|DV|Hybrid)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEG_BRACKETS_PATTERN = Pattern.compile("\\[\\s*(?:L|LEG|LEGENDADO)\\s*\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGENDADO_WORD_PATTERN = Pattern.compile("\\b(legendado)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANCAMENTOS_PATTERN = Pattern.compile("\\[\\s*lan[cç]amentos?\\s*\\]", Pattern.CASE_INSENSITIVE);

    public static String cleanTitleKey(String title) {
        if (title == null || title.isEmpty()) return "";
        int n = title.length();
        int i = 0;

        // 1. Pula numeração inicial: "01 - ", "123 – ", etc.
        if (Character.isDigit(title.charAt(0))) {
            while (i < n && Character.isDigit(title.charAt(i))) i++;
            while (i < n && title.charAt(i) == ' ') i++;
            if (i < n && (title.charAt(i) == '-' || title.charAt(i) == '–' || title.charAt(i) == '—')) {
                i++;
                while (i < n && title.charAt(i) == ' ') i++;
            } else {
                i = 0; // Se não era formato de numeração com hífen, reinicia
            }
        }

        StringBuilder sb = new StringBuilder(n - i);
        boolean lastWasSpace = false;
        int bracketDepth = 0;
        int parenDepth = 0;

        while (i < n) {
            char c = title.charAt(i);
            if (c == '[') {
                bracketDepth++;
                i++;
                continue;
            } else if (c == ']') {
                if (bracketDepth > 0) bracketDepth--;
                i++;
                continue;
            } else if (c == '(') {
                parenDepth++;
                i++;
                continue;
            } else if (c == ')') {
                if (parenDepth > 0) parenDepth--;
                i++;
                continue;
            }

            if (bracketDepth > 0 || parenDepth > 0) {
                i++;
                continue;
            }

            // Ignora a palavra "4k" isolada (word boundary)
            if (c == '4' && i + 1 < n && (title.charAt(i + 1) == 'k' || title.charAt(i + 1) == 'K')) {
                boolean prevOk = (i == 0 || !Character.isLetterOrDigit(title.charAt(i - 1)));
                boolean nextOk = (i + 2 == n || !Character.isLetterOrDigit(title.charAt(i + 2)));
                if (prevOk && nextOk) {
                    i += 2;
                    continue;
                }
            }

            // Converte maiúsculas para minúsculas
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }

            // Normalização rápida de acentos ASCII
            switch (c) {
                case 'á': case 'à': case 'ã': case 'â': case 'ä': c = 'a'; break;
                case 'é': case 'è': case 'ê': case 'ë': c = 'e'; break;
                case 'í': case 'ì': case 'î': case 'ï': c = 'i'; break;
                case 'ó': case 'ò': case 'õ': case 'ô': case 'ö': c = 'o'; break;
                case 'ú': case 'ù': case 'û': case 'ü': c = 'u'; break;
                case 'ç': c = 'c'; break;
                case 'ñ': c = 'n'; break;
            }

            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastWasSpace = false;
            } else if (c == ' ' || c == '-' || c == '_' || c == '.' || c == ':' || c == '\'' || c == '\"' || c == '/') {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            }
            i++;
        }

        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    public static String cleanDisplayTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isEmpty()) return "";
        boolean hasBracket = rawTitle.indexOf('[') >= 0;
        boolean hasDigitStart = Character.isDigit(rawTitle.charAt(0));
        boolean has4k = rawTitle.contains("4k") || rawTitle.contains("4K");
        if (!hasBracket && !hasDigitStart && !has4k) {
            return rawTitle.trim();
        }
        String s = rawTitle;
        if (hasDigitStart) {
            s = LEADING_NUM_PATTERN.matcher(s).replaceAll("");
        }
        if (has4k) {
            s = FOUR_K_PATTERN.matcher(s).replaceAll("");
        }
        if (hasBracket) {
            s = CLEAN_TAGS_PATTERN.matcher(s).replaceAll("");
        }
        s = MULTI_SPACES_PATTERN.matcher(s).replaceAll(" ");
        return s.trim();
    }

    public static Movie.MovieVersion detectMovieVersion(Movie item) {
        String name = item.getRawTitle();
        String catId = item.category_id != null ? item.category_id : "";

        // Alinhado 100% com o index.html (web): tags [HDR], [DV], [Hybrid] ou '4k' ou categoria 765 identificam 4K
        boolean is4K = "765".equals(catId);
        if (!is4K && name != null) {
            is4K = FOUR_K_PATTERN.matcher(name).find() || HDR_DV_HYBRID_PATTERN.matcher(name).find();
        }

        boolean isLeg = "630".equals(catId);
        if (!isLeg && name != null) {
            isLeg = LEG_BRACKETS_PATTERN.matcher(name).find() || LEGENDADO_WORD_PATTERN.matcher(name).find();
        }

        String ext = (item.container_extension != null && !item.container_extension.isEmpty()) ? item.container_extension : "mp4";

        if (is4K) {
            if (isLeg) {
                return new Movie.MovieVersion("4k_leg", "4K Legendado", "4K LEG", "✨",
                        "Resolução 4K Ultra HD • Áudio Original com Legenda", item.stream_id, ext, "4K", item);
            }
            return new Movie.MovieVersion("4k_dub", "4K Ultra HD", "4K DUB", "✨",
                        "Resolução 4K Ultra HD • Dublado em Português", item.stream_id, ext, "4K", item);
        }

        if (isLeg) {
            return new Movie.MovieVersion("legendado", "Legendado", "LEG", "💬",
                    "Áudio Original com Legendas em Português", item.stream_id, ext, "1080p", item);
        }

        return new Movie.MovieVersion("dublado", "Dublado", "DUB", "🔊",
                "Áudio Dublado em Português", item.stream_id, ext, "1080p", item);
    }

    public static Series.SeriesVersion detectSeriesVersion(Series item) {
        if (item == null) return null;
        String name = item.getRawTitle();
        String catId = item.category_id != null ? item.category_id : "";

        boolean is4K = "765".equals(catId);
        if (!is4K && name != null) {
            is4K = FOUR_K_PATTERN.matcher(name).find() || HDR_DV_HYBRID_PATTERN.matcher(name).find();
        }

        boolean isLeg = "671".equals(catId);
        if (!isLeg && name != null) {
            isLeg = LEG_BRACKETS_PATTERN.matcher(name).find() || LEGENDADO_WORD_PATTERN.matcher(name).find();
        }

        if (is4K) {
            if (isLeg) {
                return new Series.SeriesVersion("4k_leg", "4K Legendado", "4K LEG", "✨",
                        "Resolução 4K Ultra HD • Áudio Original com Legenda", item.series_id, item);
            }
            return new Series.SeriesVersion("4k_dub", "4K Ultra HD", "4K DUB", "✨",
                    "Resolução 4K Ultra HD • Dublado em Português", item.series_id, item);
        }
        if (isLeg) {
            return new Series.SeriesVersion("legendado", "Legendado", "LEG", "💬",
                    "Áudio Original com Legenda", item.series_id, item);
        }
        return new Series.SeriesVersion("dublado", "Dublado", "DUB", "🔊",
                "Áudio Dublado em Português", item.series_id, item);
    }

    private static String extractYear(String text, String defaultYear) {
        if (text != null && text.length() >= 6) {
            int len = text.length();
            for (int i = 0; i <= len - 6; i++) {
                if (text.charAt(i) == '(' && text.charAt(i + 5) == ')') {
                    char c1 = text.charAt(i + 1);
                    char c2 = text.charAt(i + 2);
                    char c3 = text.charAt(i + 3);
                    char c4 = text.charAt(i + 4);
                    if ((c1 == '1' || c1 == '2') &&
                            (c1 == '1' ? (c2 == '9') : (c2 == '0')) &&
                            c3 >= '0' && c3 <= '9' &&
                            c4 >= '0' && c4 <= '9') {
                        return text.substring(i + 1, i + 5);
                    }
                }
            }
        }
        if (defaultYear != null && defaultYear.trim().length() == 4) {
            return defaultYear.trim();
        }
        return "";
    }

    public static List<Movie> groupMovies(List<Movie> rawList) {
        if (rawList == null || rawList.isEmpty()) return new ArrayList<>();

        List<Movie> groups = new ArrayList<>();
        Map<String, List<Integer>> cleanToGroupIndices = new HashMap<>();

        for (Movie item : rawList) {
            String rawTitle = item.getRawTitle();
            String cleanKey = cleanTitleKey(rawTitle);
            if (cleanKey.isEmpty()) {
                groups.add(item);
                continue;
            }

            String itemYear = extractYear(rawTitle, item.year);
            Movie.MovieVersion versionInfo = detectMovieVersion(item);
            boolean isLanc = "632".equals(item.category_id) || LANCAMENTOS_PATTERN.matcher(rawTitle).find();
            boolean isAnim = "621".equals(item.category_id) || "764".equals(item.category_id);
            boolean isSpec = "630".equals(item.category_id) || "765".equals(item.category_id) || "632".equals(item.category_id) || !"dublado".equals(versionInfo.type) || isLanc;

            Movie matchedGroup = null;
            List<Integer> candidateIndices = cleanToGroupIndices.get(cleanKey);

            if (candidateIndices != null) {
                for (int idx : candidateIndices) {
                    Movie g = groups.get(idx);
                    String gYear = extractYear(g.getRawTitle(), g.year);

                    // 1. Verificação de ano (evita mesclar refilmagens diferentes)
                    if (!itemYear.isEmpty() && !gYear.isEmpty()) {
                        try {
                            int y1 = Integer.parseInt(itemYear);
                            int y2 = Integer.parseInt(gYear);
                            if (Math.abs(y1 - y2) > 1) continue;
                        } catch (Exception ignored) {}
                    }

                    // 2. Animação vs Live-action
                    boolean gAnim = "621".equals(g.category_id) || "764".equals(g.category_id);
                    if (isAnim && !gAnim) continue;
                    if (!isAnim && !isSpec && gAnim) continue;

                    // 3. Mesma versão (ex: dois dublados só se juntam se um for lançamento)
                    boolean hasSameVersion = false;
                    for (Movie.MovieVersion v : g.versions) {
                        if (v.type != null && v.type.equals(versionInfo.type)) {
                            hasSameVersion = true;
                            break;
                        }
                    }
                    if (hasSameVersion && !isLanc) {
                        continue;
                    }

                    matchedGroup = g;
                    break;
                }
            }

            if (matchedGroup != null) {
                // Adiciona nova versão se ainda não existir
                boolean exists = false;
                for (int i = 0; i < matchedGroup.versions.size(); i++) {
                    Movie.MovieVersion ev = matchedGroup.versions.get(i);
                    if (ev.type != null && ev.type.equals(versionInfo.type)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    matchedGroup.versions.add(versionInfo);
                }
            } else {
                // Cria novo grupo representativo
                Movie groupMovie = item; // preserva referência original com metadados
                groupMovie.cleanTitle = cleanDisplayTitle(rawTitle);
                if (groupMovie.versions == null) {
                    groupMovie.versions = new ArrayList<>();
                }
                groupMovie.versions.clear();
                groupMovie.versions.add(versionInfo);

                int newIndex = groups.size();
                groups.add(groupMovie);
                if (candidateIndices == null) {
                    candidateIndices = new ArrayList<>();
                    cleanToGroupIndices.put(cleanKey, candidateIndices);
                }
                candidateIndices.add(newIndex);
            }
        }

        // Calcula sumário de versões uma única vez por grupo (elimina 33.000 chamadas redundantes)
        for (int i = 0; i < groups.size(); i++) {
            buildMovieSummary(groups.get(i));
        }

        return groups;
    }

    private static void buildMovieSummary(Movie m) {
        if (m.versions == null || m.versions.isEmpty()) return;

        // Limpa versões híbridas sintetizadas anteriores para recalcular
        for (int i = m.versions.size() - 1; i >= 0; i--) {
            String t = m.versions.get(i).type;
            if ("4k_leg_hybrid".equals(t) || "hybrid_dub".equals(t) || "hybrid_leg".equals(t)
                    || "hybrid_dub_leg".equals(t) || "hybrid_4k_orig".equals(t)) {
                m.versions.remove(i);
            }
        }

        Movie.MovieVersion dubVer = null;
        Movie.MovieVersion legVer = null;
        Movie.MovieVersion fourKDub = null;
        Movie.MovieVersion fourKLeg = null;

        for (Movie.MovieVersion v : m.versions) {
            if ("dublado".equals(v.type)) dubVer = v;
            else if ("legendado".equals(v.type)) legVer = v;
            else if ("4k_dub".equals(v.type)) fourKDub = v;
            else if ("4k_leg".equals(v.type)) fourKLeg = v;
        }

        // Híbrido 1: Imagem da versão legendada (com legenda impressa na tela) + Áudio da versão dublada (PT-BR)
        Movie.MovieVersion legVideoSource = (fourKLeg != null) ? fourKLeg : legVer;
        Movie.MovieVersion dubAudioSource = (dubVer != null) ? dubVer : fourKDub;
        if (legVideoSource != null && dubAudioSource != null) {
            Movie.MovieVersion h1 = new Movie.MovieVersion(
                    "hybrid_dub_leg",
                    "Híbrido (Áudio PT-BR + Legenda na Tela)",
                    "HÍBRIDO DUB",
                    "🔀",
                    "Vídeo com Legenda Impressa • Áudio Dublado em Português",
                    legVideoSource.streamId,
                    legVideoSource.ext,
                    legVideoSource.quality != null ? legVideoSource.quality : "1080p",
                    legVideoSource.rawMovie
            );
            h1.isHybrid = true;
            h1.audioStreamId = dubAudioSource.streamId;
            h1.audioExt = dubAudioSource.ext;
            m.versions.add(h1);
        }

        // Híbrido 2: Imagem da versão 4K (quando disponível) + Áudio da versão original em inglês
        Movie.MovieVersion fourKVideoSource = (fourKDub != null) ? fourKDub : fourKLeg;
        Movie.MovieVersion origAudioSource = (legVer != null) ? legVer : fourKLeg;
        if (fourKVideoSource != null && origAudioSource != null && fourKVideoSource != origAudioSource) {
            Movie.MovieVersion h2 = new Movie.MovieVersion(
                    "hybrid_4k_orig",
                    "4K Híbrido (Vídeo 4K + Áudio Original)",
                    "4K HÍBRIDO",
                    "✨",
                    "Resolução 4K Ultra HD • Áudio Original em Inglês",
                    fourKVideoSource.streamId,
                    fourKVideoSource.ext,
                    "4K",
                    fourKVideoSource.rawMovie
            );
            h2.isHybrid = true;
            h2.audioStreamId = origAudioSource.streamId;
            h2.audioExt = origAudioSource.ext;
            m.versions.add(h2);
        }

        boolean has4k = false;
        boolean hasDub = false;
        boolean hasLeg = false;
        boolean hasHybrid = false;

        Movie.MovieVersion preferredVersion = null;

        for (Movie.MovieVersion v : m.versions) {
            if ("4k_dub".equals(v.type) || "4k_leg".equals(v.type) || "hybrid_4k_orig".equals(v.type)) has4k = true;
            if (v.isHybrid || (v.type != null && v.type.startsWith("hybrid"))) hasHybrid = true;
            if ("dublado".equals(v.type) || "4k_dub".equals(v.type) || "hybrid_dub_leg".equals(v.type)) hasDub = true;
            if ("legendado".equals(v.type) || "4k_leg".equals(v.type)) hasLeg = true;

            // Prioridade para versão padrão: Dublado comum > 4K Dublado > Legendado > qualquer
            if ("dublado".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "4k_dub".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "legendado".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "4k_leg".equals(v.type)) {
                preferredVersion = v;
            }
        }

        if (preferredVersion == null && !m.versions.isEmpty()) {
            preferredVersion = m.versions.get(0);
        }
        m.activeVersion = preferredVersion;

        List<String> parts = new ArrayList<>();
        if (has4k) parts.add("4K");
        if (hasHybrid) parts.add("HÍBRIDO");
        if (hasDub) parts.add("DUB");
        if (hasLeg) parts.add("LEG");

        if (parts.isEmpty()) {
            m.versionsSummary = "";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" • ");
                sb.append(parts.get(i));
            }
            m.versionsSummary = sb.toString();
        }
    }

    public static List<Series> groupSeries(List<Series> rawList) {
        if (rawList == null || rawList.isEmpty()) return new ArrayList<>();

        List<Series> groups = new ArrayList<>();
        Map<String, List<Integer>> cleanToGroupIndices = new HashMap<>();

        for (Series item : rawList) {
            String rawTitle = item.getRawTitle();
            String cleanKey = cleanTitleKey(rawTitle);
            if (cleanKey.isEmpty()) {
                groups.add(item);
                continue;
            }

            String itemYear = extractYear(rawTitle, item.releaseDate);
            Series.SeriesVersion versionInfo = detectSeriesVersion(item);
            boolean isLanc = LANCAMENTOS_PATTERN.matcher(rawTitle).find();

            Series matchedGroup = null;
            List<Integer> candidateIndices = cleanToGroupIndices.get(cleanKey);

            if (candidateIndices != null) {
                for (int idx : candidateIndices) {
                    Series g = groups.get(idx);
                    String gYear = extractYear(g.getRawTitle(), g.releaseDate);

                    if (!itemYear.isEmpty() && !gYear.isEmpty()) {
                        try {
                            int y1 = Integer.parseInt(itemYear);
                            int y2 = Integer.parseInt(gYear);
                            if (Math.abs(y1 - y2) > 1) continue;
                        } catch (Exception ignored) {}
                    }

                    boolean hasSameVersion = false;
                    for (Series.SeriesVersion v : g.versions) {
                        if (v.type != null && v.type.equals(versionInfo.type)) {
                            hasSameVersion = true;
                            break;
                        }
                    }
                    if (hasSameVersion && !isLanc) {
                        continue;
                    }

                    matchedGroup = g;
                    break;
                }
            }

            if (matchedGroup != null) {
                boolean exists = false;
                for (Series.SeriesVersion ev : matchedGroup.versions) {
                    if (ev.type != null && ev.type.equals(versionInfo.type)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    matchedGroup.versions.add(versionInfo);
                }
            } else {
                Series groupSeries = item;
                groupSeries.cleanTitle = cleanDisplayTitle(rawTitle);
                if (groupSeries.versions == null) {
                    groupSeries.versions = new ArrayList<>();
                }
                groupSeries.versions.clear();
                groupSeries.versions.add(versionInfo);

                int newIndex = groups.size();
                groups.add(groupSeries);
                if (candidateIndices == null) {
                    candidateIndices = new ArrayList<>();
                    cleanToGroupIndices.put(cleanKey, candidateIndices);
                }
                candidateIndices.add(newIndex);
            }
        }

        // Calcula sumário de versões de séries uma única vez por grupo
        for (int i = 0; i < groups.size(); i++) {
            buildSeriesSummary(groups.get(i));
        }

        return groups;
    }

    private static void buildSeriesSummary(Series s) {
        if (s.versions == null || s.versions.isEmpty()) return;

        boolean has4k = false;
        boolean hasDub = false;
        boolean hasLeg = false;
        boolean hasHybrid = false;
        Series.SeriesVersion preferred = null;

        for (Series.SeriesVersion v : s.versions) {
            if ("4k_dub".equals(v.type) || "4k_leg".equals(v.type) || "hybrid_4k_orig".equals(v.type)) has4k = true;
            if (v.type != null && v.type.contains("hybrid")) hasHybrid = true;
            if ("dublado".equals(v.type) || "4k_dub".equals(v.type) || "hybrid_dub_leg".equals(v.type)) hasDub = true;
            if ("legendado".equals(v.type) || "4k_leg".equals(v.type)) hasLeg = true;

            // Prioridade: Dublado comum > 4K Dublado > Legendado > qualquer
            if ("dublado".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "4k_dub".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "legendado".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "4k_leg".equals(v.type)) {
                preferred = v;
            }
        }

        if (preferred == null && !s.versions.isEmpty()) {
            preferred = s.versions.get(0);
        }
        s.activeVersion = preferred;

        List<String> parts = new ArrayList<>();
        if (has4k) parts.add("4K");
        if (hasHybrid) parts.add("HÍBRIDO");
        if (hasDub) parts.add("DUB");
        if (hasLeg) parts.add("LEG");

        if (parts.isEmpty()) {
            s.versionsSummary = "";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" • ");
                sb.append(parts.get(i));
            }
            s.versionsSummary = sb.toString();
        }
    }
}

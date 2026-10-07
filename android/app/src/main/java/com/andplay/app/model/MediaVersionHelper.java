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
        String s = title;
        if (s.length() > 0 && Character.isDigit(s.charAt(0))) {
            s = LEADING_NUM_PATTERN.matcher(s).replaceAll("");
        }
        s = FOUR_K_PATTERN.matcher(s).replaceAll("");
        s = BRACKETS_PATTERN.matcher(s).replaceAll("");
        s = PARENS_PATTERN.matcher(s).replaceAll("");
        return fastNormalizeAscii(s);
    }

    private static String fastNormalizeAscii(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
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
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ' ') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }

    public static String cleanDisplayTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isEmpty()) return "";
        String s = rawTitle;
        if (s.length() > 0 && Character.isDigit(s.charAt(0))) {
            s = LEADING_NUM_PATTERN.matcher(s).replaceAll("");
        }
        s = FOUR_K_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_TAGS_PATTERN.matcher(s).replaceAll("");
        s = MULTI_SPACES_PATTERN.matcher(s).replaceAll(" ");
        return s.trim();
    }

    public static Movie.MovieVersion detectMovieVersion(Movie item) {
        String name = item.getRawTitle();
        String catId = item.category_id != null ? item.category_id : "";

        boolean isHybrid = HDR_DV_HYBRID_PATTERN.matcher(name).find() || name.toLowerCase(Locale.ROOT).contains("hybrid");

        boolean is4K = "765".equals(catId) ||
                FOUR_K_PATTERN.matcher(name).find() || isHybrid;

        boolean isLeg = "630".equals(catId) ||
                LEG_BRACKETS_PATTERN.matcher(name).find() ||
                LEGENDADO_WORD_PATTERN.matcher(name).find();

        String ext = (item.container_extension != null && !item.container_extension.isEmpty()) ? item.container_extension : "mp4";

        if (isHybrid) {
            if (isLeg) {
                return new Movie.MovieVersion("hybrid_leg", "Híbrido Legendado", "HYBRID LEG", "⚡",
                        "Versão Híbrida (HDR/DV/Remux) • Áudio Original com Legenda", item.stream_id, ext, "4K/HDR", item);
            }
            return new Movie.MovieVersion("hybrid_dub", "Híbrido Dublado", "HYBRID DUB", "⚡",
                    "Versão Híbrida (HDR/DV/Remux) • Dublado em Português", item.stream_id, ext, "4K/HDR", item);
        }

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

        boolean isHybrid = HDR_DV_HYBRID_PATTERN.matcher(name).find() || name.toLowerCase(Locale.ROOT).contains("hybrid");
        boolean is4K = FOUR_K_PATTERN.matcher(name).find() || isHybrid;

        boolean isLeg = "671".equals(catId) ||
                LEG_BRACKETS_PATTERN.matcher(name).find() ||
                LEGENDADO_WORD_PATTERN.matcher(name).find();

        if (isHybrid) {
            if (isLeg) {
                return new Series.SeriesVersion("hybrid_leg", "Híbrido Legendado", "HYBRID LEG", "⚡",
                        "Versão Híbrida (HDR/DV/Remux) • Áudio Original com Legenda", item.series_id, item);
            }
            return new Series.SeriesVersion("hybrid_dub", "Híbrido Dublado", "HYBRID DUB", "⚡",
                    "Versão Híbrida (HDR/DV/Remux) • Dublado em Português", item.series_id, item);
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
        if (text != null) {
            Matcher m = YEAR_PATTERN.matcher(text);
            if (m.find()) {
                return m.group(1);
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
                        // Se o item existente veio de lançamento e este não, substitui pelo estável
                        break;
                    }
                }
                if (!exists) {
                    matchedGroup.versions.add(versionInfo);
                }
                buildMovieSummary(matchedGroup);
            } else {
                // Cria novo grupo representativo
                Movie groupMovie = item; // preserva referência original com metadados
                groupMovie.cleanTitle = cleanDisplayTitle(rawTitle);
                if (groupMovie.versions == null) {
                    groupMovie.versions = new ArrayList<>();
                }
                groupMovie.versions.clear();
                groupMovie.versions.add(versionInfo);
                buildMovieSummary(groupMovie);

                int newIndex = groups.size();
                groups.add(groupMovie);
                if (candidateIndices == null) {
                    candidateIndices = new ArrayList<>();
                    cleanToGroupIndices.put(cleanKey, candidateIndices);
                }
                candidateIndices.add(newIndex);
            }
        }

        return groups;
    }

    private static void buildMovieSummary(Movie m) {
        if (m.versions == null || m.versions.isEmpty()) return;

        boolean has4k = false;
        boolean hasDub = false;
        boolean hasLeg = false;
        boolean hasHybrid = false;

        Movie.MovieVersion preferredVersion = null;

        for (Movie.MovieVersion v : m.versions) {
            if ("4k_dub".equals(v.type) || "4k_leg".equals(v.type)) has4k = true;
            if ("hybrid_dub".equals(v.type) || "hybrid_leg".equals(v.type)) hasHybrid = true;
            if ("dublado".equals(v.type) || "4k_dub".equals(v.type) || "hybrid_dub".equals(v.type)) hasDub = true;
            if ("legendado".equals(v.type) || "4k_leg".equals(v.type) || "hybrid_leg".equals(v.type)) hasLeg = true;

            // Prioridade para versão padrão: Dublado comum > 4K Dublado > Híbrido Dublado > Legendado > qualquer
            if ("dublado".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "4k_dub".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "hybrid_dub".equals(v.type)) {
                preferredVersion = v;
            } else if (preferredVersion == null && "legendado".equals(v.type)) {
                preferredVersion = v;
            }
        }

        if (preferredVersion == null && !m.versions.isEmpty()) {
            preferredVersion = m.versions.get(0);
        }
        m.activeVersion = preferredVersion;

        List<String> parts = new ArrayList<>();
        if (hasHybrid) parts.add("HYBRID");
        else if (has4k) parts.add("4K");
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
                buildSeriesSummary(matchedGroup);
            } else {
                Series groupSeries = item;
                groupSeries.cleanTitle = cleanDisplayTitle(rawTitle);
                if (groupSeries.versions == null) {
                    groupSeries.versions = new ArrayList<>();
                }
                groupSeries.versions.clear();
                groupSeries.versions.add(versionInfo);
                buildSeriesSummary(groupSeries);

                int newIndex = groups.size();
                groups.add(groupSeries);
                if (candidateIndices == null) {
                    candidateIndices = new ArrayList<>();
                    cleanToGroupIndices.put(cleanKey, candidateIndices);
                }
                candidateIndices.add(newIndex);
            }
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
            if ("4k_dub".equals(v.type) || "4k_leg".equals(v.type)) has4k = true;
            if ("hybrid_dub".equals(v.type) || "hybrid_leg".equals(v.type)) hasHybrid = true;
            if ("dublado".equals(v.type) || "4k_dub".equals(v.type) || "hybrid_dub".equals(v.type)) hasDub = true;
            if ("legendado".equals(v.type) || "4k_leg".equals(v.type) || "hybrid_leg".equals(v.type)) hasLeg = true;

            // Prioridade: Dublado comum > 4K Dublado > Híbrido Dublado > Legendado > qualquer
            if ("dublado".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "4k_dub".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "hybrid_dub".equals(v.type)) {
                preferred = v;
            } else if (preferred == null && "legendado".equals(v.type)) {
                preferred = v;
            }
        }

        if (preferred == null && !s.versions.isEmpty()) {
            preferred = s.versions.get(0);
        }
        s.activeVersion = preferred;

        List<String> parts = new ArrayList<>();
        if (hasHybrid) parts.add("HYBRID");
        else if (has4k) parts.add("4K");
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

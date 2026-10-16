package com.andplay.app.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Algoritmos de seleção, estratificação e recomendação de trilhos e carrosséis.
 * Portado fielmente do ecossistema Web (assets/js/app.js) para garantir que
 * Web e Android operem como gêmeos idênticos (mesmas cotas, estratificação caótica,
 * distribuição homogênea e deduplicação de versões).
 */
public class HomeRailAlgorithm {

    public static final int HOME_RAIL_ITEM_LIMIT = 24;

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
    private static final Random RNG = new Random();

    public static Movie seriesToPseudoMovie(Series s) {
        return convertSingleSeriesToPseudoMovie(s);
    }

    public static int getItemYear(Movie item) {
        if (item == null) return 0;
        if (item.year != null && !item.year.trim().isEmpty()) {
            Matcher m = YEAR_PATTERN.matcher(item.year);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        if (item.release_date != null && !item.release_date.trim().isEmpty()) {
            Matcher m = YEAR_PATTERN.matcher(item.release_date);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        String raw = item.getRawTitle();
        if (raw != null) {
            Matcher m = YEAR_PATTERN.matcher(raw);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    public static long getItemAddedTime(Movie item) {
        if (item == null || item.added == null || item.added.trim().isEmpty()) return 0;
        String raw = item.added.trim();
        try {
            long n = Long.parseLong(raw);
            if (n > 100000000000L) return n; // já em milissegundos
            if (n > 0) return n * 1000L;      // segundos para milissegundos
        } catch (Exception ignored) {}

        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = fmt.parse(raw);
            if (d != null) return d.getTime();
        } catch (Exception ignored) {}

        try {
            SimpleDateFormat fmtIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            fmtIso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String clean = raw.split("\\.")[0];
            Date d = fmtIso.parse(clean);
            if (d != null) return d.getTime();
        } catch (Exception ignored) {}

        return 0;
    }

    public static double getItemRating(Movie item) {
        if (item == null) return 0.0;
        String r = item.rating;
        if (r == null || r.trim().isEmpty()) r = item.rating_5based;
        if (r == null || r.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(r.replace(",", ".").trim());
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    public static String getHomeDisplayKey(Movie item) {
        if (item == null) return "";
        String type = item.isSeries ? "series" : "movie";
        String clean = item.cleanTitle;
        if (clean == null || clean.isEmpty()) {
            clean = MediaVersionHelper.cleanDisplayTitle(item.getRawTitle());
        }
        String key = MediaVersionHelper.cleanTitleKey(clean);
        if (key != null && !key.isEmpty()) {
            return type + ":title:" + key;
        }
        return type + ":id:" + (item.stream_id != null ? item.stream_id : "");
    }

    public static <T> List<T> pickRandomSample(List<T> source, int count) {
        if (source == null || source.isEmpty() || count <= 0) return new ArrayList<>();
        List<T> copy = new ArrayList<>(source);
        Collections.shuffle(copy, RNG);
        if (copy.size() > count) {
            return new ArrayList<>(copy.subList(0, count));
        }
        return copy;
    }

    /**
     * Amostragem caótica estratificada por épocas idêntica ao Web (assets/js/app.js).
     * Divide os candidatos em 4 faixas equilibradas:
     * - poolAdded: 25% (novidades adicionadas recentemente)
     * - poolRecent: 30% (ano >= 2023)
     * - poolModern: 25% (2010 a 2022)
     * - poolClassic: 20% (< 2010)
     */
    public static List<Movie> sampleChaoticRailItems(List<Movie> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();

        // Pré-filtro para desduplicar por chave canônica
        Set<String> seenDedupe = new HashSet<>();
        List<Movie> uniqueCandidates = new ArrayList<>();
        for (Movie item : candidates) {
            String k = getHomeDisplayKey(item);
            if (!k.isEmpty() && seenDedupe.contains(k)) continue;
            if (!k.isEmpty()) seenDedupe.add(k);
            uniqueCandidates.add(item);
        }

        if (uniqueCandidates.size() <= limit) {
            List<Movie> res = new ArrayList<>(uniqueCandidates);
            Collections.shuffle(res, RNG);
            return res;
        }

        // 1. Identificar novidades recentemente adicionadas ao catálogo (top 20%)
        List<Movie> sortedByTime = new ArrayList<>(uniqueCandidates);
        Collections.sort(sortedByTime, (a, b) -> Long.compare(getItemAddedTime(b), getItemAddedTime(a)));
        int topAddedCount = Math.max(2, (int) Math.floor(uniqueCandidates.size() * 0.20));
        Set<String> addedKeys = new HashSet<>();
        for (int i = 0; i < Math.min(topAddedCount, sortedByTime.size()); i++) {
            addedKeys.add(getHomeDisplayKey(sortedByTime.get(i)));
        }

        // 2. Classificar em 4 faixas
        List<Movie> poolAdded = new ArrayList<>();
        List<Movie> poolRecent = new ArrayList<>();
        List<Movie> poolModern = new ArrayList<>();
        List<Movie> poolClassic = new ArrayList<>();

        for (Movie item : uniqueCandidates) {
            String k = getHomeDisplayKey(item);
            int y = getItemYear(item);
            if (addedKeys.contains(k)) {
                poolAdded.add(item);
            } else if (y >= 2023) {
                poolRecent.add(item);
            } else if (y >= 2010) {
                poolModern.add(item);
            } else {
                poolClassic.add(item);
            }
        }

        // Cotas idênticas ao Web
        int quotaAdded = Math.max(1, Math.round(limit * 0.25f));
        int quotaRecent = Math.max(1, Math.round(limit * 0.30f));
        int quotaModern = Math.max(1, Math.round(limit * 0.25f));
        int quotaClassic = Math.max(1, limit - quotaAdded - quotaRecent - quotaModern);

        List<Movie> pickedAdded = pickRandomSample(poolAdded, quotaAdded);
        List<Movie> pickedRecent = pickRandomSample(poolRecent, quotaRecent);
        List<Movie> pickedModern = pickRandomSample(poolModern, quotaModern);
        List<Movie> pickedClassic = pickRandomSample(poolClassic, quotaClassic);

        List<Movie> combined = new ArrayList<>();
        combined.addAll(pickedAdded);
        combined.addAll(pickedRecent);
        combined.addAll(pickedModern);
        combined.addAll(pickedClassic);

        Set<String> usedKeys = new HashSet<>();
        for (Movie m : combined) usedKeys.add(getHomeDisplayKey(m));

        if (combined.size() < limit) {
            List<Movie> leftover = new ArrayList<>();
            for (Movie m : uniqueCandidates) {
                if (!usedKeys.contains(getHomeDisplayKey(m))) {
                    leftover.add(m);
                }
            }
            List<Movie> extra = pickRandomSample(leftover, limit - combined.size());
            combined.addAll(extra);
        }

        Collections.shuffle(combined, RNG);
        if (combined.size() > limit) {
            return new ArrayList<>(combined.subList(0, limit));
        }
        return combined;
    }

    /**
     * Constrói o trilho de "Novidades" idêntico ao Web:
     * - 24 itens
     * - 35% de novidades do catálogo (amostradas das top 45 novidades)
     * - 65% de catálogo geral estratificado
     * - Novidades distribuídas homogeneamente nos índices 1, 4, 7, 10, 13, 16, 19, 22.
     */
    public static List<Movie> buildNewsRail(List<Movie> allMovies, List<Series> allSeries, int limit) {
        List<Movie> candidates = new ArrayList<>();
        if (allMovies != null) {
            for (Movie m : allMovies) {
                if (m.name != null && m.name.toLowerCase().contains("demo")) continue;
                candidates.add(m);
            }
        }
        if (allSeries != null) {
            for (Series s : allSeries) {
                if (s.name != null && s.name.toLowerCase().contains("demo")) continue;
                Movie pseudo = convertSingleSeriesToPseudoMovie(s);
                if (pseudo != null) candidates.add(pseudo);
            }
        }

        if (candidates.isEmpty()) return new ArrayList<>();

        int railLimit = limit > 0 ? limit : 24;
        int catalogNewsQuota = Math.round(railLimit * 0.35f); // 8 itens
        int generalQuota = railLimit - catalogNewsQuota;      // 16 itens

        // Ordenar por data de adição ao catálogo
        List<Movie> sortedByAdded = new ArrayList<>(candidates);
        Collections.sort(sortedByAdded, (a, b) -> Long.compare(getItemAddedTime(b), getItemAddedTime(a)));

        Set<String> seenNews = new HashSet<>();
        List<Movie> uniqueTopAdded = new ArrayList<>();
        for (Movie item : sortedByAdded) {
            String k = getHomeDisplayKey(item);
            if (!k.isEmpty() && seenNews.contains(k)) continue;
            if (!k.isEmpty()) seenNews.add(k);
            uniqueTopAdded.add(item);
            if (uniqueTopAdded.size() >= 45) break;
        }

        List<Movie> catalogNewsItems = pickRandomSample(uniqueTopAdded, catalogNewsQuota);
        Set<String> usedInNews = new HashSet<>();
        for (Movie m : catalogNewsItems) usedInNews.add(getHomeDisplayKey(m));

        List<Movie> remainingPool = new ArrayList<>();
        for (Movie m : candidates) {
            if (!usedInNews.contains(getHomeDisplayKey(m))) {
                remainingPool.add(m);
            }
        }

        List<Movie> generalItems = sampleChaoticRailItems(remainingPool, generalQuota);

        // Distribuir os 35% de novidades homogeneamente (índices 1, 4, 7, 10, 13, 16, 19, 22)
        int[] newsIndices = new int[]{1, 4, 7, 10, 13, 16, 19, 22};
        Set<Integer> newsIndexSet = new HashSet<>();
        for (int idx : newsIndices) newsIndexSet.add(idx);

        List<Movie> distributed = new ArrayList<>(Collections.nCopies(railLimit, (Movie) null));
        int ni = 0;
        int gi = 0;

        for (int i = 0; i < railLimit; i++) {
            if (newsIndexSet.contains(i) && ni < catalogNewsItems.size()) {
                distributed.set(i, catalogNewsItems.get(ni++));
            } else if (gi < generalItems.size()) {
                distributed.set(i, generalItems.get(gi++));
            } else if (ni < catalogNewsItems.size()) {
                distributed.set(i, catalogNewsItems.get(ni++));
            }
        }

        List<Movie> finalNews = new ArrayList<>();
        for (Movie m : distributed) {
            if (m != null) finalNews.add(m);
        }
        return finalNews;
    }

    /**
     * Constrói os destaques do Hero/Banner rotativo idêntico ao Web (assets/js/app.js - getHomeFeaturedItems):
     * - 10 itens
     * - Exatamente 5 vagas para as novidades mais recentes adicionadas ao catálogo (desduplicadas)
     * - Exatamente 5 vagas para alta nota / aclamados / populares
     * - Intercala filmes e séries
     */
    public static List<Movie> buildFeaturedBannerItems(List<Movie> allMovies, List<Series> allSeries, int limit) {
        List<Movie> candidates = new ArrayList<>();
        if (allMovies != null) {
            for (Movie m : allMovies) {
                if (m.name != null && m.name.toLowerCase().contains("demo")) continue;
                candidates.add(m);
            }
        }
        if (allSeries != null) {
            for (Series s : allSeries) {
                if (s.name != null && s.name.toLowerCase().contains("demo")) continue;
                Movie pseudo = convertSingleSeriesToPseudoMovie(s);
                if (pseudo != null) candidates.add(pseudo);
            }
        }

        if (candidates.isEmpty()) return new ArrayList<>();

        int bannerLimit = limit > 0 ? limit : 10;
        int newsSlots = bannerLimit / 2;     // 5
        int acclaimedSlots = bannerLimit - newsSlots; // 5

        // 1. 5 Novidades mais recentes adicionadas ao catálogo
        List<Movie> sortedByAdded = new ArrayList<>(candidates);
        Collections.sort(sortedByAdded, (a, b) -> Long.compare(getItemAddedTime(b), getItemAddedTime(a)));

        Set<String> seenBanner = new HashSet<>();
        List<Movie> topAddedBanner = new ArrayList<>();
        for (Movie item : sortedByAdded) {
            String k = getHomeDisplayKey(item);
            if (!k.isEmpty() && seenBanner.contains(k)) continue;
            if (!k.isEmpty()) seenBanner.add(k);
            topAddedBanner.add(item);
            if (topAddedBanner.size() >= newsSlots) break;
        }

        // 2. 5 Destaques de alta nota / populares entre o restante
        List<Movie> acclaimedPool = new ArrayList<>();
        for (Movie m : candidates) {
            if (!seenBanner.contains(getHomeDisplayKey(m))) {
                double r = getItemRating(m);
                if (r >= 7.8) {
                    acclaimedPool.add(m);
                }
            }
        }

        Collections.sort(acclaimedPool, (a, b) -> Double.compare(getItemRating(b), getItemRating(a)));
        List<Movie> topAcclaimed = new ArrayList<>();
        for (Movie m : acclaimedPool) {
            String k = getHomeDisplayKey(m);
            if (!k.isEmpty() && seenBanner.contains(k)) continue;
            if (!k.isEmpty()) seenBanner.add(k);
            topAcclaimed.add(m);
            if (topAcclaimed.size() >= acclaimedSlots) break;
        }

        // Se faltar para acclaimedSlots, pega os maiores ratings do restante
        if (topAcclaimed.size() < acclaimedSlots) {
            for (Movie m : candidates) {
                String k = getHomeDisplayKey(m);
                if (!seenBanner.contains(k)) {
                    seenBanner.add(k);
                    topAcclaimed.add(m);
                    if (topAcclaimed.size() >= acclaimedSlots) break;
                }
            }
        }

        // Intercalar novidades e aclamados
        List<Movie> bannerItems = new ArrayList<>();
        int aIdx = 0, cIdx = 0;
        while (bannerItems.size() < bannerLimit && (aIdx < topAddedBanner.size() || cIdx < topAcclaimed.size())) {
            if (aIdx < topAddedBanner.size()) bannerItems.add(topAddedBanner.get(aIdx++));
            if (cIdx < topAcclaimed.size() && bannerItems.size() < bannerLimit) bannerItems.add(topAcclaimed.get(cIdx++));
        }

        return bannerItems;
    }

    /**
     * Constrói o trilho "Recomendados / Para Você" idêntico ao Web (getHomeRecommendationMix):
     * - Calcula afinidade por gênero a partir do perfil de gosto e histórico de assistidos/favoritos
     * - Aplica bônus de nota (IMDb >= 7.0) e novidade (ano >= 2023)
     * - Exclui itens já assistidos
     * - Garante diversidade de gêneros
     */
    public static List<Movie> buildForYouRail(
            List<Movie> allMovies,
            List<Series> allSeries,
            Map<String, Integer> genreWeights,
            Set<String> watchedKeys,
            int limit
    ) {
        List<Movie> candidates = new ArrayList<>();
        if (allMovies != null) {
            for (Movie m : allMovies) {
                if (m.name != null && m.name.toLowerCase().contains("demo")) continue;
                candidates.add(m);
            }
        }
        if (allSeries != null) {
            for (Series s : allSeries) {
                if (s.name != null && s.name.toLowerCase().contains("demo")) continue;
                Movie pseudo = convertSingleSeriesToPseudoMovie(s);
                if (pseudo != null) candidates.add(pseudo);
            }
        }

        if (candidates.isEmpty()) return new ArrayList<>();

        Map<String, Integer> safeWeights = genreWeights != null ? genreWeights : new HashMap<>();
        Set<String> safeWatched = watchedKeys != null ? watchedKeys : new HashSet<>();

        class ScoredItem {
            final Movie movie;
            final double score;
            final String primaryGenre;

            ScoredItem(Movie m, double s, String g) {
                this.movie = m;
                this.score = s;
                this.primaryGenre = g;
            }
        }

        List<ScoredItem> scored = new ArrayList<>();
        for (Movie m : candidates) {
            String dk = getHomeDisplayKey(m);
            if (!dk.isEmpty() && safeWatched.contains(dk)) continue;

            String genreRaw = m.genre != null ? m.genre.toLowerCase(Locale.ROOT) : "";
            double tasteScore = 0;
            String primaryG = "geral";

            if (!genreRaw.isEmpty()) {
                String[] parts = genreRaw.split("[,/•-]");
                for (String p : parts) {
                    String clean = p.trim();
                    if (!clean.isEmpty()) {
                        if ("geral".equals(primaryG)) primaryG = clean;
                        if (safeWeights.containsKey(clean)) {
                            tasteScore += safeWeights.get(clean) * 2.5;
                        }
                    }
                }
            }

            double r = getItemRating(m);
            double ratingBonus = r >= 7.0 ? (r * 1.5) : r;
            int y = getItemYear(m);
            double yearBonus = y >= 2023 ? 5.0 : (y >= 2018 ? 2.0 : 0.0);
            double fourKBonus = (m.versionsSummary != null && m.versionsSummary.contains("4K")) ? 2.5 : 0.0;

            double totalScore = tasteScore + ratingBonus + yearBonus + fourKBonus;
            scored.add(new ScoredItem(m, totalScore, primaryG));
        }

        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        // Diversidade: nenhum gênero individual pode ocupar mais de 30% das vagas
        int maxPerGenre = Math.max(3, Math.round(limit * 0.30f));
        Map<String, Integer> genreCount = new HashMap<>();
        List<Movie> result = new ArrayList<>();

        for (ScoredItem si : scored) {
            int count = genreCount.containsKey(si.primaryGenre) ? genreCount.get(si.primaryGenre) : 0;
            if (count < maxPerGenre || result.size() >= limit - 3) {
                result.add(si.movie);
                genreCount.put(si.primaryGenre, count + 1);
            }
            if (result.size() >= limit) break;
        }

        // Se ainda não preencheu, completa com os próximos melhores pontuados
        if (result.size() < limit) {
            Set<String> used = new HashSet<>();
            for (Movie m : result) used.add(getHomeDisplayKey(m));
            for (ScoredItem si : scored) {
                if (!used.contains(getHomeDisplayKey(si.movie))) {
                    result.add(si.movie);
                    used.add(getHomeDisplayKey(si.movie));
                    if (result.size() >= limit) break;
                }
            }
        }

        return result;
    }

    /**
     * Trilho "Mais bem avaliados" idêntico ao Web:
     * Filtra rating >= 6.0 (ou IMDb >= 7.5), extrai top 150 e aplica amostragem caótica.
     */
    public static List<Movie> buildTopRatedRail(List<Movie> allMovies, List<Series> allSeries, int limit) {
        List<Movie> candidates = new ArrayList<>();
        if (allMovies != null) {
            for (Movie m : allMovies) {
                if (m.name != null && m.name.toLowerCase().contains("demo")) continue;
                if (getItemRating(m) >= 6.0) candidates.add(m);
            }
        }
        if (allSeries != null) {
            for (Series s : allSeries) {
                if (s.name != null && s.name.toLowerCase().contains("demo")) continue;
                Movie pseudo = convertSingleSeriesToPseudoMovie(s);
                if (pseudo != null && getItemRating(pseudo) >= 6.0) candidates.add(pseudo);
            }
        }

        Collections.sort(candidates, (a, b) -> Double.compare(getItemRating(b), getItemRating(a)));
        int poolSize = Math.min(150, candidates.size());
        List<Movie> topPool = new ArrayList<>(candidates.subList(0, poolSize));

        return sampleChaoticRailItems(topPool, limit > 0 ? limit : 24);
    }

    public static Movie convertSingleSeriesToPseudoMovie(Series s) {
        if (s == null) return null;
        String nameLow = s.name != null ? s.name.toLowerCase() : "";
        String titleLow = s.title != null ? s.title.toLowerCase() : "";
        if (nameLow.contains("demo") || titleLow.contains("demo")) return null;

        Movie pseudo = new Movie();
        pseudo.stream_id = s.series_id;
        pseudo.name = s.getDisplayTitle();
        pseudo.title = s.getDisplayTitle();
        pseudo.cleanTitle = s.cleanTitle;
        pseudo.versionsSummary = s.versionsSummary;
        pseudo.isSeries = true;
        pseudo.stream_icon = s.getPosterUrl();
        pseudo.plot = s.plot;
        pseudo.rating = s.rating;
        pseudo.genre = s.genre;
        pseudo.year = s.releaseDate;
        pseudo.release_date = s.releaseDate;
        pseudo.category_id = s.category_id;
        pseudo.added = s.last_modified;

        if (s.versions != null && !s.versions.isEmpty()) {
            pseudo.versions = new ArrayList<>();
            for (Series.SeriesVersion sv : s.versions) {
                pseudo.versions.add(new Movie.MovieVersion(
                        sv.type, sv.label, sv.badge, sv.icon, sv.desc, sv.seriesId, "mp4", "1080p", null
                ));
            }
        }
        return pseudo;
    }
}

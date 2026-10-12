package com.stonewu.agenteam.service.modelpricing;

import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;

import java.net.URI;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 自动推荐只用精确服务地址和编码；宽松搜索仅用于用户主动选择。 */
public final class ModelsDevPriceSearch {
    private static final Map<String, String> DEFAULT_APIS = Map.of(
        "openai", "https://api.openai.com/v1", "anthropic", "https://api.anthropic.com/v1",
        "google", "https://generativelanguage.googleapis.com/v1beta");

    private ModelsDevPriceSearch() {
    }

    public static boolean matches(ModelPriceCatalogEntry entry, String baseUrl, String modelName, String protocol) {
        String target = protocol.equals("gemini") && modelName.startsWith("models/") ? modelName.substring(7) : modelName;
        String source = entry.api() == null ? DEFAULT_APIS.get(entry.providerId()) : entry.api();
        String address = endpoint(source);
        return target.equals(entry.modelId()) && address != null && address.equals(endpoint(baseUrl));
    }

    private static String endpoint(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            String path = uri.getPath().replaceAll("/+$", "").replaceFirst("/v1(?:beta)?$", "");
            return uri.getHost().toLowerCase(Locale.ROOT) + path;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static int score(ModelPriceCatalogEntry entry, String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return 1;
        }
        String model = normalize(entry.modelId());
        if (model.equals(needle)) {
            return 1000;
        }
        String haystack = normalize(entry.modelId() + " " + entry.modelName() + " " + entry.providerId()
            + " " + entry.providerName() + " " + entry.canonicalModelId());
        if (haystack.replace(" ", "").contains(needle.replace(" ", ""))) {
            return 700;
        }
        List<String> words = Arrays.asList(haystack.split(" +"));
        int score = 500;
        for (String token : needle.split(" +")) {
            if (haystack.contains(token)) {
                continue;
            }
            if (token.length() >= 4 && words.stream().anyMatch(word -> near(token, word))) {
                score -= 50;
                continue;
            }
            return 0;
        }
        return score;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static boolean near(String left, String right) {
        int limit = left.length() >= 8 ? 2 : 1;
        if (Math.abs(left.length() - right.length()) > limit || right.isEmpty()) {
            return false;
        }
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            int best = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
                best = Math.min(best, current[j]);
            }
            if (best > limit) {
                return false;
            }
            previous = current;
        }
        return previous[right.length()] <= limit;
    }
}

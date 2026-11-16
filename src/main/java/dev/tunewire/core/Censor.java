package dev.tunewire.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks profanity in lyric lines for the "censor explicit lyrics" option: the first letter stays and the
 * rest become asterisks ("f***"). Whole words only, so "class", "Dickens" or "cocktail" are untouched.
 * Stems match any word that starts with them ("fucking", "shitty"); short or ambiguous words match exactly.
 */
public final class Censor {
    /** Any word starting with one of these is masked. */
    private static final String[] STEMS = {
            "fuck", "motherfuck", "shit", "bitch", "cunt", "nigga", "nigger", "faggot", "whore", "slut", "bullshit",
            "dickhead", "pussies", "asshole",
    };
    /** Masked only as whole words. */
    private static final Set<String> EXACT = Set.of(
            "ass", "asses", "dick", "dicks", "pussy", "fag", "fags", "bastard", "bastards", "cock", "cocks",
            "hoe", "hoes", "damn", "goddamn", "tits", "titties", "piss", "pissed", "wank", "wanker", "twat", "thot", "thots");

    // letters plus apostrophes inside words (fuckin', motherfuckin')
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}']*");

    private Censor() {}

    public static String clean(String line) {
        if (line == null || line.isEmpty()) return line;
        Matcher m = WORD.matcher(line);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String word = m.group();
            if (!isProfane(word)) continue;
            if (out == null) out = new StringBuilder(line.length());
            out.append(line, last, m.start()).append(mask(word));
            last = m.end();
        }
        if (out == null) return line;
        return out.append(line, last, line.length()).toString();
    }

    static boolean isProfane(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        String bare = w.endsWith("'") ? w.substring(0, w.length() - 1) : w;
        if (EXACT.contains(bare)) return true;
        for (String s : STEMS) if (bare.startsWith(s)) return true;
        return false;
    }

    private static String mask(String word) {
        int keep = Character.charCount(word.codePointAt(0));
        StringBuilder b = new StringBuilder(word.substring(0, keep));
        for (int i = keep; i < word.length(); i++) b.append(word.charAt(i) == '\'' ? '\'' : '*');
        return b.toString();
    }
}

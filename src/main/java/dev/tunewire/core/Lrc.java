package dev.tunewire.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics in LRC format ("[01:23.45] line"). Immutable; lines sorted by time. */
public final class Lrc {
    public record Line(long timeMs, String text) {}

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    public static final Lrc EMPTY = new Lrc(List.of());

    private final List<Line> lines;

    private Lrc(List<Line> lines) {
        this.lines = lines;
    }

    public static Lrc parse(String text) {
        if (text == null || text.isBlank()) return EMPTY;
        List<Line> out = new ArrayList<>();
        long offset = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            Matcher off = OFFSET.matcher(line);
            if (off.matches()) {
                offset = Long.parseLong(off.group(1));
                continue;
            }
            Matcher m = TIME.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            // a line may carry several leading timestamps: "[00:10.00][01:20.00] chorus"
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                if (m.group(3) != null) {
                    String frac = (m.group(3) + "00").substring(0, 3);
                    ms = Long.parseLong(frac);
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String lyric = line.substring(end).strip();
            for (long t : times) out.add(new Line(t, lyric));
        }
        if (offset != 0) {
            // positive [offset:] shows lyrics earlier
            long o = offset;
            out.replaceAll(l -> new Line(Math.max(0, l.timeMs() - o), l.text()));
        }
        out.sort(Comparator.comparingLong(Line::timeMs)); // stable: keeps file order for equal times
        return out.isEmpty() ? EMPTY : new Lrc(Collections.unmodifiableList(out));
    }

    public List<Line> lines() {
        return lines;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Index of the line being sung at {@code ms}, or -1 before the first line. */
    public int indexAt(long ms) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}

package splint.core.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The words of a command, read left to right. */
public final class Args {
    private final List<String> words;
    private int i;
    private final boolean confirm;

    /** {@code confirm} anywhere in the words is taken out and reported by {@link #confirmed}. */
    public Args(List<String> words) {
        List<String> w = new ArrayList<>(words);
        this.confirm = w.removeIf(s -> s.equalsIgnoreCase("confirm"));
        this.words = w;
    }

    public boolean confirmed() { return confirm; }

    public boolean more() { return i < words.size(); }

    public String peek() { return more() ? words.get(i) : null; }

    public String word() {
        if (!more()) throw new Usage(i == 0 ? "Missing argument." : "Missing value after '" + words.get(i - 1) + "'.");
        return words.get(i++);
    }

    public String lower() { return word().toLowerCase(Locale.ROOT); }

    public int integer(String what) { return number(word(), what); }

    public boolean nextIsInt() {
        if (!more()) return false;
        try {
            Integer.parseInt(words.get(i));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A comma list, also accepting spaces after commas: "cow, sheep". */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        String raw = word();
        out.addAll(Arrays.asList(raw.split(",")));
        while (raw.endsWith(",") && more()) {
            raw = word();
            out.addAll(Arrays.asList(raw.split(",")));
        }
        out.removeIf(String::isBlank);
        return out;
    }

    /** Remaining words, consumed. */
    public List<String> rest() {
        List<String> out = new ArrayList<>(words.subList(i, words.size()));
        i = words.size();
        return out;
    }

    public static int number(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new Usage("Not a number for " + what + ": '" + s + "'");
        }
    }
}

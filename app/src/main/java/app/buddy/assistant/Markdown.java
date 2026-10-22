package app.buddy.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agents' replies in Markdown, shown formatted: headings, bold, italic, strikethrough, `code`, lists, quotes and
 * links in the text; code blocks and tables as their own scrollable boxes. A reply without code blocks or tables
 * stays one TextView.
 */
final class Markdown {
    private Markdown() {
    }

    /** Makes a TextView for a run of text, styled for where it's shown (selectable, link handling, size). */
    interface Texts {
        TextView make(CharSequence text);
    }

    /** The reply as one TextView (plain text and inline Markdown) or a column of blocks (with code or tables). */
    static View render(Context c, String text, Runnable linkThen, Texts texts) {
        List<Object> blocks = blocks(text);
        if (blocks.size() == 1 && blocks.get(0) instanceof String) {
            return texts.make(paragraphs(c, (String) blocks.get(0), linkThen));
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < blocks.size(); i++) {
            Object b = blocks.get(i);
            View v;
            if (b instanceof String) v = texts.make(paragraphs(c, (String) b, linkThen));
            else if (b instanceof Code) v = code(c, ((Code) b).text);
            else if (b instanceof Table) v = table(c, (Table) b, linkThen);
            else v = rule(c);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) p.topMargin = Ui.dp(c, 8);
            col.addView(v, p);
        }
        return col;
    }

    // ------------------------------------------------------------------ blocks

    private static final class Code {
        final String text;

        Code(String text) {
            this.text = text;
        }
    }

    private static final class Table {
        final List<String[]> rows = new ArrayList<>(); // the first is the header
    }

    private static final class Rule {
    }

    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    /** Text runs (String), code blocks, tables and rules, in order. */
    private static List<Object> blocks(String text) {
        List<Object> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String t = l.trim();
            Object block = null;
            if (t.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                while (j < lines.length && !lines[j].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[j]);
                    j++;
                }
                block = new Code(code.toString());
                i = j; // the closing fence (or the end)
            } else if (t.startsWith("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                Table tb = new Table();
                tb.rows.add(cells(t));
                int j = i + 2;
                while (j < lines.length && lines[j].trim().startsWith("|")) tb.rows.add(cells(lines[j++].trim()));
                block = tb;
                i = j - 1;
            } else if (RULE.matcher(l).matches() && !t.isEmpty()) {
                block = new Rule();
            }
            if (block == null) {
                if (run.length() > 0) run.append('\n');
                run.append(l);
                continue;
            }
            flush(out, run);
            out.add(block);
        }
        flush(out, run);
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static void flush(List<Object> out, StringBuilder run) {
        String s = run.toString().replaceAll("^\\n+|\\n+$", "");
        if (!s.trim().isEmpty()) out.add(s);
        run.setLength(0);
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        String[] c = r.split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < c.length; i++) c[i] = c[i].trim().replace("\\|", "|");
        return c;
    }

    // ------------------------------------------------------------------- text

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");

    /** A run of lines: headings, list items and quotes, each line's inline Markdown. */
    static CharSequence paragraphs(Context c, String text, Runnable linkThen) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        int indent = Ui.dp(c, 18);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String l = lines[i];
            int start = out.length();
            Matcher m;
            if ((m = HEADING.matcher(l)).matches()) {
                out.append(inline(c, m.group(2), linkThen));
                int level = m.group(1).length();
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level == 1 ? 1.3f : level == 2 ? 1.18f : 1.06f), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = BULLET.matcher(l)).matches()) {
                int depth = m.group(1).replace("\t", "  ").length() / 2;
                out.append("•  ").append(inline(c, m.group(2), linkThen));
                margin(out, start, depth * indent, depth * indent + indent);
            } else if ((m = NUMBERED.matcher(l)).matches()) {
                int depth = m.group(1).replace("\t", "  ").length() / 2;
                out.append(m.group(2)).append(".  ").append(inline(c, m.group(3), linkThen));
                margin(out, start, depth * indent, depth * indent + indent);
            } else if ((m = QUOTE.matcher(l)).matches()) {
                out.append(inline(c, m.group(1), linkThen));
                out.setSpan(new QuoteSpan(Ui.MUTED, Ui.dp(c, 3), Ui.dp(c, 10)), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.MUTED), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(inline(c, l, linkThen));
            }
        }
        return out;
    }

    private static void margin(SpannableStringBuilder b, int start, int first, int rest) {
        if (b.length() > start) {
            b.setSpan(new LeadingMarginSpan.Standard(first, rest), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|__(?=\\S)(.+?)(?<=\\S)__");
    private static final Pattern ITALIC = Pattern.compile(
            "(?<![\\w*])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![\\w*])|(?<![\\w_])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\w_])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");

    /** **bold**, *italic*, ~~struck~~, `code` and [text](url) in one line. */
    static CharSequence inline(Context c, String s, Runnable linkThen) {
        SpannableStringBuilder b = new SpannableStringBuilder(Ui.markdownLinks(s, linkThen));
        unwrap(c, b, CODE, 0);
        unwrap(c, b, BOLD, 1);
        unwrap(c, b, ITALIC, 2);
        unwrap(c, b, STRIKE, 3);
        return b;
    }

    /** Takes the markers off each match and styles what was between them; never inside `code`. */
    private static void unwrap(Context c, SpannableStringBuilder b, Pattern p, int kind) {
        int from = 0;
        Matcher m = p.matcher(b);
        while (from <= b.length() && m.find(from)) {
            int g = m.group(1) != null ? 1 : 2;
            int s = m.start(), e = m.end(), gs = m.start(g), ge = m.end(g);
            if (kind != 0 && b.getSpans(s, e, CodeSpan.class).length > 0) {
                from = e;
                continue;
            }
            b.delete(ge, e);
            b.delete(s, gs);
            int end = s + (ge - gs);
            switch (kind) {
                case 0:
                    b.setSpan(new CodeSpan(Ui.mono(c, 400)), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    b.setSpan(new BackgroundColorSpan(Theme.withAlpha(Ui.MUTED, 0x33)), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case 1:
                    b.setSpan(new StyleSpan(Typeface.BOLD), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case 2:
                    b.setSpan(new StyleSpan(Typeface.ITALIC), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    b.setSpan(new StrikethroughSpan(), s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            from = end;
            m = p.matcher(b);
        }
    }

    /** `code` in a line (a TypefaceSpan of its own, so other markers skip it). */
    private static final class CodeSpan extends TypefaceSpan {
        CodeSpan(Typeface t) {
            super(t);
        }
    }

    // ------------------------------------------------------------ block views

    private static View code(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTypeface(Ui.mono(c, 400));
        t.setTextSize(13);
        t.setTextColor(Ui.CODE_TEXT);
        t.setHorizontallyScrolling(true);
        t.setTextIsSelectable(true);
        t.setPaddingRelative(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.setBackground(Ui.rounded(c, Ui.CODE, 10));
        h.addView(t);
        return h;
    }

    private static View table(Context c, Table tb, Runnable linkThen) {
        TableLayout t = new TableLayout(c);
        t.setBackground(Ui.outlined(c, 0x00000000, 8, Ui.OUTLINE, 1));
        int cols = 0;
        for (String[] r : tb.rows) cols = Math.max(cols, r.length);
        for (int i = 0; i < tb.rows.size(); i++) {
            String[] r = tb.rows.get(i);
            TableRow row = new TableRow(c);
            if (i == 0) row.setBackgroundColor(Theme.withAlpha(Ui.MUTED, 0x1F));
            for (int j = 0; j < cols; j++) {
                TextView cell = new TextView(c);
                CharSequence s = inline(c, j < r.length ? r[j] : "", linkThen);
                cell.setText(s);
                cell.setTextSize(13.5f);
                cell.setTextColor(Ui.TEXT);
                cell.setTypeface(Ui.sans(c, i == 0 ? 600 : 400));
                cell.setMaxWidth(Ui.dp(c, 320));
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
                cell.setBackground(Ui.outlined(c, 0x00000000, 0, Ui.LINE, 1));
                row.addView(cell);
            }
            t.addView(row);
        }
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(t);
        return h;
    }

    private static View rule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.OUTLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 1)));
        v.setMinimumHeight(Ui.dp(c, 1));
        return v;
    }
}

package com.majstr.backend.staticpages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quoted string in a page's inline script never spans a line. The admin page went blank on prod
 * (2026-11-10) because a letter draft's {@code '\n'} was written out as real line breaks — one
 * SyntaxError and not a line of the script runs. The pages have no build step, so this is the only
 * check that reads them before a deploy.
 */
class InlineScriptStringsTest {

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", Pattern.DOTALL);

    /** Characters after which a {@code /} starts a regex literal rather than a division. */
    private static final String BEFORE_REGEX = "(,=:[!&|?{};+-*%<>~^";

    @ParameterizedTest
    @ValueSource(strings = {"admin/index.html", "portal/index.html", "message/index.html"})
    void noQuotedStringRunsPastTheEndOfALine(String page) throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static", page), StandardCharsets.UTF_8);
        List<String> broken = new ArrayList<>();
        Matcher m = INLINE_SCRIPT.matcher(html);
        while (m.find()) {
            broken.addAll(unterminatedStrings(m.group(1), lineOf(html, m.start(1))));
        }
        assertThat(broken).as("unterminated '…' or \"…\" in %s", page).isEmpty();
    }

    @Test
    void catchesTheBreakThatTookTheAdminDown() {
        assertThat(unterminatedStrings("const a = { body: 'Вітаю!\n\n'\n + 'x' };", 1)).isNotEmpty();
    }

    @Test
    void readsPastWhatOnlyLooksLikeAnOpenQuote() {
        assertThat(unterminatedStrings("const a = 'it\\'s'; // don't\n/* won't */ const b = `x\ny`;", 1))
                .isEmpty();
        assertThat(unterminatedStrings("s.replace(/[\"']/g, '');\nconst h = `<p>об'єкт ${x ? `б'є` : ''}</p>\n`;", 1))
                .isEmpty();
        assertThat(unterminatedStrings("const r = a / b; const q = 'ok';", 1)).isEmpty();
    }

    /** Lines where a '…' or "…" string is still open at the line break. Comments, regex literals,
     *  template literals (with nested {@code ${…}}) and escapes are stepped over. */
    static List<String> unterminatedStrings(String js, int firstLine) {
        List<String> found = new ArrayList<>();
        // One entry per open template literal: -1 while in its text, else the brace depth inside its ${…}.
        Deque<int[]> templates = new ArrayDeque<>();
        int line = firstLine;
        char prev = '(';
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            char next = i + 1 < js.length() ? js.charAt(i + 1) : 0;
            if (c == '\n') {
                line++;
            }
            int[] top = templates.peek();
            if (top != null && top[0] < 0) {
                if (c == '\\') {
                    i++;
                } else if (c == '`') {
                    templates.pop();
                    prev = 'a';
                } else if (c == '$' && next == '{') {
                    top[0] = 0;
                    i++;
                    prev = '{';
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '/' && next == '/') {
                while (i + 1 < js.length() && js.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = js.indexOf("*/", i + 2);
                int stop = end < 0 ? js.length() : end;
                for (int k = i; k < stop; k++) {
                    if (js.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = end < 0 ? js.length() : end + 1;
                continue;
            }
            if (c == '\'' || c == '"') {
                int k = i + 1;
                while (k < js.length() && js.charAt(k) != c && js.charAt(k) != '\n') {
                    k += js.charAt(k) == '\\' ? 2 : 1;
                }
                if (k >= js.length() || js.charAt(k) == '\n') {
                    found.add("line " + line);
                    i = k - 1;
                } else {
                    i = k;
                }
                prev = 'a';
                continue;
            }
            if (c == '/' && BEFORE_REGEX.indexOf(prev) >= 0) {
                boolean inClass = false;
                int k = i + 1;
                while (k < js.length() && js.charAt(k) != '\n') {
                    char r = js.charAt(k);
                    if (r == '\\') {
                        k++;
                    } else if (r == '[') {
                        inClass = true;
                    } else if (r == ']') {
                        inClass = false;
                    } else if (r == '/' && !inClass) {
                        break;
                    }
                    k++;
                }
                i = k;
                prev = 'a';
                continue;
            }
            if (c == '`') {
                templates.push(new int[] {-1});
                continue;
            }
            if (top != null) {
                if (c == '{') {
                    top[0]++;
                } else if (c == '}') {
                    if (top[0] == 0) {
                        top[0] = -1;
                        continue;
                    }
                    top[0]--;
                }
            }
            prev = c;
        }
        return found;
    }

    private static int lineOf(String s, int index) {
        int n = 1;
        for (int i = 0; i < index; i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}

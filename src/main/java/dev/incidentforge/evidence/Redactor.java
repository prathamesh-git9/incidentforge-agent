package dev.incidentforge.evidence;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials and personal data from untrusted text before it is stored as evidence,
 * shown to a reviewer or sent to a model provider.
 */
public final class Redactor {

    private record Rule(String type, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}")),
            new Rule("anthropic-key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{10,}")),
            new Rule("api-key", Pattern.compile("\\bsk-[A-Za-z0-9]{20,}")),
            new Rule("aws-access-key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new Rule("bearer-token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{12,}=*")),
            new Rule("secret", Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key)\\s*[=:]\\s*[^\\s;,]+")),
            new Rule("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")));

    private Redactor() {
    }

    public record Redacted(String text, boolean changed) {
    }

    public static Redacted redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Redacted(text, false);
        }
        String result = text;
        boolean changed = false;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(Matcher.quoteReplacement("[REDACTED:" + rule.type() + "]"));
                changed = true;
            }
        }
        return new Redacted(result, changed);
    }
}

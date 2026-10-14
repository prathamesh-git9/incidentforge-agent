package dev.incidentforge.evidence;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Flags retrieved text that looks like instructions aimed at an agent. This is a visibility
 * signal for reviewers, not a security control: the controls are the tool policy, the patch
 * policy, the absence of any merge capability and mandatory human approval, which hold even if
 * an injection is not detected.
 */
public final class InjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore (all |any )?(previous|prior|above) instructions"),
            Pattern.compile("(?i)\\byou are (now )?authori[sz]ed to\\b"),
            Pattern.compile("(?i)\\b(system|developer) (notice|prompt|message)\\b"),
            Pattern.compile("(?i)\\bmerge (your|the|this) (own )?(pull request|pr)\\b"),
            Pattern.compile("(?i)\\b(force[- ]push|git push)\\b"),
            Pattern.compile("(?i)\\.github/workflows"),
            Pattern.compile("(?i)\\b(delete|disable|skip) (the )?(failing )?(tests|checks)\\b"),
            Pattern.compile("(?i)curl [^|]*\\|\\s*(ba)?sh"));

    private InjectionDetector() {
    }

    public static boolean suspicious(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}

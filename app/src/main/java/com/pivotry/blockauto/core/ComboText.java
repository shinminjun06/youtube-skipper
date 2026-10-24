package com.pivotry.blockauto.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets a label detected by on-device text recognition. A naked number
 * never identifies a combo: the game's score and best-score numbers may be
 * much larger than the current combo. Only an explicit COMBO label qualifies.
 */
public final class ComboText {
    private ComboText() {}
    private static final Pattern LABELED = Pattern.compile(
        "(?i)\\bCOMBO[ \\t]*[:x\\u00d7]?[ \\t]*([0-9]{1,6})(?![0-9])");

    /** Returns the *displayed* combo number, or -1 if not established. */
    public static int displayed(String recognized) {
        if (recognized == null || recognized.length() > 8192) return -1;
        Matcher match=LABELED.matcher(recognized);
        int found=-1;
        while(match.find()) {
            int candidate;
            try {candidate=Integer.parseInt(match.group(1));}
            catch(NumberFormatException ignored) {continue;}
            if(candidate<1 || candidate>100000)continue;
            // Two inconsistent labels in the same frame are ambiguous.
            if(found>=0 && found!=candidate)return -1;
            found=candidate;
        }
        return found;
    }
}

package javax.lang.model;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal Android shim for {@code javax.lang.model.SourceVersion}.
 *
 * GraphHopper 7.0 validates every encoded-value name with
 * {@code SourceVersion.isKeyword(CharSequence)} (see
 * {@code IntEncodedValueImpl.isValidEncodedValue}). That class is part of the
 * JDK compiler API (the {@code javax.lang.model} package) and is NOT on
 * Android's bootclasspath, so on ART the reference throws
 * {@link NoClassDefFoundError} the first time an EncodedValue is constructed —
 * which happens on the very first route query, during snapping. Because
 * {@code javax.lang.model} is absent from Android, we are free to provide our
 * own class in this package; the APK's copy is what gets loaded.
 *
 * Only {@code isKeyword(CharSequence)} is referenced by GraphHopper. We
 * implement the real Java keyword/literal set so validation keeps its intended
 * meaning (reject names that collide with language keywords). GraphHopper's
 * built-in EV names never collide, so this always returns false in practice,
 * but a faithful implementation is cheap and future-proof.
 */
public final class SourceVersion {
    private SourceVersion() {}

    private static final Set<String> KEYWORDS = new HashSet<>();
    static {
        String[] kw = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            // the three reserved literals SourceVersion also treats as keywords
            "true", "false", "null",
        };
        for (String k : kw) KEYWORDS.add(k);
    }

    public static boolean isKeyword(CharSequence s) {
        return KEYWORDS.contains(s.toString());
    }

    public static boolean isName(CharSequence name) {
        String s = name.toString();
        if (s.isEmpty()) return false;
        int cp = s.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) return false;
        for (int i = Character.charCount(cp); i < s.length(); ) {
            cp = s.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            i += Character.charCount(cp);
        }
        return !isKeyword(s);
    }

    public static boolean isIdentifier(CharSequence name) {
        String s = name.toString();
        if (s.isEmpty()) return false;
        int cp = s.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) return false;
        for (int i = Character.charCount(cp); i < s.length(); ) {
            cp = s.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
}

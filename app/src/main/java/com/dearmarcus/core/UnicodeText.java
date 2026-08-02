package com.dearmarcus.core;

final class UnicodeText {
    private UnicodeText() {
    }

    static String required(String value, int maximumCodePoints, String fieldName) {
        if (value == null || isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return bounded(value, maximumCodePoints, fieldName);
    }

    static String optional(String value, int maximumCodePoints, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return bounded(value, maximumCodePoints, fieldName);
    }

    private static String bounded(String value, int maximumCodePoints, String fieldName) {
        rejectUnpairedSurrogates(value, fieldName);
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount > maximumCodePoints) {
            throw new IllegalArgumentException(
                    fieldName + " must contain at most " + maximumCodePoints + " Unicode code points.");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    // A surrogate pair counts as one Unicode scalar value; unpaired UTF-16 surrogates are rejected.
    private static void rejectUnpairedSurrogates(String value, String fieldName) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(fieldName + " contains an unpaired surrogate.");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(fieldName + " contains an unpaired surrogate.");
            }
        }
    }
}

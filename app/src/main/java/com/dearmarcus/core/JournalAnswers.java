package com.dearmarcus.core;

public final class JournalAnswers {
    public static final int MAXIMUM_CODE_POINTS = 600;

    private final String whatWentWell;
    private final String whatWentPoorly;
    private final String whatWouldYouDoDifferently;

    private JournalAnswers(
            String whatWentWell,
            String whatWentPoorly,
            String whatWouldYouDoDifferently) {
        this.whatWentWell = UnicodeText.required(
                whatWentWell,
                MAXIMUM_CODE_POINTS,
                "What went well today?");
        this.whatWentPoorly = UnicodeText.required(
                whatWentPoorly,
                MAXIMUM_CODE_POINTS,
                "What went poorly?");
        this.whatWouldYouDoDifferently = UnicodeText.required(
                whatWouldYouDoDifferently,
                MAXIMUM_CODE_POINTS,
                "What would you do differently?");
    }

    public static JournalAnswers of(
            String whatWentWell,
            String whatWentPoorly,
            String whatWouldYouDoDifferently) {
        return new JournalAnswers(whatWentWell, whatWentPoorly, whatWouldYouDoDifferently);
    }

    public String whatWentWell() {
        return whatWentWell;
    }

    public String whatWentPoorly() {
        return whatWentPoorly;
    }

    public String whatWouldYouDoDifferently() {
        return whatWouldYouDoDifferently;
    }
}

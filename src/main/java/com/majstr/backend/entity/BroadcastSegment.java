package com.majstr.backend.entity;

/**
 * Who a broadcast is for — the funnel step a master is stuck on. The rules themselves live in
 * {@code AudienceRules}, which places every master in AT MOST ONE segment (the earliest step he has
 * not passed), so two sends to two segments can never reach the same person twice.
 */
public enum BroadcastSegment {

    UNVERIFIED("Не підтвердили пошту",
            "Пошта не підтверджена · зареєстровані 3+ дні тому",
            "Підтвердити пошту"),
    NO_OBJECT("Немає жодного об'єкта",
            "Пошта підтверджена · жодного об'єкта · зареєстровані 3+ дні тому",
            "Відкрити Майстр"),
    NO_ESTIMATE("Є об'єкт, немає кошторису",
            "Пошта підтверджена · є об'єкт · жодного кошторису · зареєстровані 3+ дні тому",
            "Відкрити Майстр"),
    EMPTY_ESTIMATE("Кошторис майже порожній",
            "Пошта підтверджена · є кошторис, але в кожному менше 5 позицій · зареєстровані 3+ дні тому",
            "Відкрити кошторис"),
    INACTIVE("Не заходили 30+ днів",
            "Пошта підтверджена · є кошторис із 5+ позицій · не заходили 30+ днів");

    private final String label;
    private final String criteria;
    private final String buttonLabel;

    BroadcastSegment(String label, String criteria, String buttonLabel) {
        this.label = label;
        this.criteria = criteria;
        this.buttonLabel = buttonLabel;
    }

    BroadcastSegment(String label, String criteria) {
        this(label, criteria, "Відкрити Майстр");
    }

    public String label() { return label; }

    public String criteria() { return criteria; }

    public String buttonLabel() { return buttonLabel; }
}

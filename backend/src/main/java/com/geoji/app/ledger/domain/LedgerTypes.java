package com.geoji.app.ledger.domain;

public final class LedgerTypes {
    private LedgerTypes() {}
    public enum Type { EXPENSE, INCOME }
    public enum Source { MANUAL, RECURRING }
    public enum Method { CREDIT_CARD, DEBIT_CARD, CASH, BANK_TRANSFER, EASY_PAY, OTHER }
    public enum Status { ACTIVE, PAUSED, CANCELLED }
    public enum Category {
        FOOD(Type.EXPENSE, "식비"), TRANSPORT(Type.EXPENSE, "교통"), SHOPPING(Type.EXPENSE, "쇼핑"),
        HOUSING(Type.EXPENSE, "주거·공과금"), SUBSCRIPTION(Type.EXPENSE, "통신·구독"),
        LEISURE(Type.EXPENSE, "문화·여가"), HEALTH(Type.EXPENSE, "건강·운동"),
        EDUCATION(Type.EXPENSE, "교육"), GIFT(Type.EXPENSE, "경조사·선물"), ETC_EXPENSE(Type.EXPENSE, "기타"),
        SALARY(Type.INCOME, "급여"), ALLOWANCE(Type.INCOME, "용돈"), SIDE_INCOME(Type.INCOME, "부수입"),
        REFUND(Type.INCOME, "환급·환불"), FINANCIAL_INCOME(Type.INCOME, "금융수익"), ETC_INCOME(Type.INCOME, "기타");
        public final Type type;
        public final String label;
        Category(Type type, String label) { this.type = type; this.label = label; }
    }
}

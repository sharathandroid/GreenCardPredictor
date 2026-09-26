package org.innovativebrains.greencardpredictor.model;

public enum EbCategory {
    EB1,
    EB2,
    EB3;

    public static EbCategory fromString(String category) {
        if (category == null) return null;
        category = category.toUpperCase();
        if (category.contains("1ST") || category.contains("EB1")) return EB1;
        if (category.contains("2ND") || category.contains("EB2")) return EB2;
        if (category.contains("3RD") || category.contains("EB3")) return EB3;
        return null;
    }
}

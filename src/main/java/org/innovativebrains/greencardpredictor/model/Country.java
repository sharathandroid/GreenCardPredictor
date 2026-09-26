package org.innovativebrains.greencardpredictor.model;

public enum Country {
    INDIA,
    CHINA,
    PHILIPPINES,
    MEXICO,
    BRAZIL,
    CUBA,
    IRAN,
    NORTH_KOREA,
    SYRIA,
    VENEZUELA,
    AFGHANISTAN,
    BELARUS,
    MYANMAR,
    NICARAGUA,
    RUSSIA,
    ROW; // Rest of World

    public static Country fromString(String country) {
        if (country == null) return ROW;
        country = country.toUpperCase().trim();
        if (country.contains("INDIA")) return INDIA;
        if (country.contains("CHINA")) return CHINA;
        if (country.contains("PHILIPPINES")) return PHILIPPINES;
        if (country.contains("MEXICO")) return MEXICO;
        if (country.contains("BRAZIL")) return BRAZIL;
        if (country.contains("CUBA")) return CUBA;
        if (country.contains("IRAN")) return IRAN;
        if (country.contains("NORTH KOREA") || country.contains("NORTH_KOREA")) return NORTH_KOREA;
        if (country.contains("SYRIA")) return SYRIA;
        if (country.contains("VENEZUELA")) return VENEZUELA;
        if (country.contains("AFGHANISTAN")) return AFGHANISTAN;
        if (country.contains("BELARUS")) return BELARUS;
        if (country.contains("MYANMAR")) return MYANMAR;
        if (country.contains("NICARAGUA")) return NICARAGUA;
        if (country.contains("RUSSIA")) return RUSSIA;
        if (country.contains("REST OF THE WORLD") || country.contains("ROW")) return ROW;
        return ROW;
    }
}

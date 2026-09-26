package org.innovativebrains.greencardpredictor.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum Country {
    // Primary / Standard track countries
    INDIA,
    CHINA,
    PHILIPPINES,
    MEXICO,
    ROW, // Rest of World

    // Restricted countries (75 countries with immigrant visa restrictions + existing restricted)
    AFGHANISTAN,
    ALBANIA,
    ALGERIA,
    ANTIGUA_AND_BARBUDA,
    ARMENIA,
    AZERBAIJAN,
    BAHAMAS,
    BANGLADESH,
    BARBADOS,
    BELARUS,
    BELIZE,
    BHUTAN,
    BOSNIA_AND_HERZEGOVINA,
    BRAZIL,
    CAMBODIA,
    CAMEROON,
    CAPE_VERDE,
    COLOMBIA,
    CUBA,
    DEMOCRATIC_REPUBLIC_OF_CONGO,
    DOMINICA,
    EGYPT,
    ERITREA,
    ETHIOPIA,
    FIJI,
    GAMBIA,
    GEORGIA,
    GHANA,
    GRENADA,
    GUATEMALA,
    GUINEA,
    HAITI,
    IRAN,
    IRAQ,
    IVORY_COAST,
    JAMAICA,
    JORDAN,
    KAZAKHSTAN,
    KOSOVO,
    KUWAIT,
    KYRGYZSTAN,
    LAOS,
    LEBANON,
    LIBERIA,
    LIBYA,
    MOLDOVA,
    MONGOLIA,
    MONTENEGRO,
    MOROCCO,
    MYANMAR,
    NEPAL,
    NICARAGUA,
    NIGERIA,
    NORTH_KOREA,
    NORTH_MACEDONIA,
    PAKISTAN,
    REPUBLIC_OF_CONGO,
    RUSSIA,
    RWANDA,
    SAINT_KITTS_AND_NEVIS,
    SAINT_LUCIA,
    SAINT_VINCENT_AND_THE_GRENADINES,
    SENEGAL,
    SIERRA_LEONE,
    SOMALIA,
    SOUTH_SUDAN,
    SUDAN,
    SYRIA,
    TANZANIA,
    THAILAND,
    TOGO,
    TUNISIA,
    UGANDA,
    URUGUAY,
    UZBEKISTAN,
    VENEZUELA,
    YEMEN;

    private static final Set<Country> RESTRICTED_COUNTRIES = Collections.unmodifiableSet(EnumSet.of(
        AFGHANISTAN, ALBANIA, ALGERIA, ANTIGUA_AND_BARBUDA, ARMENIA,
        AZERBAIJAN, BAHAMAS, BANGLADESH, BARBADOS, BELARUS,
        BELIZE, BHUTAN, BOSNIA_AND_HERZEGOVINA, BRAZIL, CAMBODIA,
        CAMEROON, CAPE_VERDE, COLOMBIA, CUBA, DEMOCRATIC_REPUBLIC_OF_CONGO,
        DOMINICA, EGYPT, ERITREA, ETHIOPIA, FIJI,
        GAMBIA, GEORGIA, GHANA, GRENADA, GUATEMALA,
        GUINEA, HAITI, IRAN, IRAQ, IVORY_COAST,
        JAMAICA, JORDAN, KAZAKHSTAN, KOSOVO, KUWAIT,
        KYRGYZSTAN, LAOS, LEBANON, LIBERIA, LIBYA,
        MOLDOVA, MONGOLIA, MONTENEGRO, MOROCCO, MYANMAR,
        NEPAL, NICARAGUA, NIGERIA, NORTH_KOREA, NORTH_MACEDONIA,
        PAKISTAN, REPUBLIC_OF_CONGO, RUSSIA, RWANDA, SAINT_KITTS_AND_NEVIS,
        SAINT_LUCIA, SAINT_VINCENT_AND_THE_GRENADINES, SENEGAL, SIERRA_LEONE, SOMALIA,
        SOUTH_SUDAN, SUDAN, SYRIA, TANZANIA, THAILAND,
        TOGO, TUNISIA, UGANDA, URUGUAY, UZBEKISTAN,
        VENEZUELA, YEMEN
    ));

    public boolean isRestricted() {
        return RESTRICTED_COUNTRIES.contains(this);
    }

    @JsonCreator
    public static Country fromString(String country) {
        if (country == null) return ROW;
        String c = country.trim().toUpperCase().replace("_", " ");

        // Priority multi-word / ambiguous checks first
        if (c.contains("REST OF THE WORLD") || c.contains("ROW") || c.contains("ALL OTHER") || c.contains("ALL COUNTRIES") || c.contains("ALL CHARGEABILITY")) return ROW;
        if (c.contains("DEMOCRATIC REPUBLIC OF CONGO") || c.contains("DEMOCRATIC REPUBLIC OF THE CONGO") || c.contains("DRC") || c.contains("CONGO, DEM") || c.contains("CONGO (DEM")) return DEMOCRATIC_REPUBLIC_OF_CONGO;
        if (c.contains("REPUBLIC OF CONGO") || c.contains("REPUBLIC OF THE CONGO") || c.contains("CONGO, REP") || c.contains("CONGO (BRAZZAVILLE)")) return REPUBLIC_OF_CONGO;
        if (c.contains("SOUTH SUDAN")) return SOUTH_SUDAN;
        if (c.contains("SUDAN")) return SUDAN;
        if (c.contains("NORTH KOREA") || c.contains("DPRK")) return NORTH_KOREA;
        if (c.contains("NORTH MACEDONIA") || c.contains("MACEDONIA")) return NORTH_MACEDONIA;
        if (c.contains("ANTIGUA")) return ANTIGUA_AND_BARBUDA;
        if (c.contains("BOSNIA")) return BOSNIA_AND_HERZEGOVINA;
        if (c.contains("CAPE VERDE") || c.contains("CABO VERDE")) return CAPE_VERDE;
        if (c.contains("COTE D") || c.contains("CÔTE D") || c.contains("IVORY COAST")) return IVORY_COAST;
        if (c.contains("SAINT KITTS") || c.contains("ST. KITTS") || c.contains("ST KITTS")) return SAINT_KITTS_AND_NEVIS;
        if (c.contains("SAINT LUCIA") || c.contains("ST. LUCIA") || c.contains("ST LUCIA")) return SAINT_LUCIA;
        if (c.contains("SAINT VINCENT") || c.contains("ST. VINCENT") || c.contains("ST VINCENT")) return SAINT_VINCENT_AND_THE_GRENADINES;
        if (c.contains("SIERRA LEONE")) return SIERRA_LEONE;
        if (c.contains("MYANMAR") || c.contains("BURMA")) return MYANMAR;
        if (c.contains("KYRGYZ")) return KYRGYZSTAN;
        if (c.contains("LAOS") || c.contains("LAO PDR") || c.contains("LAO PEOPLE")) return LAOS;
        if (c.contains("RUSSIAN") || c.contains("RUSSIA")) return RUSSIA;
        if (c.contains("DOMINICAN")) return ROW; // Dominican Republic is NOT Dominica
        if (c.contains("DOMINICA")) return DOMINICA;
        if (c.contains("EQUATORIAL GUINEA") || c.contains("GUINEA-BISSAU") || c.contains("GUINEA BISSAU") || c.contains("PAPUA")) return ROW;
        if (c.contains("GUINEA")) return GUINEA;
        if (c.contains("GAMBIA")) return GAMBIA;
        if (c.contains("BAHAMAS")) return BAHAMAS;

        // Specific major countries
        if (c.contains("INDIA")) return INDIA;
        if (c.contains("CHINA")) return CHINA;
        if (c.contains("PHILIPPINES")) return PHILIPPINES;
        if (c.contains("MEXICO")) return MEXICO;
        if (c.contains("VENEZUELA")) return VENEZUELA;

        // Direct enum name check
        try {
            return Country.valueOf(c.replace(" ", "_"));
        } catch (IllegalArgumentException ignored) {}

        // Remaining countries check by substring
        if (c.contains("AFGHANISTAN")) return AFGHANISTAN;
        if (c.contains("ALBANIA")) return ALBANIA;
        if (c.contains("ALGERIA")) return ALGERIA;
        if (c.contains("ARMENIA")) return ARMENIA;
        if (c.contains("AZERBAIJAN")) return AZERBAIJAN;
        if (c.contains("BANGLADESH")) return BANGLADESH;
        if (c.contains("BARBADOS")) return BARBADOS;
        if (c.contains("BELARUS")) return BELARUS;
        if (c.contains("BELIZE")) return BELIZE;
        if (c.contains("BHUTAN")) return BHUTAN;
        if (c.contains("BRAZIL")) return BRAZIL;
        if (c.contains("CAMBODIA")) return CAMBODIA;
        if (c.contains("CAMEROON")) return CAMEROON;
        if (c.contains("COLOMBIA")) return COLOMBIA;
        if (c.contains("CUBA")) return CUBA;
        if (c.contains("EGYPT")) return EGYPT;
        if (c.contains("ERITREA")) return ERITREA;
        if (c.contains("ETHIOPIA")) return ETHIOPIA;
        if (c.contains("FIJI")) return FIJI;
        if (c.contains("GEORGIA")) return GEORGIA;
        if (c.contains("GHANA")) return GHANA;
        if (c.contains("GRENADA")) return GRENADA;
        if (c.contains("GUATEMALA")) return GUATEMALA;
        if (c.contains("HAITI")) return HAITI;
        if (c.contains("IRAN")) return IRAN;
        if (c.contains("IRAQ")) return IRAQ;
        if (c.contains("JAMAICA")) return JAMAICA;
        if (c.contains("JORDAN")) return JORDAN;
        if (c.contains("KAZAKHSTAN")) return KAZAKHSTAN;
        if (c.contains("KOSOVO")) return KOSOVO;
        if (c.contains("KUWAIT")) return KUWAIT;
        if (c.contains("LEBANON")) return LEBANON;
        if (c.contains("LIBERIA")) return LIBERIA;
        if (c.contains("LIBYA")) return LIBYA;
        if (c.contains("MOLDOVA")) return MOLDOVA;
        if (c.contains("MONGOLIA")) return MONGOLIA;
        if (c.contains("MONTENEGRO")) return MONTENEGRO;
        if (c.contains("MOROCCO")) return MOROCCO;
        if (c.contains("NEPAL")) return NEPAL;
        if (c.contains("NICARAGUA")) return NICARAGUA;
        if (c.contains("NIGERIA")) return NIGERIA;
        if (c.contains("PAKISTAN")) return PAKISTAN;
        if (c.contains("RWANDA")) return RWANDA;
        if (c.contains("SENEGAL")) return SENEGAL;
        if (c.contains("SOMALIA")) return SOMALIA;
        if (c.contains("SYRIA")) return SYRIA;
        if (c.contains("TANZANIA")) return TANZANIA;
        if (c.contains("THAILAND")) return THAILAND;
        if (c.contains("TOGO")) return TOGO;
        if (c.contains("TUNISIA")) return TUNISIA;
        if (c.contains("UGANDA")) return UGANDA;
        if (c.contains("URUGUAY")) return URUGUAY;
        if (c.contains("UZBEKISTAN")) return UZBEKISTAN;
        if (c.contains("YEMEN")) return YEMEN;

        return ROW;
    }
}

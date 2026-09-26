package org.innovativebrains.greencardpredictor.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * NOTE ON RESTRICTION DATA (updated 2026-09-25):
 * ------------------------------------------------------------------------
 * The previous version of this enum marked 75 countries as "restricted"
 * based on the Department of State's Jan 21, 2026 pause on immigrant visa
 * issuance for nationals deemed at high risk of public-benefits reliance
 * (the "public charge reassessment" pause).
 *
 * That specific policy was VACATED BY COURT ORDER on August 21, 2026
 * (the court held it exceeded DOS's statutory authority) and is no longer
 * in force as a country-specific bar. Continuing to model those 75
 * countries as flatly "restricted" is itself a bug now, not a feature.
 *
 * The restriction that IS still legally operative (subject to ongoing
 * litigation) is Presidential Proclamation 10998 (signed Dec 16, 2025,
 * effective Jan 1, 2026) -- the "travel ban" -- which suspends entry and/or
 * visa issuance for a different set of ~39 countries. This is what
 * RESTRICTED_COUNTRIES now reflects. See PREDICTION_STRATEGY.md section on
 * "Current Restriction Model" for full citations.
 *
 * Separately, DOS ran a WORLDWIDE pause on immigrant visa *interviews*
 * for consular training purposes starting ~Aug 25, 2026, expected (but not
 * guaranteed) to lift by mid-September 2026. That pause primarily affects
 * FAMILY-based cases, not employment-based ones, so it is modeled as an
 * input to the FB-to-EB spillover calculation (see PredictionService),
 * not as a per-country EB restriction here.
 *
 * IMPORTANT: This area of law is changing roughly monthly via new
 * proclamations and litigation. Treat RESTRICTED_COUNTRIES as a snapshot
 * dated 2026-09-25, not a permanent fact. Ideally this table should be
 * externalized to a config file the operator can update without a
 * redeploy -- see the gap noted in GAPS_AND_FIXES.md.
 * ------------------------------------------------------------------------
 */
public enum Country {
    // Primary / Standard track countries (each independently subject to the
    // per-country 7% cap; none of these five are currently travel-banned)
    INDIA,
    CHINA,
    PHILIPPINES,
    MEXICO,
    BRAZIL, // NOTE: Brazil was on the now-vacated 75-country public-charge
            // list and was previously (incorrectly, as of today) marked
            // restricted here. It is NOT on the current 39-country travel
            // ban list, so it is now treated as a normal, non-restricted
            // country with its own 7% cap, consistent with
            // PREDICTION_STRATEGY.md's original (and correct) description.
    ROW, // Rest of World

    // ---- Presidential Proclamation 10998 (eff. Jan 1, 2026): FULL suspension
    // of immigrant AND nonimmigrant visa issuance ----
    AFGHANISTAN,
    MYANMAR, // "Burma"
    BURKINA_FASO,
    CHAD,
    REPUBLIC_OF_CONGO,
    EQUATORIAL_GUINEA,
    ERITREA,
    HAITI,
    IRAN,
    LAOS,
    LIBYA,
    MALI,
    NIGER,
    SIERRA_LEONE,
    SOMALIA,
    SOUTH_SUDAN,
    SUDAN,
    SYRIA,
    YEMEN,
    PALESTINIAN_AUTHORITY,

    // ---- Proclamation 10998: PARTIAL suspension -- immigrant visas AND
    // B-1/B-2 and F/M/J categories suspended (other nonimmigrant work visas,
    // e.g. H-1B, remain generally available) ----
    ANGOLA,
    ANTIGUA_AND_BARBUDA,
    BENIN,
    BURUNDI,
    IVORY_COAST,
    CUBA,
    DOMINICA,
    GABON,
    GAMBIA,
    MALAWI,
    MAURITANIA,
    NIGERIA,
    SENEGAL,
    TANZANIA,
    TOGO,
    TONGA,
    VENEZUELA,
    ZAMBIA,
    ZIMBABWE,

    // ---- Proclamation 10998: single-category restriction -- immigrant
    // visas only (nonimmigrant categories unaffected) ----
    TURKMENISTAN,

    // ---- Countries formerly on the (now-vacated) 75-country public-charge
    // list that are NOT on the current travel-ban list. Kept as ordinary,
    // non-restricted enum values so existing Excel data keyed to these
    // countries still resolves correctly. ----
    ALBANIA,
    ALGERIA,
    ARMENIA,
    AZERBAIJAN,
    BAHAMAS,
    BANGLADESH,
    BARBADOS,
    BELARUS,
    BELIZE,
    BHUTAN,
    BOSNIA_AND_HERZEGOVINA,
    CAMBODIA,
    CAMEROON,
    CAPE_VERDE,
    COLOMBIA,
    DEMOCRATIC_REPUBLIC_OF_CONGO,
    EGYPT,
    ETHIOPIA,
    FIJI,
    GEORGIA,
    GHANA,
    GRENADA,
    GUATEMALA,
    GUINEA,
    IRAQ,
    JAMAICA,
    JORDAN,
    KAZAKHSTAN,
    KOSOVO,
    KUWAIT,
    KYRGYZSTAN,
    LEBANON,
    LIBERIA,
    MOLDOVA,
    MONGOLIA,
    MONTENEGRO,
    MOROCCO,
    NEPAL,
    NICARAGUA,
    NORTH_KOREA,
    NORTH_MACEDONIA,
    PAKISTAN,
    RUSSIA,
    RWANDA,
    SAINT_KITTS_AND_NEVIS,
    SAINT_LUCIA,
    SAINT_VINCENT_AND_THE_GRENADINES,
    THAILAND,
    TUNISIA,
    UGANDA,
    URUGUAY,
    UZBEKISTAN;

    /**
     * Countries where, as of 2026-09-25, immigrant-visa issuance (the thing
     * this predictor cares about) is suspended under Proclamation 10998 --
     * either as part of the "full" ban, the "partial" ban (which still
     * suspends immigrant categories), or the single-category (Turkmenistan)
     * restriction. All three groups block green-card issuance, so all are
     * treated the same way here; PREDICTION_STRATEGY.md documents the
     * distinction for anyone who needs it (e.g. to separately model
     * nonimmigrant visas later).
     */
    private static final Set<Country> RESTRICTED_COUNTRIES = Collections.unmodifiableSet(EnumSet.of(
        // Full suspension (19 + Palestinian Authority)
        AFGHANISTAN, MYANMAR, BURKINA_FASO, CHAD, REPUBLIC_OF_CONGO, EQUATORIAL_GUINEA,
        ERITREA, HAITI, IRAN, LAOS, LIBYA, MALI, NIGER, SIERRA_LEONE, SOMALIA,
        SOUTH_SUDAN, SUDAN, SYRIA, YEMEN, PALESTINIAN_AUTHORITY,
        // Partial suspension (19) -- immigrant visas included
        ANGOLA, ANTIGUA_AND_BARBUDA, BENIN, BURUNDI, IVORY_COAST, CUBA, DOMINICA,
        GABON, GAMBIA, MALAWI, MAURITANIA, NIGERIA, SENEGAL, TANZANIA, TOGO, TONGA,
        VENEZUELA, ZAMBIA, ZIMBABWE,
        // Single-category (1)
        TURKMENISTAN
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
        if (c.contains("EQUATORIAL GUINEA")) return EQUATORIAL_GUINEA;
        if (c.contains("GUINEA-BISSAU") || c.contains("GUINEA BISSAU") || c.contains("PAPUA")) return ROW;
        if (c.contains("GUINEA")) return GUINEA;
        if (c.contains("GAMBIA")) return GAMBIA;
        if (c.contains("BAHAMAS")) return BAHAMAS;
        if (c.contains("BURKINA")) return BURKINA_FASO;
        if (c.contains("PALESTIN") || c.contains("GAZA") || c.contains("WEST BANK")) return PALESTINIAN_AUTHORITY;
        if (c.contains("TURKMENISTAN")) return TURKMENISTAN;
        if (c.contains("TONGA")) return TONGA;

        // Specific major / standard-track countries
        if (c.contains("INDIA")) return INDIA;
        if (c.contains("CHINA")) return CHINA;
        if (c.contains("PHILIPPINES")) return PHILIPPINES;
        if (c.contains("MEXICO")) return MEXICO;
        if (c.contains("BRAZIL")) return BRAZIL;
        if (c.contains("VENEZUELA")) return VENEZUELA;

        // Direct enum name check (handles well-formed names like "CHAD", "MALI", "NIGER", "GABON", etc.)
        try {
            return Country.valueOf(c.replace(" ", "_"));
        } catch (IllegalArgumentException ignored) {}

        // Remaining countries checked by substring
        if (c.contains("AFGHANISTAN")) return AFGHANISTAN;
        if (c.contains("ALBANIA")) return ALBANIA;
        if (c.contains("ALGERIA")) return ALGERIA;
        if (c.contains("ANGOLA")) return ANGOLA;
        if (c.contains("ARMENIA")) return ARMENIA;
        if (c.contains("AZERBAIJAN")) return AZERBAIJAN;
        if (c.contains("BANGLADESH")) return BANGLADESH;
        if (c.contains("BARBADOS")) return BARBADOS;
        if (c.contains("BELARUS")) return BELARUS;
        if (c.contains("BELIZE")) return BELIZE;
        if (c.contains("BENIN")) return BENIN;
        if (c.contains("BHUTAN")) return BHUTAN;
        if (c.contains("BURUNDI")) return BURUNDI;
        if (c.contains("CAMBODIA")) return CAMBODIA;
        if (c.contains("CAMEROON")) return CAMEROON;
        if (c.contains("CHAD")) return CHAD;
        if (c.contains("COLOMBIA")) return COLOMBIA;
        if (c.contains("CUBA")) return CUBA;
        if (c.contains("EGYPT")) return EGYPT;
        if (c.contains("ERITREA")) return ERITREA;
        if (c.contains("ETHIOPIA")) return ETHIOPIA;
        if (c.contains("FIJI")) return FIJI;
        if (c.contains("GABON")) return GABON;
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
        if (c.contains("MALAWI")) return MALAWI;
        if (c.contains("MALI")) return MALI;
        if (c.contains("MAURITANIA")) return MAURITANIA;
        if (c.contains("MOLDOVA")) return MOLDOVA;
        if (c.contains("MONGOLIA")) return MONGOLIA;
        if (c.contains("MONTENEGRO")) return MONTENEGRO;
        if (c.contains("MOROCCO")) return MOROCCO;
        if (c.contains("NEPAL")) return NEPAL;
        if (c.contains("NICARAGUA")) return NICARAGUA;
        // NOTE: "NIGERIA" contains "NIGER" as a substring, so the more specific
        // match (NIGERIA) must be checked first or every Nigeria record would
        // be miscoded as Niger.
        if (c.contains("NIGERIA")) return NIGERIA;
        if (c.contains("NIGER")) return NIGER;
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
        if (c.contains("ZAMBIA")) return ZAMBIA;
        if (c.contains("ZIMBABWE")) return ZIMBABWE;

        // Fallback: unrecognized chargeability area is treated as Rest of World.
        return ROW;
    }
}

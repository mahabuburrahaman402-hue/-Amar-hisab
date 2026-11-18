package com.amarhisab;

import java.util.Locale;

/**
 * Pure helpers for category-scoped ledgers and independent cashboxes.
 * Legacy transactions without an explicit category remain unassigned until
 * the user deliberately classifies them; they are never silently merged.
 */
public final class CategoryAccounting {
    public static final String CATEGORY_RENT = "rent";
    public static final String CATEGORY_SHOP = "shop";
    public static final String CATEGORY_PERSONAL = "personal";
    public static final String CATEGORY_OTHER = "other";
    public static final String CATEGORY_UNASSIGNED = "unassigned";
    public static final String FILTER_ALL = "all";

    private CategoryAccounting() {}

    public static String normalizeCategoryId(String value) {
        if (value == null) return CATEGORY_UNASSIGNED;
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty() ||
            !normalized.matches("[a-z0-9][a-z0-9:_-]{0,63}")) {
            return CATEGORY_UNASSIGNED;
        }
        return normalized;
    }

    public static boolean belongsToCategory(String recordCategory, String selectedCategory) {
        if (selectedCategory == null || selectedCategory.trim().isEmpty()) return false;
        String selected = selectedCategory.trim().toLowerCase(Locale.US);
        if (FILTER_ALL.equals(selected)) return true;
        return normalizeCategoryId(recordCategory).equals(normalizeCategoryId(selected));
    }

    public static boolean sameLedgerIdentity(String categoryA, String accountIdA,
                                             String accountNameA, String categoryB,
                                             String accountIdB, String accountNameB) {
        if (!normalizeCategoryId(categoryA).equals(normalizeCategoryId(categoryB))) return false;
        String idA = accountIdA == null ? "" : accountIdA.trim();
        String idB = accountIdB == null ? "" : accountIdB.trim();
        if (!idA.isEmpty() || !idB.isEmpty()) {
            return !idA.isEmpty() && idA.equals(idB);
        }
        String nameA = accountNameA == null ? "" : accountNameA.trim();
        String nameB = accountNameB == null ? "" : accountNameB.trim();
        return !nameA.isEmpty() && nameA.equalsIgnoreCase(nameB);
    }

    /**
     * Returns only the movement caused by a transfer between two cashboxes.
     * A transfer is not income or expense: source decreases, destination rises.
     */
    public static double cashboxTransferDelta(String cashboxId, String fromCashboxId,
                                              String toCashboxId, double amount) {
        if (cashboxId == null || fromCashboxId == null || toCashboxId == null ||
            !isValidCashboxAmount(amount)) return 0;
        String account = cashboxId.trim();
        String from = fromCashboxId.trim();
        String to = toCashboxId.trim();
        if (account.isEmpty() || from.isEmpty() || to.isEmpty() || from.equals(to)) return 0;
        if (account.equals(from)) return -amount;
        if (account.equals(to)) return amount;
        return 0;
    }

    public static double cashboxBalance(double openingBalance, double... movements) {
        if (!Double.isFinite(openingBalance) || movements == null) return 0;
        double balance = openingBalance;
        for (double movement : movements) {
            if (!Double.isFinite(movement)) return 0;
            balance += movement;
            if (!Double.isFinite(balance)) return 0;
        }
        return balance;
    }

    public static boolean isValidCashboxAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }

    public static boolean isValidOpeningBalance(double amount) {
        return Double.isFinite(amount) && amount >= 0;
    }

    public static boolean isValidCashboxTransfer(String fromCashboxId, String toCashboxId,
                                                  double amount, double sourceBalance) {
        if (fromCashboxId == null || toCashboxId == null ||
            !isValidCashboxAmount(amount) || !isValidOpeningBalance(sourceBalance)) return false;
        String from = fromCashboxId.trim();
        String to = toCashboxId.trim();
        return !from.isEmpty() && !to.isEmpty() && !from.equals(to) &&
            sourceBalance >= amount;
    }

    public static boolean isTransferType(String type) {
        return "অ্যাকাউন্ট স্থানান্তর".equals(type) ||
            "CASHBOX_TRANSFER".equals(type);
    }
}

package com.amarhisab;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class AccountingMathTest {
    private static final double EPSILON = 0.000001;

    @Test public void multipliesQuantityByUnitPrice() {
        assertEquals(360.0, AccountingMath.lineTotal(3, 120), EPSILON);
    }

    @Test public void rejectsIncompleteOrInvalidEntryLines() {
        assertEquals(true, AccountingMath.isValidLineItem("ডিম", 3, 12));
        assertEquals(false, AccountingMath.isValidLineItem("", 3, 12));
        assertEquals(false, AccountingMath.isValidLineItem("ডিম", 0, 12));
        assertEquals(false, AccountingMath.isValidLineItem("ডিম", 3, -12));
        assertEquals(false, AccountingMath.isValidLineItem("ডিম", Double.NaN, 12));
        assertEquals(false, AccountingMath.isValidLineItem("ডিম", Double.POSITIVE_INFINITY, 12));
    }

    @Test public void lineTotalSafelyHandlesInvalidAndOverflowValues() {
        assertEquals(0.0, AccountingMath.lineTotal(-1, 12), EPSILON);
        assertEquals(0.0, AccountingMath.lineTotal(Double.NaN, 12), EPSILON);
        assertEquals(0.0, AccountingMath.lineTotal(Double.MAX_VALUE, 2), EPSILON);
    }

    @Test public void calculatesWeightedAveragePurchaseCost() {
        assertEquals(15.0, AccountingMath.weightedAverageCost(10, 12, 10, 18), EPSILON);
    }

    @Test public void calculatesCostOfGoodsSold() {
        assertEquals(75.0, AccountingMath.costOfGoodsSold(5, 15), EPSILON);
    }

    @Test public void calculatesGrossProfit() {
        assertEquals(125.0, AccountingMath.grossProfit(200, 75), EPSILON);
    }

    @Test public void parsesWesternBengaliAndArabicIndicAmountDigits() {
        assertEquals(1250.50, AccountingMath.parseAmount("1250.50"), EPSILON);
        assertEquals(1250.50, AccountingMath.parseAmount("১২৫০,৫০"), EPSILON);
        assertEquals(1250.50, AccountingMath.parseAmount("١٢٥٠٫٥٠"), EPSILON);
        assertEquals(0.0, AccountingMath.parseAmount(""), EPSILON);
        assertEquals(0.0, AccountingMath.parseAmount("১২..৫"), EPSILON);
        assertEquals(0.0, AccountingMath.parseAmount("9999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999"), EPSILON);
    }

    @Test public void transactionAmountMustBeFiniteAndPositive() {
        assertTrue(AccountingMath.isValidTransactionAmount(0.01));
        assertFalse(AccountingMath.isValidTransactionAmount(0));
        assertFalse(AccountingMath.isValidTransactionAmount(-1));
        assertFalse(AccountingMath.isValidTransactionAmount(Double.NaN));
        assertFalse(AccountingMath.isValidTransactionAmount(Double.POSITIVE_INFINITY));
    }

    @Test public void vacantRentalUnitsAcceptBanglaAndEnglishLabels() {
        assertTrue(AccountingMath.isVacantTenantName("খালি"));
        assertTrue(AccountingMath.isVacantTenantName("  Vacant  "));
        assertTrue(AccountingMath.isVacantTenantName("VACANT"));
        assertFalse(AccountingMath.isVacantTenantName("Karim"));
        assertFalse(AccountingMath.isVacantTenantName(""));
        assertFalse(AccountingMath.isVacantTenantName(null));
    }

    @Test public void refundableDepositReceiptAndRefundAreNotBusinessIncome() {
        assertFalse(AccountingMath.isBusinessIncomeRecord("জামানত জমা", false));
        assertFalse(AccountingMath.isBusinessIncomeRecord("জামানত ফেরত", false));
        assertFalse(AccountingMath.isBusinessIncomeRecord("জামানত সমন্বয় / ফেরত", false));
        assertTrue(AccountingMath.isBusinessIncomeRecord("আয়", false));
    }

    @Test public void stockCreditSaleCountsAsBothReceivableAndBusinessIncome() {
        assertTrue(AccountingMath.isBusinessIncomeRecord("পাওনা", true));
        assertTrue(AccountingMath.isBusinessIncomeRecord("আয়", false));
        assertFalse(AccountingMath.isBusinessIncomeRecord("পাওনা", false));
        assertFalse(AccountingMath.isBusinessIncomeRecord("খরচ", false));
    }

    @Test public void partyLedgerTracksDueAndPaymentsPerParty() {
        double balance = 0;
        balance = AccountingMath.applyPartyTransaction(balance, "পাওনা", 500);
        balance = AccountingMath.applyPartyTransaction(balance, "জমা", 200);
        assertEquals(300.0, balance, EPSILON);
        balance = AccountingMath.applyPartyTransaction(balance, "পরিশোধ", 350);
        assertEquals(-50.0, balance, EPSILON);
    }

    @Test public void nonPartyIncomeAndExpensesDoNotChangePartyBalance() {
        assertEquals(125.0, AccountingMath.applyPartyTransaction(125, "আয়", 40), EPSILON);
        assertEquals(125.0, AccountingMath.applyPartyTransaction(125, "খরচ", 40), EPSILON);
    }

    @Test public void invalidPartyPaymentDoesNotChangeBalance() {
        assertEquals(125.0, AccountingMath.applyPartyTransaction(125, "জমা", -10), EPSILON);
        assertEquals(125.0, AccountingMath.applyPartyTransaction(125, "জমা",
            Double.NaN), EPSILON);
    }

    @Test public void ledgerBalanceHelpersRejectNonFiniteOverflowResults() {
        assertEquals(Double.MAX_VALUE, AccountingMath.applyPartyTransaction(Double.MAX_VALUE,
            "পাওনা", Double.MAX_VALUE), EPSILON);
        assertEquals(-Double.MAX_VALUE, AccountingMath.applyPartyTransaction(-Double.MAX_VALUE,
            "জমা", Double.MAX_VALUE), EPSILON);
        assertEquals(0.0, AccountingMath.outstanding(Double.MAX_VALUE,
            -Double.MAX_VALUE), EPSILON);
        assertEquals(0.0, AccountingMath.remainingReceivable(Double.MAX_VALUE,
            -Double.MAX_VALUE, 0), EPSILON);
        assertEquals(0.0, AccountingMath.excessPayment(0,
            Double.MAX_VALUE, Double.MAX_VALUE), EPSILON);
    }

    @Test public void outstandingNeverBecomesNegative() {
        assertEquals(60.0, AccountingMath.outstanding(100, 40), EPSILON);
        assertEquals(0.0, AccountingMath.outstanding(100, 120), EPSILON);
    }

    @Test public void multiplePartialRentPaymentsReduceOnlyTheirOwnMonth() {
        double november = AccountingMath.rentalOutstanding(12000, 3000, 2500, 1500);
        double december = AccountingMath.rentalOutstanding(12000, 4000, 2000);
        assertEquals(5000.0, november, EPSILON);
        assertEquals(6000.0, december, EPSILON);
        assertEquals(0.0, AccountingMath.rentalOutstanding(12000, 7000, 6000), EPSILON);
    }

    @Test public void rentalPaymentTotalsIgnoreInvalidAmounts() {
        assertEquals(4500.0, AccountingMath.sumRentalPayments(2000, 1500, 1000,
            -500, Double.NaN, Double.POSITIVE_INFINITY), EPSILON);
    }

    @Test public void dashboardReceivableNeverShowsNegativeDue() {
        assertEquals(60.0, AccountingMath.remainingReceivable(100, 25, 15), EPSILON);
        assertEquals(0.0, AccountingMath.remainingReceivable(100, 70, 50), EPSILON);
    }

    @Test public void dashboardShowsExcessPaymentSeparately() {
        assertEquals(0.0, AccountingMath.excessPayment(100, 40, 30), EPSILON);
        assertEquals(20.0, AccountingMath.excessPayment(100, 70, 50), EPSILON);
    }

    @Test public void stockCalculationsRejectInvalidAndOverflowValues() {
        assertEquals(0.0, AccountingMath.weightedAverageCost(-1, 10, 2, 20), EPSILON);
        assertEquals(0.0, AccountingMath.weightedAverageCost(1, 10,
            Double.MAX_VALUE, Double.MAX_VALUE), EPSILON);
        assertEquals(0.0, AccountingMath.costOfGoodsSold(Double.NaN, 10), EPSILON);
        assertEquals(0.0, AccountingMath.costOfGoodsSold(Double.MAX_VALUE, 2), EPSILON);
        assertEquals(0.0, AccountingMath.grossProfit(Double.POSITIVE_INFINITY, 10), EPSILON);
    }

    @Test public void weightedAverageCostIncludesRepeatedPurchases() {
        double average = AccountingMath.weightedAverageCost(10, 12, 10, 18);
        assertEquals(15.0, average, EPSILON);
        assertEquals(16.0, AccountingMath.weightedAverageCost(20, average, 10, 18), EPSILON);
    }

    @Test public void zeroStockHasZeroAverageCost() {
        assertEquals(0.0, AccountingMath.weightedAverageCost(0, 0, 0, 25), EPSILON);
    }

    @Test public void monthlyRentDuplicateMustMatchBuildingWhenUsingLegacyKey() {
        assertEquals(true, AccountingMath.sameMonthlyRentEntry(
            "2026-11|Building A|Shop 1|Karim",
            "2026-11|Building A|Shop 1|Karim",
            "2026-11|Shop 1|Karim", "Building A", "Building A"));
        assertEquals(false, AccountingMath.sameMonthlyRentEntry(
            "2026-11|Shop 1|Karim",
            "2026-11|Building B|Shop 1|Karim",
            "2026-11|Shop 1|Karim", "Building A", "Building B"));
        assertEquals(false, AccountingMath.sameMonthlyRentEntry(
            "2026-11|Building A|Shop 1|Karim",
            "2026-11|Building B|Shop 1|Karim",
            "2026-11|Shop 1|Karim", "Building A", "Building B"));
    }

    @Test public void rentalSetupRejectsInvalidAmountsAndDueDays() {
        assertTrue(AccountingMath.isValidRentalSetup(12000, 50000, 10));
        assertTrue(AccountingMath.isValidRentalSetup(12000, 0, 31));
        assertFalse(AccountingMath.isValidRentalSetup(0, 50000, 10));
        assertFalse(AccountingMath.isValidRentalSetup(-1, 0, 10));
        assertFalse(AccountingMath.isValidRentalSetup(Double.POSITIVE_INFINITY, 0, 10));
        assertFalse(AccountingMath.isValidRentalSetup(12000, Double.NaN, 10));
        assertFalse(AccountingMath.isValidRentalSetup(12000, -1, 10));
        assertFalse(AccountingMath.isValidRentalSetup(12000, 0, 0));
        assertFalse(AccountingMath.isValidRentalSetup(12000, 0, 32));
    }

    @Test public void preventsDuplicateRentalUnitIdentity() {
        assertEquals(true, AccountingMath.sameRentalIdentity(
            "Building A", "Flat 2", "building a", "flat 2"));
        assertEquals(false, AccountingMath.sameRentalIdentity(
            "Building A", "Flat 2", "Building B", "Flat 2"));
        assertEquals(false, AccountingMath.sameRentalIdentity(
            "Building A", "Flat 2", "Building A", "Flat 3"));
    }

    @Test public void validatesRentalEffectiveMonth() {
        assertEquals(true, AccountingMath.isValidYearMonth("2026-11"));
        assertEquals(false, AccountingMath.isValidYearMonth("2026-00"));
        assertEquals(false, AccountingMath.isValidYearMonth("2026-13"));
        assertEquals(false, AccountingMath.isValidYearMonth("26-11"));
        assertEquals(false, AccountingMath.isValidYearMonth(null));
    }

    @Test public void futureRentIncreaseDoesNotChangeCurrentMonthRent() {
        assertEquals(12000.0, AccountingMath.rentAtMonth(12000, "2026-11",
            new String[]{"0000-00", "2026-12"}, new double[]{12000, 13000}), EPSILON);
        assertEquals(13000.0, AccountingMath.rentAtMonth(12000, "2026-12",
            new String[]{"0000-00", "2026-12"}, new double[]{12000, 13000}), EPSILON);
    }

    @Test public void backdatedRentChangeKeepsTheLatestEffectiveRentForCurrentMonth() {
        assertEquals(14000.0, AccountingMath.rentAtMonth(15000, "2026-11",
            new String[]{"0000-00", "2026-06", "2026-10", "2026-12"},
            new double[]{10000, 12000, 14000, 16000}), EPSILON);
        assertEquals(16000.0, AccountingMath.rentAtMonth(15000, "2026-12",
            new String[]{"0000-00", "2026-06", "2026-10", "2026-12"},
            new double[]{10000, 12000, 14000, 16000}), EPSILON);
    }

    @Test public void depositRefundUsesTheTenantWhoOwnsTheDeposit() {
        assertEquals("Karim", AccountingMath.depositOwner("খালি", "Karim"));
        assertEquals("Rahim", AccountingMath.depositOwner("Rahim", ""));
        assertEquals("", AccountingMath.depositOwner(null, null));
    }

    @Test public void rentalExpensesMustMatchTheirBuildingAndUnit() {
        assertEquals(true, AccountingMath.sameRentalExpense(
            "Building A", "Flat 2", "বাড়ি ভাড়া: Building A / Flat 2",
            "Building A", "Flat 2", true));
        assertEquals(false, AccountingMath.sameRentalExpense(
            "Building B", "Flat 2", "বাড়ি ভাড়া: Building A / Flat 2",
            "Building A", "Flat 2", true));
        assertEquals(true, AccountingMath.sameRentalExpense(
            "Building A", "Flat 2", "বাড়ি ভাড়া: Building A / Flat 2",
            "", "", true));
        assertEquals(false, AccountingMath.sameRentalExpense(
            "Building A", "Flat 2", "বাড়ি ভাড়া: Building A / Flat 2",
            "", "", false));
    }

    @Test public void rentalPaymentsMustMatchTenantUnitAndBuilding() {
        assertEquals(true, AccountingMath.sameRentalUnit(
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertEquals(false, AccountingMath.sameRentalUnit(
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building B"));
        assertEquals(false, AccountingMath.sameRentalUnit(
            "Karim", "Shop 1", "Building A", "Rahim", "Shop 1", "Building A"));
        assertEquals(false, AccountingMath.sameRentalUnit(
            "Karim", "Shop 1", "Building A", "Karim", "Shop 2", "Building A"));
    }

    @Test public void rentalPaymentLinksRequireTheSameMonthTenantUnitAndBuilding() {
        assertTrue(AccountingMath.matchesRentalRecordLink("2026-11|A", "2026-11|A",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink("2026-11|A", "2026-12|A",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink("2026-11|A", "2026-11|A",
            "Karim", "Shop 1", "Building A", "Rahim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink("2026-11|A", "2026-11|A",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building B"));
        assertFalse(AccountingMath.matchesRentalRecordLink("", "",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
    }

    @Test public void rentalPaymentsCannotCrossTenantUnitOrBuildingBoundaries() {
        String key = "2026-11|Building A|Shop 1|Karim";
        assertTrue(AccountingMath.matchesRentalRecordLink(key, key,
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink(key,
            "2026-11|Building A|Shop 1|Rahim",
            "Karim", "Shop 1", "Building A", "Rahim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink(key,
            "2026-11|Building A|Shop 2|Karim",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 2", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink(key,
            "2026-11|Building B|Shop 1|Karim",
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building B"));
    }

    @Test public void overpaymentInOneMonthCannotHideAnotherMonthsArrears() {
        double novemberBalance = AccountingMath.rentalOutstanding(10000, 15000);
        double decemberBalance = AccountingMath.rentalOutstanding(10000, 5000);
        assertEquals(0.0, novemberBalance, EPSILON);
        assertEquals(5000.0, decemberBalance, EPSILON);
        assertEquals(5000.0, novemberBalance + decemberBalance, EPSILON);
    }

    @Test public void archivedTenantPaymentsCannotReduceNewTenantsClosingBalance() {
        String oldTenantKey = "2026-11|Building A|Shop 1|Karim";
        String newTenantKey = "2026-11|Building A|Shop 1|Rahim";
        assertTrue(AccountingMath.matchesRentalRecordLink(oldTenantKey, oldTenantKey,
            "Karim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertFalse(AccountingMath.matchesRentalRecordLink(newTenantKey, oldTenantKey,
            "Rahim", "Shop 1", "Building A", "Karim", "Shop 1", "Building A"));
        assertEquals(4000.0, AccountingMath.rentalOutstanding(10000, 6000), EPSILON);
    }

    @Test public void partialPaymentsAreCappedAtTheirOwnMonthlyDue() {
        assertEquals(0.0, AccountingMath.rentalOutstanding(10000, 3000, 3000, 4000), EPSILON);
        assertEquals(2500.0, AccountingMath.rentalOutstanding(10000, 2500, 2500, 2500), EPSILON);
        assertEquals(10000.0, AccountingMath.rentalOutstanding(10000), EPSILON);
    }

    @Test public void rentPostingIdentityIncludesMonthBuildingUnitAndTenant() {
        String novemberKarim = "2026-11|Building A|Shop 1|Karim";
        String decemberKarim = "2026-12|Building A|Shop 1|Karim";
        String novemberRahim = "2026-11|Building A|Shop 1|Rahim";
        String novemberKarimOtherBuilding = "2026-11|Building B|Shop 1|Karim";
        assertFalse(AccountingMath.sameMonthlyRentEntry(
            novemberKarim, decemberKarim, "2026-12|Shop 1|Karim",
            "Building A", "Building A"));
        assertFalse(AccountingMath.sameMonthlyRentEntry(
            novemberKarim, novemberRahim, "2026-11|Shop 1|Rahim",
            "Building A", "Building A"));
        assertFalse(AccountingMath.sameMonthlyRentEntry(
            novemberKarim, novemberKarimOtherBuilding, "2026-11|Shop 1|Karim",
            "Building A", "Building B"));
    }

    @Test public void parsesOnlyValidCompleteAccountingDates() {
        assertEquals("2026-11", AccountingMath.monthFromDate("16-11-2026"));
        assertEquals("", AccountingMath.monthFromDate("31-02-2026"));
        assertEquals("", AccountingMath.monthFromDate("16-13-2026"));
        assertEquals("", AccountingMath.monthFromDate("16-11-2026extra"));
        assertEquals("", AccountingMath.monthFromDate(null));
    }

    @Test public void calculatorUsesNormalOperatorPrecedenceAndParentheses() {
        assertEquals(42.0, AccountingMath.evaluateExpression("3 × (12 + 2)"), EPSILON);
        assertEquals(26.0, AccountingMath.evaluateExpression("2 + 3 * 8"), EPSILON);
        assertEquals(6.0, AccountingMath.evaluateExpression("(20 - 8) ÷ 2"), EPSILON);
    }

    @Test public void calculatorSupportsPercentDecimalsAndBanglaDigits() {
        assertEquals(220.0, AccountingMath.evaluateExpression("200 + 10%"), EPSILON);
        assertEquals(20.0, AccountingMath.evaluateExpression("200 × 10%"), EPSILON);
        assertEquals(12.5, AccountingMath.evaluateExpression("১২,৫"), EPSILON);
        assertEquals(0.5, AccountingMath.evaluateExpression(".5"), EPSILON);
    }

    @Test public void rentalAndDepositRecordsStayOutOfPersonalPartyLedger() {
        assertTrue(AccountingMath.isRentalLedgerRecord(true, false, false, false,
            false, false, false, "", "", "", "", "DEP-1"));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, true, false, false,
            false, false, false, "", "", "", "", ""));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, false, true, false,
            false, false, false, "", "", "CHG-1", "", ""));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, false, false, true,
            false, false, false, "", "", "", "", ""));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, false, false, false,
            true, false, false, "", "", "", "", ""));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, false, false, false,
            false, true, false, "", "RENT-PAY-1", "", "", ""));
        assertTrue(AccountingMath.isRentalLedgerRecord(false, false, false, false,
            false, false, true, "", "", "", "", ""));
    }

    @Test public void ordinaryPersonalPartyTransactionsStayInPersonalPartyLedger() {
        assertFalse(AccountingMath.isRentalLedgerRecord(false, false, false, false,
            false, false, false, "", "", "", "", ""));
        assertFalse(AccountingMath.isRentalLedgerRecord(false, false, false, false,
            false, false, false, null, " ", null, "", null));
    }

    @Test public void calculatorRejectsIncompleteDivideByZeroAndOverflowExpressions() {
        assertThrows(IllegalArgumentException.class, () -> AccountingMath.evaluateExpression("2 +"));
        assertThrows(IllegalArgumentException.class, () -> AccountingMath.evaluateExpression("8 / 0"));
        assertThrows(IllegalArgumentException.class, () -> AccountingMath.evaluateExpression("1e309"));
        assertThrows(IllegalArgumentException.class, () -> AccountingMath.evaluateExpression("(2 + 3"));
    }


    @Test public void categoryIdsAreStableAndLegacyRecordsStayUnassigned() {
        assertEquals("rent", CategoryAccounting.normalizeCategoryId(" RENT "));
        assertEquals(CategoryAccounting.CATEGORY_UNASSIGNED,
            CategoryAccounting.normalizeCategoryId(""));
        assertEquals(CategoryAccounting.CATEGORY_UNASSIGNED,
            CategoryAccounting.normalizeCategoryId(null));
        assertEquals(CategoryAccounting.CATEGORY_UNASSIGNED,
            CategoryAccounting.normalizeCategoryId("../rent"));
    }

    @Test public void categorySearchIsStrictUnlessAllCategoriesIsExplicitlySelected() {
        assertTrue(CategoryAccounting.belongsToCategory("rent", "rent"));
        assertFalse(CategoryAccounting.belongsToCategory("shop", "rent"));
        assertTrue(CategoryAccounting.belongsToCategory("shop", "all"));
        assertTrue(CategoryAccounting.belongsToCategory("", "unassigned"));
        assertFalse(CategoryAccounting.belongsToCategory("rent", ""));
    }

    @Test public void sameNamedAccountsRemainSeparateAcrossCategoriesAndIds() {
        assertTrue(CategoryAccounting.sameLedgerIdentity("rent", "TENANT-1", "Karim",
            "rent", "TENANT-1", "Karim"));
        assertFalse(CategoryAccounting.sameLedgerIdentity("rent", "TENANT-1", "Karim",
            "shop", "TENANT-1", "Karim"));
        assertFalse(CategoryAccounting.sameLedgerIdentity("rent", "TENANT-1", "Karim",
            "rent", "TENANT-2", "Karim"));
        assertTrue(CategoryAccounting.sameLedgerIdentity("personal", "", "Karim",
            "personal", "", "karim"));
    }

    @Test public void cashboxTransferReducesSourceAndIncreasesDestinationOnly() {
        assertEquals(-250.0, CategoryAccounting.cashboxTransferDelta(
            "rent-cash", "rent-cash", "shop-cash", 250), EPSILON);
        assertEquals(250.0, CategoryAccounting.cashboxTransferDelta(
            "shop-cash", "rent-cash", "shop-cash", 250), EPSILON);
        assertEquals(0.0, CategoryAccounting.cashboxTransferDelta(
            "personal-cash", "rent-cash", "shop-cash", 250), EPSILON);
        assertEquals(0.0, CategoryAccounting.cashboxTransferDelta(
            "rent-cash", "rent-cash", "rent-cash", 250), EPSILON);
        assertEquals(0.0, CategoryAccounting.cashboxTransferDelta(
            "rent-cash", "rent-cash", "shop-cash", -250), EPSILON);
    }

    @Test public void cashboxOpeningBalanceAndMovementsAreCheckedForOverflow() {
        assertEquals(1200.0, CategoryAccounting.cashboxBalance(1000, 500, -300), EPSILON);
        assertEquals(0.0, CategoryAccounting.cashboxBalance(Double.MAX_VALUE,
            Double.MAX_VALUE), EPSILON);
        assertEquals(0.0, CategoryAccounting.cashboxBalance(1000, Double.NaN), EPSILON);
    }

    @Test public void cashboxTransfersAreNotIncomeOrExpenseLabels() {
        assertTrue(CategoryAccounting.isTransferType("অ্যাকাউন্ট স্থানান্তর"));
        assertTrue(CategoryAccounting.isTransferType("CASHBOX_TRANSFER"));
        assertFalse(CategoryAccounting.isTransferType("আয়"));
        assertFalse(CategoryAccounting.isTransferType("খরচ"));
    }


    @Test public void openingCashboxBalanceMustBeFiniteAndNonnegative() {
        assertTrue(CategoryAccounting.isValidOpeningBalance(0));
        assertTrue(CategoryAccounting.isValidOpeningBalance(1500));
        assertFalse(CategoryAccounting.isValidOpeningBalance(-1));
        assertFalse(CategoryAccounting.isValidOpeningBalance(Double.NaN));
        assertFalse(CategoryAccounting.isValidOpeningBalance(Double.POSITIVE_INFINITY));
    }

    @Test public void cashboxTransferRequiresDistinctAccountsAndSufficientFunds() {
        assertTrue(CategoryAccounting.isValidCashboxTransfer("rent", "shop", 500, 500));
        assertFalse(CategoryAccounting.isValidCashboxTransfer("rent", "shop", 501, 500));
        assertFalse(CategoryAccounting.isValidCashboxTransfer("rent", "rent", 100, 500));
        assertFalse(CategoryAccounting.isValidCashboxTransfer("", "shop", 100, 500));
        assertFalse(CategoryAccounting.isValidCashboxTransfer("rent", "shop", 0, 500));
        assertFalse(CategoryAccounting.isValidCashboxTransfer("rent", "shop", 100, -1));
    }

}

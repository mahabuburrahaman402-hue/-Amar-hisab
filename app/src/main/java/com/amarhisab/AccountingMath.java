package com.amarhisab;

public final class AccountingMath {
    private AccountingMath() {}

    public static double lineTotal(double quantity, double unitPrice) {
        if (!Double.isFinite(quantity) || !Double.isFinite(unitPrice) ||
            quantity < 0 || unitPrice < 0) return 0;
        double total = quantity * unitPrice;
        return Double.isFinite(total) ? total : 0;
    }

    public static boolean isValidLineItem(String product, double quantity, double unitPrice) {
        return product != null && !product.trim().isEmpty() &&
            Double.isFinite(quantity) && Double.isFinite(unitPrice) &&
            quantity > 0 && unitPrice > 0 &&
            Double.isFinite(quantity * unitPrice);
    }

    public static double weightedAverageCost(double oldQuantity, double oldUnitCost,
                                             double purchaseQuantity, double purchaseUnitCost) {
        if (!Double.isFinite(oldQuantity) || !Double.isFinite(oldUnitCost) ||
            !Double.isFinite(purchaseQuantity) || !Double.isFinite(purchaseUnitCost) ||
            oldQuantity < 0 || oldUnitCost < 0 ||
            purchaseQuantity <= 0 || purchaseUnitCost <= 0) return 0;
        double newQuantity = oldQuantity + purchaseQuantity;
        double oldValue = oldQuantity * oldUnitCost;
        double purchaseValue = purchaseQuantity * purchaseUnitCost;
        double totalValue = oldValue + purchaseValue;
        if (!Double.isFinite(newQuantity) || newQuantity <= 0 ||
            !Double.isFinite(oldValue) || !Double.isFinite(purchaseValue) ||
            !Double.isFinite(totalValue)) return 0;
        double average = totalValue / newQuantity;
        return Double.isFinite(average) && average > 0 ? average : 0;
    }

    public static double costOfGoodsSold(double quantity, double averageUnitCost) {
        if (!Double.isFinite(quantity) || !Double.isFinite(averageUnitCost) ||
            quantity <= 0 || averageUnitCost < 0) return 0;
        double cost = quantity * averageUnitCost;
        return Double.isFinite(cost) ? cost : 0;
    }

    public static double grossProfit(double salesTotal, double costOfGoodsSold) {
        if (!Double.isFinite(salesTotal) || !Double.isFinite(costOfGoodsSold) ||
            salesTotal < 0 || costOfGoodsSold < 0) return 0;
        double profit = salesTotal - costOfGoodsSold;
        return Double.isFinite(profit) ? profit : 0;
    }

    // Accept Bengali, Arabic-Indic and Western numerals in all amount fields.
    public static double parseAmount(String raw) {
        if (raw == null) return 0;
        StringBuilder normalized = new StringBuilder();
        String value = raw.trim();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '০' && ch <= '৯') ch = (char)('0' + (ch - '০'));
            else if (ch >= '٠' && ch <= '٩') ch = (char)('0' + (ch - '٠'));
            if (!Character.isWhitespace(ch)) normalized.append(ch == ',' || ch == '٫' ? '.' : ch);
        }
        try {
            double amount = Double.parseDouble(normalized.toString());
            return Double.isFinite(amount) ? amount : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isValidTransactionAmount(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }

    public static double applyPartyTransaction(double currentBalance, String type, double amount) {
        if (!Double.isFinite(currentBalance) || !Double.isFinite(amount) || amount < 0)
            return currentBalance;
        if ("পাওনা".equals(type)) {
            double next = currentBalance + amount;
            return Double.isFinite(next) ? next : currentBalance;
        }
        if ("জমা".equals(type) || "পরিশোধ".equals(type)) {
            double next = currentBalance - amount;
            return Double.isFinite(next) ? next : currentBalance;
        }
        return currentBalance;
    }

    public static double sumRentalPayments(double... payments) {
        if (payments == null) return 0;
        double total = 0;
        for (double payment : payments) {
            if (!Double.isFinite(payment) || payment <= 0) continue;
            total += payment;
            if (!Double.isFinite(total)) return 0;
        }
        return total;
    }

    public static double rentalOutstanding(double amountDue, double... payments) {
        if (!Double.isFinite(amountDue) || amountDue < 0) return 0;
        return Math.max(0, amountDue - sumRentalPayments(payments));
    }

    public static double outstanding(double amountDue, double amountPaid) {
        if (!Double.isFinite(amountDue) || !Double.isFinite(amountPaid) ||
            amountDue < 0 || amountPaid < 0) return 0;
        double balance = amountDue - amountPaid;
        return Double.isFinite(balance) ? Math.max(0, balance) : 0;
    }

    public static double remainingReceivable(double totalDue, double totalReceived,
                                             double totalPaid) {
        if (!Double.isFinite(totalDue) || !Double.isFinite(totalReceived) ||
            !Double.isFinite(totalPaid) || totalDue < 0 ||
            totalReceived < 0 || totalPaid < 0) return 0;
        double balance = totalDue - totalReceived - totalPaid;
        return Double.isFinite(balance) ? Math.max(0, balance) : 0;
    }

    public static double excessPayment(double totalDue, double totalReceived,
                                       double totalPaid) {
        if (!Double.isFinite(totalDue) || !Double.isFinite(totalReceived) ||
            !Double.isFinite(totalPaid) || totalDue < 0 ||
            totalReceived < 0 || totalPaid < 0) return 0;
        double paid = totalReceived + totalPaid;
        if (!Double.isFinite(paid)) return 0;
        double excess = paid - totalDue;
        return Double.isFinite(excess) ? Math.max(0, excess) : 0;
    }

    public static boolean isValidRentalSetup(double monthlyRent, double deposit, int dueDay) {
        return Double.isFinite(monthlyRent) && monthlyRent > 0 &&
            Double.isFinite(deposit) && deposit >= 0 &&
            dueDay >= 1 && dueDay <= 31;
    }

    public static boolean sameRentalUnit(String tenantA, String unitA, String buildingA,
                                         String tenantB, String unitB, String buildingB) {
        return safeEquals(tenantA, tenantB) &&
               safeEquals(unitA, unitB) &&
               safeEquals(buildingA, buildingB);
    }

    public static boolean matchesRentalRecordLink(String expectedKey, String actualKey,
                                                   String tenant, String unit, String building,
                                                   String recordTenant, String recordUnit,
                                                   String recordBuilding) {
        return expectedKey != null && !expectedKey.isEmpty() &&
            expectedKey.equals(actualKey) &&
            sameRentalUnit(tenant, unit, building, recordTenant, recordUnit, recordBuilding);
    }

    public static boolean sameMonthlyRentEntry(String existingKey, String currentKey,
                                                String legacyKey, String existingBuilding,
                                                String currentBuilding) {
        if (safeEquals(existingKey, currentKey)) return true;
        return safeEquals(existingKey, legacyKey) &&
               existingBuilding != null && !existingBuilding.isEmpty() &&
               safeEquals(existingBuilding, currentBuilding);
    }

    public static boolean sameRentalIdentity(String buildingA, String unitA,
                                              String buildingB, String unitB) {
        return buildingA != null && unitA != null && buildingB != null && unitB != null &&
            buildingA.trim().equalsIgnoreCase(buildingB.trim()) &&
            unitA.trim().equalsIgnoreCase(unitB.trim());
    }

    public static boolean sameRentalExpense(String building, String unit,
                                             String recordParty, String recordBuilding,
                                             String recordUnit, boolean rentalExpense) {
        if (!rentalExpense) return false;
        boolean hasUnitMetadata = (recordBuilding != null && !recordBuilding.isEmpty()) ||
                                  (recordUnit != null && !recordUnit.isEmpty());
        if (hasUnitMetadata) {
            return safeEquals(building, recordBuilding) && safeEquals(unit, recordUnit);
        }
        return safeEquals("বাড়ি ভাড়া: " + building + " / " + unit, recordParty);
    }

    public static double rentAtMonth(double fallbackRent, String targetMonth,
                                      String[] effectiveMonths, double[] amounts) {
        double selected = Double.isFinite(fallbackRent) && fallbackRent > 0 ? fallbackRent : 0;
        if (!isValidYearMonth(targetMonth) || effectiveMonths == null || amounts == null) return selected;
        String selectedMonth = "0000-00";
        int count = Math.min(effectiveMonths.length, amounts.length);
        for (int i = 0; i < count; i++) {
            String from = effectiveMonths[i];
            double amount = amounts[i];
            boolean validMonth = "0000-00".equals(from) || isValidYearMonth(from);
            if (validMonth && from.compareTo(targetMonth) <= 0 && from.compareTo(selectedMonth) >= 0 &&
                Double.isFinite(amount) && amount > 0) {
                selectedMonth = from;
                selected = amount;
            }
        }
        return selected;
    }

    public static boolean isValidYearMonth(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) return false;
        try {
            int year = Integer.parseInt(value.substring(0, 4));
            int month = Integer.parseInt(value.substring(5, 7));
            return year >= 1900 && year <= 9999 && month >= 1 && month <= 12;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String depositOwner(String currentTenant, String recordedOwner) {
        if (recordedOwner != null && !recordedOwner.trim().isEmpty() &&
            !"খালি".equals(recordedOwner)) return recordedOwner;
        return currentTenant == null ? "" : currentTenant;
    }

    public static double evaluateExpression(String raw) {
        if (raw == null) throw new IllegalArgumentException("Invalid expression");
        String s = raw.trim()
            .replace('×', '*').replace('x', '*').replace('X', '*')
            .replace('÷', '/').replace('−', '-')
            .replace(" ", "")
            .replace('০','0').replace('১','1').replace('২','2')
            .replace('৩','3').replace('৪','4').replace('৫','5')
            .replace('৬','6').replace('৭','7').replace('৮','8').replace('৯','9')
            .replace(',', '.');
        if (s.isEmpty()) return 0;
        final int[] p = {0};
        double value = parseExpression(s, p);
        if (p[0] != s.length() || !Double.isFinite(value))
            throw new IllegalArgumentException("Invalid expression");
        return value;
    }

    private static double parseExpression(String s, int[] p) {
        double value = parseTerm(s, p);
        while (p[0] < s.length()) {
            char op = s.charAt(p[0]);
            if (op != '+' && op != '-') break;
            p[0]++;
            int rightStart = p[0];
            double right = parseTerm(s, p);
            boolean percent = p[0] > rightStart && s.charAt(p[0] - 1) == '%';
            if (percent) right = value * right;
            value = op == '+' ? value + right : value - right;
        }
        return value;
    }

    private static double parseTerm(String s, int[] p) {
        double value = parseFactor(s, p);
        while (p[0] < s.length()) {
            char op = s.charAt(p[0]);
            if (op != '*' && op != '/') break;
            p[0]++;
            double right = parseFactor(s, p);
            if (op == '/' && right == 0) throw new IllegalArgumentException("Divide by zero");
            value = op == '*' ? value * right : value / right;
        }
        return value;
    }

    private static double parseFactor(String s, int[] p) {
        if (p[0] < s.length() && (s.charAt(p[0]) == '+' || s.charAt(p[0]) == '-')) {
            char sign = s.charAt(p[0]++);
            double value = parseFactor(s, p);
            return sign == '-' ? -value : value;
        }
        if (p[0] < s.length() && s.charAt(p[0]) == '(') {
            p[0]++;
            double value = parseExpression(s, p);
            if (p[0] >= s.length() || s.charAt(p[0]++) != ')')
                throw new IllegalArgumentException("Missing )");
            return value;
        }
        int start = p[0];
        while (p[0] < s.length() &&
            (Character.isDigit(s.charAt(p[0])) || s.charAt(p[0]) == '.')) p[0]++;
        if (start == p[0]) throw new IllegalArgumentException("Number expected");
        double value = Double.parseDouble(s.substring(start, p[0]));
        if (p[0] < s.length() && s.charAt(p[0]) == '%') {
            p[0]++;
            value /= 100.0;
        }
        return value;
    }

    public static String monthFromDate(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            java.text.SimpleDateFormat input =
                new java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US);
            input.setLenient(false);
            java.text.ParsePosition position = new java.text.ParsePosition(0);
            String normalized = value.trim();
            java.util.Date parsed = input.parse(normalized, position);
            if (parsed == null || position.getIndex() != normalized.length()) return "";
            return new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(parsed);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
    // Rental, utility, maintenance and refundable-deposit movements belong to
    // the property ledger, not the tenant's unrelated personal party ledger.
    public static boolean isRentalLedgerRecord(
            boolean depositTransaction, boolean depositRefund,
            boolean rentalCharge, boolean rentalExpense,
            boolean rentAdvanceReceived, boolean rentAdvanceApplied,
            boolean rentAdvanceRefund, String rentKey, String rentPaymentKey,
            String rentalChargeKey, String rentalChargePaymentKey,
            String depositTransactionKey) {
        return depositTransaction || depositRefund || rentalCharge || rentalExpense ||
            rentAdvanceReceived || rentAdvanceApplied || rentAdvanceRefund ||
            hasText(rentKey) || hasText(rentPaymentKey) ||
            hasText(rentalChargeKey) || hasText(rentalChargePaymentKey) ||
            hasText(depositTransactionKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static boolean isBusinessIncomeRecord(String type, boolean stockSale) {
        return stockSale || "আয়".equals(type);
    }

    public static boolean isVacantTenantName(String name) {
        if (name == null) return false;
        String value = name.trim();
        return "খালি".equals(value) || "vacant".equalsIgnoreCase(value);
    }

}

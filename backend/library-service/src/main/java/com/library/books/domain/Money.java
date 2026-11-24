package com.library.books.domain;

/**
 * An amount in whole Indian rupees. The library only deals in whole-rupee amounts (deposits,
 * Rs. 10/day late fees), so a long is exact and simpler than BigDecimal. May be negative when a
 * member owes more in late fees than their deposit covers.
 */
public record Money(long rupees) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public static Money rupees(long rupees) {
        return new Money(rupees);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(rupees, other.rupees));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(rupees, other.rupees));
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(rupees, factor));
    }

    public boolean isPositive() {
        return rupees > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(rupees, other.rupees);
    }

    /** "Rs. 500", "-Rs. 20". */
    @Override
    public String toString() {
        return rupees < 0 ? "-Rs. " + Math.abs(rupees) : "Rs. " + rupees;
    }
}

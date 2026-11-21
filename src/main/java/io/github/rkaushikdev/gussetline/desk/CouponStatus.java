package io.github.rkaushikdev.gussetline.desk;

/** What has happened to one coupon, under the code a ticket record shows for it. */
public enum CouponStatus {
    OPEN("OK"),
    USED("USED"),
    EXCHANGED("EXCH"),
    REFUNDED("RFND"),
    VOID("VOID");

    private final String value;

    CouponStatus(String value) {
        this.value = value;
    }

    /** The code a ticket record shows for the status. */
    public String value() {
        return value;
    }

    /** Whether the coupon can still be flown, changed or refunded. Only the open status is. */
    public boolean isOpen() {
        return this == OPEN;
    }

    /**
     * The status a ticket record shows under a code.
     *
     * @throws IllegalArgumentException when the code is no status
     */
    public static CouponStatus of(String value) {
        for (CouponStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid CouponStatus");
    }
}

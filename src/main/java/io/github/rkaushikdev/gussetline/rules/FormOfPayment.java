package io.github.rkaushikdev.gussetline.rules;

/** How a ticket is paid for, which a category 15 filing may restrict. */
public enum FormOfPayment {
    ANY("any"),
    CASH("cash"),
    CARD("card"),
    INVOICE("invoice");

    private final String value;

    FormOfPayment(String value) {
        this.value = value;
    }

    /** The name the form of payment is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The form of payment stored under a name.
     *
     * @throws IllegalArgumentException when the name is no form of payment
     */
    public static FormOfPayment of(String value) {
        for (FormOfPayment payment : values()) {
            if (payment.value.equals(value)) {
                return payment;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid FormOfPayment");
    }
}

"""Money, and the three different ways a fare gets rounded.

A published fare is not a price. It is an amount in a currency, converted
through the rate of exchange into a neutral unit, added up, converted back and
then rounded, and the rounding is not one rule. Fares round up to a unit that
is per currency and is not the minor unit. Taxes round to the nearest minor
unit. Refunds round down so we never owe the passenger a fraction we cannot
pay. Getting any of the three wrong shows up as a one unit discrepancy on
every audit in that currency, which is exactly the size of finding that gets
dismissed as noise for a year.

Amounts are Decimal throughout and never float. The tables below are the
subset of currencies the desk actually sells in.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_CEILING, ROUND_DOWN, ROUND_HALF_UP, Decimal
from typing import Any

from gussetline.errors import ValidationError


@dataclass(frozen=True, slots=True)
class Currency:
    """A selling currency and how amounts in it are rounded.

    ``exponent`` is the number of minor unit digits. ``fare_unit`` is the step
    a constructed fare is rounded up to, expressed in major units, and it is
    frequently coarser than the minor unit. Japan publishes fares to the
    hundred yen even though the yen has no minor unit at all.
    """

    code: str
    exponent: int
    fare_unit: Decimal
    name: str

    @property
    def minor(self) -> Decimal:
        return Decimal(1).scaleb(-self.exponent)

    def quantum(self) -> Decimal:
        return self.minor


_CURRENCY_ROWS: tuple[tuple[str, int, str, str], ...] = (
    ("EUR", 2, "1", "Euro"),
    ("USD", 2, "1", "US Dollar"),
    ("GBP", 2, "1", "Pound Sterling"),
    ("CHF", 2, "1", "Swiss Franc"),
    ("SEK", 2, "5", "Swedish Krona"),
    ("NOK", 2, "5", "Norwegian Krone"),
    ("DKK", 2, "5", "Danish Krone"),
    ("PLN", 2, "1", "Zloty"),
    ("CZK", 2, "10", "Czech Koruna"),
    ("HUF", 2, "100", "Forint"),
    ("JPY", 0, "100", "Yen"),
    ("KRW", 0, "100", "Won"),
    ("CNY", 2, "1", "Yuan Renminbi"),
    ("INR", 2, "5", "Indian Rupee"),
    ("BRL", 2, "1", "Brazilian Real"),
    ("ZAR", 2, "1", "Rand"),
    ("AED", 2, "5", "UAE Dirham"),
    ("KWD", 3, "0.5", "Kuwaiti Dinar"),
    ("BHD", 3, "0.5", "Bahraini Dinar"),
    ("TND", 3, "0.5", "Tunisian Dinar"),
    ("CAD", 2, "1", "Canadian Dollar"),
    ("AUD", 2, "1", "Australian Dollar"),
    ("NZD", 2, "1", "New Zealand Dollar"),
    ("SGD", 2, "1", "Singapore Dollar"),
    ("HKD", 2, "1", "Hong Kong Dollar"),
    ("THB", 2, "5", "Baht"),
    ("TRY", 2, "1", "Turkish Lira"),
    ("MXN", 2, "1", "Mexican Peso"),
)

CURRENCIES: dict[str, Currency] = {
    code: Currency(code=code, exponent=exp, fare_unit=Decimal(unit), name=name)
    for code, exp, unit, name in _CURRENCY_ROWS
}

# The neutral unit fares are constructed in. It is not a currency anybody
# sells in, it always carries two decimals, and it truncates rather than
# rounds, which is why it is here and not in the table above.
NUC = Currency(
    code="NUC", exponent=2, fare_unit=Decimal("0.01"), name="Neutral Unit of Construction"
)


def currency(code: str) -> Currency:
    """Look a currency up, refusing anything the desk cannot sell in."""
    key = code.strip().upper()
    if key == "NUC":
        return NUC
    try:
        return CURRENCIES[key]
    except KeyError:
        raise ValidationError("unknown currency", code=code) from None


@dataclass(frozen=True, slots=True, order=False)
class Money:
    """An amount in one currency. Immutable, exact, and never float."""

    amount: Decimal
    currency: str

    def __post_init__(self) -> None:
        if not isinstance(self.amount, Decimal):
            object.__setattr__(self, "amount", Decimal(str(self.amount)))
        object.__setattr__(self, "currency", self.currency.strip().upper())
        currency(self.currency)

    @classmethod
    def of(cls, amount: Decimal | int | str, code: str) -> Money:
        return cls(Decimal(str(amount)), code)

    @classmethod
    def zero(cls, code: str) -> Money:
        return cls(Decimal("0"), code)

    def _same(self, other: Money) -> None:
        if self.currency != other.currency:
            raise ValidationError("cannot mix currencies", left=self.currency, right=other.currency)

    def __add__(self, other: Money) -> Money:
        self._same(other)
        return Money(self.amount + other.amount, self.currency)

    def __sub__(self, other: Money) -> Money:
        self._same(other)
        return Money(self.amount - other.amount, self.currency)

    def __neg__(self) -> Money:
        return Money(-self.amount, self.currency)

    def __mul__(self, factor: Decimal | int) -> Money:
        return Money(self.amount * Decimal(str(factor)), self.currency)

    __rmul__ = __mul__

    def __lt__(self, other: Money) -> bool:
        self._same(other)
        return self.amount < other.amount

    def __le__(self, other: Money) -> bool:
        self._same(other)
        return self.amount <= other.amount

    def __gt__(self, other: Money) -> bool:
        self._same(other)
        return self.amount > other.amount

    def __ge__(self, other: Money) -> bool:
        self._same(other)
        return self.amount >= other.amount

    @property
    def is_zero(self) -> bool:
        return self.amount == 0

    @property
    def is_negative(self) -> bool:
        return self.amount < 0

    def abs(self) -> Money:
        return Money(abs(self.amount), self.currency)

    def as_tuple(self) -> tuple[str, str]:
        return (str(self.amount), self.currency)

    def __str__(self) -> str:
        return f"{self.amount} {self.currency}"


def round_fare(value: Money) -> Money:
    """Round a constructed fare up to the currency's published fare unit."""
    unit = currency(value.currency).fare_unit
    steps = (value.amount / unit).quantize(Decimal(1), rounding=ROUND_CEILING)
    return Money(steps * unit, value.currency)


def round_tax(value: Money) -> Money:
    """Round a tax or fee to the nearest minor unit, halves away from zero."""
    cur = currency(value.currency)
    return Money(value.amount.quantize(cur.quantum(), rounding=ROUND_HALF_UP), value.currency)


def round_refund(value: Money) -> Money:
    """Round a refundable amount down, so the desk never promises a fraction."""
    cur = currency(value.currency)
    return Money(value.amount.quantize(cur.quantum(), rounding=ROUND_DOWN), value.currency)


def truncate_nuc(amount: Decimal) -> Decimal:
    """Neutral units carry two decimals and the third is dropped, not rounded."""
    return amount.quantize(Decimal("0.01"), rounding=ROUND_DOWN)


def total(*amounts: Money) -> Money:
    """Add money that is already known to share a currency."""
    if not amounts:
        raise ValidationError("nothing to total")
    running = amounts[0]
    for item in amounts[1:]:
        running = running + item
    return running


def parse_money(text: str) -> Money:
    """Read ``123.45EUR`` or ``123.45 EUR``, which is how suppliers write it."""
    raw = text.strip().replace(" ", "")
    if len(raw) < 4:
        raise ValidationError("not an amount", text=text)
    code, digits = raw[-3:], raw[:-3]
    try:
        value = Decimal(digits)
    except Exception:
        raise ValidationError("not an amount", text=text) from None
    return Money(value, code)


def coerce_amount(value: Any) -> Decimal:
    """Take whatever a payload used for a number and make it exact."""
    if isinstance(value, Decimal):
        return value
    if isinstance(value, bool):
        raise ValidationError("boolean is not an amount", value=value)
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, str):
        try:
            return Decimal(value.strip())
        except Exception:
            raise ValidationError("not an amount", value=value) from None
    if isinstance(value, float):
        raise ValidationError("float amounts are refused, send a string", value=value)
    raise ValidationError("not an amount", value=repr(value))

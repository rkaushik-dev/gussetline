"""Rates of exchange, and the two of them that are not the same rate.

A fare is published in the currency of the country of commencement and is
converted into neutral units with the rate of exchange, which is published
quarterly and is deliberately stale. A tax is converted with the bankers
selling rate, which moves daily. Using the ROE for a tax is the single most
common cause of a supplier quote and our reconstruction differing by a few
units, and it looks like a rounding argument rather than the wrong table.

Both tables here are effective dated. A lookup asks for the date of ticketing,
never today, because a reconstruction of a ticket sold in March has to use the
rate that was in force in March or it will not reproduce the number on the
coupon.
"""

from __future__ import annotations

import bisect
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal

from gussetline.errors import ValidationError
from gussetline.money import Money, currency, round_fare, truncate_nuc


@dataclass(frozen=True, slots=True)
class Rate:
    """One currency's rate against the neutral unit, from a given date."""

    code: str
    effective: date
    units_per_nuc: Decimal

    def __post_init__(self) -> None:
        if self.units_per_nuc <= 0:
            raise ValidationError("a rate has to be positive", code=self.code)


class RateTable:
    """Effective dated rates for many currencies, queried by date.

    Rates are appended out of order often enough that the table sorts on
    insert rather than trusting the caller. A lookup takes the latest rate on
    or before the date asked for, which is what an effective dated table
    means, and refuses rather than guessing when the date is before the first
    rate we hold.
    """

    def __init__(self, name: str) -> None:
        self.name = name
        self._by_code: dict[str, list[Rate]] = {}

    def add(self, code: str, effective: date, units_per_nuc: Decimal | str) -> Rate:
        rate = Rate(code.upper(), effective, Decimal(str(units_per_nuc)))
        currency(rate.code)
        rows = self._by_code.setdefault(rate.code, [])
        for i, existing in enumerate(rows):
            if existing.effective == effective:
                rows[i] = rate
                return rate
        rows.append(rate)
        rows.sort(key=lambda r: r.effective)
        return rate

    def add_many(self, effective: date, rates: dict[str, str]) -> None:
        for code, value in rates.items():
            self.add(code, effective, value)

    def codes(self) -> list[str]:
        return sorted(self._by_code)

    def lookup(self, code: str, on: date) -> Rate:
        key = code.upper()
        rows = self._by_code.get(key)
        if not rows:
            raise ValidationError("no rate held", table=self.name, code=key)
        index = bisect.bisect_right([r.effective for r in rows], on)
        if index == 0:
            raise ValidationError(
                "rate table starts after the date asked for",
                table=self.name,
                code=key,
                on=on.isoformat(),
                first=rows[0].effective.isoformat(),
            )
        return rows[index - 1]

    def rate_for(self, code: str, on: date) -> Decimal:
        return self.lookup(code, on).units_per_nuc


def quarter_start(on: date) -> date:
    """The first day of the IATA quarter a date falls in."""
    month = ((on.month - 1) // 3) * 3 + 1
    return date(on.year, month, 1)


class Converter:
    """Turns money into neutral units and back, with the right table each way."""

    def __init__(self, roe: RateTable, bankers: RateTable | None = None) -> None:
        self.roe = roe
        self.bankers = bankers if bankers is not None else roe

    def to_nuc(self, amount: Money, on: date) -> Decimal:
        """Fare currency into neutral units. Truncated to two decimals."""
        if amount.currency == "NUC":
            return truncate_nuc(amount.amount)
        rate = self.roe.rate_for(amount.currency, on)
        return truncate_nuc(amount.amount / rate)

    def from_nuc(self, nuc: Decimal, code: str, on: date) -> Money:
        """Neutral units into a selling currency, rounded up to the fare unit."""
        target = currency(code)
        if target.code == "NUC":
            return Money(truncate_nuc(nuc), "NUC")
        rate = self.roe.rate_for(target.code, on)
        return round_fare(Money(nuc * rate, target.code))

    def convert_fare(self, amount: Money, into: str, on: date) -> Money:
        """Fare to fare, through neutral units, the long way on purpose."""
        return self.from_nuc(self.to_nuc(amount, on), into, on)

    def convert_tax(self, amount: Money, into: str, on: date) -> Money:
        """Tax to tax, on the bankers rate, rounded to the minor unit."""
        if amount.currency == into.upper():
            return amount
        source = self.bankers.rate_for(amount.currency, on)
        target_rate = self.bankers.rate_for(into.upper(), on)
        neutral = (amount.amount / source).quantize(Decimal("0.000001"), rounding=ROUND_DOWN)
        target = currency(into)
        value = (neutral * target_rate).quantize(target.quantum(), rounding=ROUND_HALF_UP)
        return Money(value, target.code)


def default_roe_table() -> RateTable:
    """The rates the desk ships with, one quarter per row.

    These are the published IATA rates for the currencies the desk sells in.
    They are checked into the package rather than fetched because a quarter
    that fails to download must not silently price at last quarter's rate.
    """
    table = RateTable("roe")
    table.add_many(
        date(2024, 10, 1),
        {
            "EUR": "0.913",
            "USD": "1.000",
            "GBP": "0.766",
            "CHF": "0.859",
            "SEK": "10.401",
            "NOK": "10.686",
            "DKK": "6.812",
            "PLN": "3.926",
            "CZK": "22.938",
            "HUF": "364.42",
            "JPY": "146.86",
            "KRW": "1329.62",
            "CNY": "7.099",
            "INR": "83.92",
            "CAD": "1.354",
            "AUD": "1.478",
            "SGD": "1.301",
            "HKD": "7.783",
            "AED": "3.673",
            "THB": "33.42",
            "TRY": "34.11",
        },
    )
    table.add_many(
        date(2025, 1, 1),
        {
            "EUR": "0.951",
            "USD": "1.000",
            "GBP": "0.789",
            "CHF": "0.896",
            "SEK": "10.968",
            "NOK": "11.163",
            "DKK": "7.093",
            "PLN": "4.070",
            "CZK": "23.902",
            "HUF": "391.66",
            "JPY": "152.44",
            "KRW": "1421.09",
            "CNY": "7.264",
            "INR": "84.71",
            "CAD": "1.416",
            "AUD": "1.575",
            "SGD": "1.348",
            "HKD": "7.775",
            "AED": "3.673",
            "THB": "34.16",
            "TRY": "35.29",
        },
    )
    table.add_many(
        date(2025, 4, 1),
        {
            "EUR": "0.924",
            "USD": "1.000",
            "GBP": "0.774",
            "CHF": "0.883",
            "SEK": "10.045",
            "NOK": "10.501",
            "DKK": "6.892",
            "PLN": "3.869",
            "CZK": "23.117",
            "HUF": "371.05",
            "JPY": "149.51",
            "KRW": "1461.32",
            "CNY": "7.257",
            "INR": "85.52",
            "CAD": "1.437",
            "AUD": "1.594",
            "SGD": "1.343",
            "HKD": "7.777",
            "AED": "3.673",
            "THB": "34.03",
            "TRY": "37.92",
        },
    )
    return table

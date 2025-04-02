"""Reading filed tariff data into the store the pricing engine reads.

Separate from the collectors on purpose. A collector fetches what a supplier
is selling today; a loader reads what a carrier has filed, which arrives as a
file, on a schedule, and describes the future as well as the present.
"""

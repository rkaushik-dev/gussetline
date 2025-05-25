"""Somewhere for the fields six columns could not hold.

Category 15 files a country list, an excluded country list, a form of payment
and two dates. The named columns cover two of those, and widening the table
for every category to suit one of them costs more than it is worth.

So one text column takes the overflow, written as key equals value pairs. It
is a blob and it is meant to be the exception: nothing that a report filters
on goes in it, and every provision kind that uses it has to survive the round
trip test.

Empty by default, which is the right answer for every row already stored:
those are categories 5, 6, 7, 16 and 31, and none of them has ever needed it.

Revision ID: 0003
Revises: 0002
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("provisions", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("detail", sa.String(length=512), nullable=False, server_default="")
        )


def downgrade() -> None:
    with op.batch_alter_table("provisions", schema=None) as batch_op:
        batch_op.drop_column("detail")

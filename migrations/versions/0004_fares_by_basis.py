"""An index for the one fare lookup that has no market to narrow on.

The night run reads a ticket's fare basis and needs the rule behind it. Every
other fare query knows the market and takes the market index; this one has only
the carrier, so it took that index for its first column and read most of the
table to find one row.

Cheap to add and cheap to keep: fares are written once per filing file and read
on every reprice.

Revision ID: 0004
Revises: 0003
"""

from __future__ import annotations

from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("fares", schema=None) as batch_op:
        batch_op.create_index("ix_fares_basis", ["carrier", "fare_basis"], unique=False)


def downgrade() -> None:
    with op.batch_alter_table("fares", schema=None) as batch_op:
        batch_op.drop_index("ix_fares_basis")

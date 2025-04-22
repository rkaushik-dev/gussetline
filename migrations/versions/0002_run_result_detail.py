"""Two readings the reliability report cannot get anywhere else.

A run result already said what the outcome was, which covers everything that
shows up as a status code. It did not say whether the payload was one we
already held, and it did not say how many offers inside a payload failed to
parse. Both of those are supplier failures that answer with a 200, so a run
made entirely of them looks perfect from the outcome column alone.

Added with a server default so the rows already in the table read as fresh
and clean, which is the right answer for them: nothing recorded a repeat or a
payload problem before this, so claiming otherwise would invent history.

Revision ID: 0002
Revises: 0001
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("run_results", schema=None) as batch_op:
        batch_op.add_column(
            sa.Column("fresh", sa.Boolean(), nullable=False, server_default=sa.true())
        )
        batch_op.add_column(sa.Column("problems", sa.Integer(), nullable=False, server_default="0"))


def downgrade() -> None:
    with op.batch_alter_table("run_results", schema=None) as batch_op:
        batch_op.drop_column("problems")
        batch_op.drop_column("fresh")

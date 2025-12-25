import os
from datetime import datetime, timezone
from typing import Literal, List

import asyncpg
from fastapi import FastAPI, HTTPException, Path
from pydantic import BaseModel, Field

DATABASE_URL = os.getenv("DATABASE_URL")
if not DATABASE_URL:
    raise RuntimeError("DATABASE_URL is required")

app = FastAPI()


# ---------- Models ----------
class TxIn(BaseModel):
    value: int = Field(..., gt=0)  # positive integer
    type: Literal["c", "d"]
    description: str = Field(..., min_length=1, max_length=10)  # 1..10 chars


class TxOut(BaseModel):
    limit: int
    balance: int


class StatementBalance(BaseModel):
    total: int
    limit: int
    date: str


class StatementTx(BaseModel):
    value: int
    type: Literal["c", "d"]
    description: str
    executed_at: str


class StatementOut(BaseModel):
    balance: StatementBalance
    latest_transactions: List[StatementTx]


# ---------- Startup / Shutdown ----------
@app.on_event("startup")
async def startup():
    # small pool helps keep memory down under container constraints
    app.state.pool = await asyncpg.create_pool(
        DATABASE_URL,
        min_size=1,
        max_size=10,
        command_timeout=2.0,
    )


@app.on_event("shutdown")
async def shutdown():
    pool = app.state.pool
    await pool.close()


@app.get("/health")
async def health():
    return {"ok": True}


# ---------- Helpers ----------
def now_iso():
    return datetime.now(timezone.utc).isoformat()


# ---------- Routes ----------
@app.post("/clients/{client_id}/transactions", response_model=TxOut)
async def create_transaction(
    client_id: int = Path(..., ge=1),
    tx_in: TxIn = None,
):
    pool: asyncpg.Pool = app.state.pool

    async with pool.acquire() as conn:
        async with conn.transaction():
            # Lock the client row to prevent race conditions
            row = await conn.fetchrow(
                """
                SELECT limit_cents, balance_cents
                FROM clients
                WHERE id = $1
                FOR UPDATE
                """,
                client_id,
            )
            if row is None:
                raise HTTPException(status_code=404)

            limit_cents = int(row["limit_cents"])
            balance_cents = int(row["balance_cents"])

            delta = tx_in.value if tx_in.type == "c" else -tx_in.value

            # debit rule: new balance must be >= -limit
            if tx_in.type == "d" and (balance_cents + delta) < -limit_cents:
                raise HTTPException(status_code=422)

            # update balance
            await conn.execute(
                "UPDATE clients SET balance_cents = balance_cents + $1 WHERE id = $2",
                delta,
                client_id,
            )

            # insert transaction
            await conn.execute(
                """
                INSERT INTO transactions (client_id, value_cents, type, description)
                VALUES ($1, $2, $3, $4)
                """,
                client_id,
                tx_in.value,
                tx_in.type,
                tx_in.description,
            )

            # read updated balance (still inside same tx)
            new_balance = await conn.fetchval(
                "SELECT balance_cents FROM clients WHERE id = $1",
                client_id,
            )

            return TxOut(limit=limit_cents, balance=int(new_balance))


@app.get("/clients/{client_id}/statement", response_model=StatementOut)
async def get_statement(client_id: int = Path(..., ge=1)):
    pool: asyncpg.Pool = app.state.pool

    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT limit_cents, balance_cents FROM clients WHERE id = $1",
            client_id,
        )
        if row is None:
            raise HTTPException(status_code=404)

        limit_cents = int(row["limit_cents"])
        balance_cents = int(row["balance_cents"])

        tx_rows = await conn.fetch(
            """
            SELECT value_cents, type, description, executed_at
            FROM transactions
            WHERE client_id = $1
            ORDER BY executed_at DESC
            LIMIT 10
            """,
            client_id,
        )

        latest = []
        for r in tx_rows:
            executed_at = r["executed_at"]
            if executed_at.tzinfo is None:
                executed_at = executed_at.replace(tzinfo=timezone.utc)
            latest.append(
                StatementTx(
                    value=int(r["value_cents"]),
                    type=r["type"],
                    description=r["description"],
                    executed_at=executed_at.astimezone(timezone.utc).isoformat(),
                )
            )

        return StatementOut(
            balance=StatementBalance(
                total=balance_cents,
                limit=limit_cents,
                date=now_iso(),
            ),
            latest_transactions=latest,
        )

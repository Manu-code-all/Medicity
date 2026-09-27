-- =====================================================================
-- V17: Optional live stock, for stores that already keep it in software.
--
-- Most stores answer questions by hand; nothing here changes that. A store
-- that can send its stock (from its billing software, or typed in) may
-- turn on automatic answers: a question it receives is then answered at
-- once from that stock, marked as automatic.
--
-- Only fresh stock answers. stock_updated_at is when the whole list was
-- last sent; older than a day, the store answers by hand again, because a
-- confident "yes" from yesterday's stock is worse than a slower real one.
-- =====================================================================

ALTER TABLE stores
    ADD COLUMN auto_answer      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stock_updated_at TIMESTAMPTZ;

CREATE TABLE store_stock (
    store_id     UUID           NOT NULL REFERENCES stores (id) ON DELETE CASCADE,
    medicine_id  UUID           NOT NULL REFERENCES medicines (id),
    quantity     INTEGER        NOT NULL,
    unit_price   NUMERIC(10, 2) NOT NULL,

    PRIMARY KEY (store_id, medicine_id),
    CONSTRAINT store_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT store_stock_price    CHECK (unit_price >= 0)
);

ALTER TABLE request_recipients
    ADD COLUMN answered_automatically BOOLEAN NOT NULL DEFAULT FALSE;

-- =====================================================================
-- V4: Pharmacy — medicine catalogue and stock.
--
-- Second concurrency surface in the system. Stock decrement has the same
-- hazard as slot booking ("read 1 remaining, both sell it"), but a
-- different correct answer: uniqueness cannot help here because the
-- contended resource is a COUNT, not an identity.
--
-- The guard is therefore a CHECK constraint on the quantity column.
-- Combined with an atomic `UPDATE ... SET qty = qty - :n WHERE qty >= :n`
-- (see StockLedger), the database refuses to let stock go negative no
-- matter how many dispensers race. The UPDATE's own row lock serialises
-- the readers, so no explicit locking is needed in application code.
-- =====================================================================

CREATE TABLE medicines (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(160)  NOT NULL,
    generic_name     VARCHAR(160)  NOT NULL,
    manufacturer     VARCHAR(160),
    form             VARCHAR(32)   NOT NULL,   -- TABLET / SYRUP / INJECTION ...
    strength         VARCHAR(40),              -- "500mg"
    unit_price       NUMERIC(10,2) NOT NULL,
    prescription_required BOOLEAN  NOT NULL DEFAULT TRUE,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT medicines_price_positive CHECK (unit_price >= 0),
    CONSTRAINT medicines_form_check
        CHECK (form IN ('TABLET','CAPSULE','SYRUP','INJECTION','OINTMENT','DROPS','INHALER'))
);

-- Same brand + strength must not be catalogued twice.
CREATE UNIQUE INDEX uq_medicines_name_strength ON medicines (lower(name), coalesce(strength, ''));
CREATE INDEX idx_medicines_generic ON medicines (lower(generic_name)) WHERE active = TRUE;

-- Deferred from V3: the medicines table did not exist yet.
ALTER TABLE prescription_items
    ADD CONSTRAINT fk_presc_item_medicine
    FOREIGN KEY (medicine_id) REFERENCES medicines (id);


CREATE TABLE medicine_stock (
    medicine_id       UUID        PRIMARY KEY,
    quantity_on_hand  INTEGER     NOT NULL DEFAULT 0,
    reorder_level     INTEGER     NOT NULL DEFAULT 10,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_stock_medicine FOREIGN KEY (medicine_id)
        REFERENCES medicines (id) ON DELETE CASCADE,
    -- >>> THE OVERSELL GUARD <<<
    CONSTRAINT stock_never_negative CHECK (quantity_on_hand >= 0),
    CONSTRAINT stock_reorder_sane   CHECK (reorder_level >= 0)
);

-- Feeds the "what needs restocking" admin view.
CREATE INDEX idx_stock_below_reorder ON medicine_stock (medicine_id)
    WHERE quantity_on_hand <= reorder_level;


-- Append-only movement log. Every change to medicine_stock writes a row
-- here, so on-hand quantity is always reconstructable and discrepancies
-- are traceable to a cause.
CREATE TABLE stock_movements (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    medicine_id  UUID        NOT NULL,
    delta        INTEGER     NOT NULL,
    reason       VARCHAR(32) NOT NULL,
    reference_id UUID,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_movement_medicine FOREIGN KEY (medicine_id) REFERENCES medicines (id),
    CONSTRAINT movement_reason_check
        CHECK (reason IN ('RESTOCK','DISPENSE','ADJUSTMENT','EXPIRY','RETURN')),
    CONSTRAINT movement_delta_nonzero CHECK (delta <> 0)
);

CREATE INDEX idx_movements_medicine_time ON stock_movements (medicine_id, occurred_at DESC);

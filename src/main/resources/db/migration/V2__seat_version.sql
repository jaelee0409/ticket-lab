-- Optimistic locking for the seat claim path.
--
-- Deliberately NOT a JPA @Version field: that would apply to every seat write,
-- including the no-lock strategy, and the no-lock strategy has to keep losing
-- races for the experiment to have a control group.
ALTER TABLE seat ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
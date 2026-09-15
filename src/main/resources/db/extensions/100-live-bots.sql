--liquibase formatted sql

--changeset cosmic-bots:100-bot-usage
-- Windowed usage counters for live bots: LLM calls and tokens, chat replies, mesos residents pay players.
-- One row per counter per window (window_id is a day, 2026-09-15, or an hour, 2026-09-15T14).
CREATE TABLE bot_usage
(
    counter   VARCHAR(64) NOT NULL,
    window_id VARCHAR(16) NOT NULL,
    amount    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (counter, window_id)
);

--changeset cosmic-bots:101-resident-state
-- What an FM resident keeps between wakes that the game does not model: its market memory (per-item
-- price multipliers and how many units it listed last). Inventory, mesos and the shop itself stay where
-- the game keeps them.
CREATE TABLE resident_state
(
    name        VARCHAR(13) NOT NULL,
    market_json TEXT        NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (name)
);

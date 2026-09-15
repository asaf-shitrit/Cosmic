package bot.residents;

import config.ServerConfig;

/** The resident and shared live-bot settings from {@code config.yaml}, read once at startup. */
record ResidentConfig(
        boolean enabled,
        int castSize,
        int channel,
        int maxAwake,
        long sleepMinMs,
        long sleepMaxMs,
        long sessionMinMs,
        long sessionMaxMs,
        long wakeGapMs,
        long startupDelayMs,
        long catchUpWindowMs,
        boolean requireHuman,
        int dailyMesoCap,
        int maxPurchasesPerWake,
        double browseChance,
        int maxListings,
        int budgetCap,
        int companionReserve,
        boolean llmEnabled,
        int llmDailyCap,
        int llmHourlyCap,
        boolean llmRequireHuman,
        int planBaseTokens,
        int planPerBotTokens,
        int planMaxBatch,
        long planCacheTtlMs,
        boolean planForVariety,
        int chatRepliesPerHour,
        String knowledgeDir,
        String memoryDir) {

    static ResidentConfig from(ServerConfig s) {
        return new ResidentConfig(
                s.USE_RESIDENTS,
                s.RESIDENTS_CAST_SIZE,
                s.RESIDENTS_CHANNEL,
                s.RESIDENTS_MAX_AWAKE,
                s.RESIDENTS_SLEEP_MIN_SECONDS * 1000L,
                s.RESIDENTS_SLEEP_MAX_SECONDS * 1000L,
                s.RESIDENTS_SESSION_MIN_SECONDS * 1000L,
                s.RESIDENTS_SESSION_MAX_SECONDS * 1000L,
                s.RESIDENTS_WAKE_GAP_SECONDS * 1000L,
                s.RESIDENTS_STARTUP_DELAY_SECONDS * 1000L,
                s.RESIDENTS_CATCHUP_WINDOW_SECONDS * 1000L,
                s.RESIDENTS_REQUIRE_HUMAN_ONLINE,
                s.RESIDENTS_DAILY_MESO_CAP,
                s.RESIDENTS_MAX_PURCHASES_PER_WAKE,
                s.RESIDENTS_BROWSE_CHANCE,
                Math.max(1, Math.min(16, s.RESIDENTS_MAX_LISTINGS)),
                s.BOT_BUDGET_MAX_CONNECTED,
                s.BOT_BUDGET_COMPANION_RESERVE,
                s.LLM_ENABLED,
                s.LLM_DAILY_CALL_CAP,
                s.LLM_HOURLY_CALL_CAP,
                s.LLM_REQUIRE_HUMAN_ONLINE,
                s.LLM_PLAN_BASE_MAX_TOKENS,
                s.LLM_PLAN_PER_BOT_MAX_TOKENS,
                s.LLM_PLAN_MAX_BATCH,
                s.LLM_PLAN_CACHE_TTL_MINUTES * 60_000L,
                s.LLM_PLAN_FOR_VARIETY,
                s.LLM_CHAT_REPLIES_PER_HOUR,
                s.BOT_KNOWLEDGE_DIR,
                s.BOT_MEMORY_DIR);
    }

    WakeScheduler.Settings wakeSettings() {
        return new WakeScheduler.Settings(sleepMinMs, sleepMaxMs, sessionMinMs, sessionMaxMs, maxAwake, wakeGapMs, startupDelayMs,
                Math.max(startupDelayMs, catchUpWindowMs));
    }
}

package bot.residents;

import bot.budget.UsageLedger;
import bot.llm.LlmGateway;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * What every resident session shares, owned by {@link ResidentDirector}.
 *
 * @param gateway      null when no LLM is configured
 * @param chatExecutor runs LLM chat replies off the packet loop
 * @param memoryRoot   the {@code bot-memory} bundle, or null if the server has none
 */
record ResidentServices(ResidentConfig config, LlmGateway gateway, UsageLedger ledger, ExecutorService chatExecutor, Path memoryRoot) {
}

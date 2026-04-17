package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;

import java.util.List;

/**
 * Interface for deterministic slash commands.
 * Commands execute without LLM involvement — direct tool calls or logic.
 */
public interface SlashCommand {

    String name();

    String description();

    List<OutputBlock> execute(String args, String sessionId);
}

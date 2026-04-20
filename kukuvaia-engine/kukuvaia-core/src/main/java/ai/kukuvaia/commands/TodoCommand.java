package ai.kukuvaia.commands;

import ai.kukuvaia.memory.model.Commitment;
import ai.kukuvaia.memory.repository.CommitmentRepository;
import ai.kukuvaia.memory.repository.SessionRepository;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TableBlock;
import ai.kukuvaia.output.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /todo — lightweight commitment management for the user.
 *
 * Subcommands:
 *   /todo add <text>      — record a new commitment
 *   /todo list            — show open commitments
 *   /todo done <id-prefix>— mark commitment complete (8-char UUID prefix accepted)
 *   /todo drop <id-prefix>— mark commitment dropped
 *
 * Deterministic — no LLM involvement.
 */
@Component
public class TodoCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(TodoCommand.class);
    // TODO: resolve from auth context. AgentService uses the same hardcoded value.
    private static final String USER_ID = "bartek";
    private static final int LIST_LIMIT = 20;

    private final CommitmentRepository commitments;
    private final SessionRepository sessions;

    public TodoCommand(CommitmentRepository commitments, SessionRepository sessions) {
        this.commitments = commitments;
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "Lightweight commitments: /todo add|list|done <id>|drop <id>";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            return help();
        }

        int space = trimmed.indexOf(' ');
        String sub = space > 0 ? trimmed.substring(0, space).toLowerCase() : trimmed.toLowerCase();
        String rest = space > 0 ? trimmed.substring(space + 1).trim() : "";

        return switch (sub) {
            case "add" -> add(rest, sessionId);
            case "list", "ls" -> list();
            case "done" -> mark(rest, "done");
            case "drop", "cancel" -> mark(rest, "dropped");
            case "start" -> mark(rest, "in_progress");
            default -> help();
        };
    }

    private List<OutputBlock> add(String text, String sessionId) {
        if (text.isEmpty()) {
            return List.of(new TextBlock("Usage: /todo add <what needs to happen>", "error"));
        }
        sessions.ensureExists(sessionId, USER_ID);
        UUID id = commitments.create(USER_ID, sessionId, text, null, "explicit", null);
        String shortId = id.toString().substring(0, 8);
        return List.of(new TextBlock(
                "✓ Commitment saved (" + shortId + "): " + text, null));
    }

    private List<OutputBlock> list() {
        List<Commitment> open = commitments.findOpenByUser(USER_ID, LIST_LIMIT);
        if (open.isEmpty()) {
            return List.of(new TextBlock("No open commitments. Use `/todo add <text>` to record one.", null));
        }
        List<String> headers = List.of("id", "status", "age", "summary");
        List<List<String>> rows = new ArrayList<>();
        for (Commitment c : open) {
            rows.add(List.of(
                    c.id().toString().substring(0, 8),
                    c.status(),
                    formatAge(c.createdAt()),
                    truncate(c.summary(), 80)
            ));
        }
        return List.of(new TableBlock("Open commitments (" + open.size() + ")", headers, rows));
    }

    private List<OutputBlock> mark(String idPrefix, String status) {
        if (idPrefix.isEmpty()) {
            return List.of(new TextBlock("Usage: /todo " + status.replace("_", " ") +
                    " <id-prefix> (use /todo list to see ids)", "error"));
        }
        Optional<Commitment> match = resolveByPrefix(idPrefix);
        if (match.isEmpty()) {
            return List.of(new TextBlock("No open commitment matches id prefix '" + idPrefix + "'", "error"));
        }
        Commitment c = match.get();
        int updated = commitments.markStatus(c.id(), USER_ID, status);
        if (updated == 0) {
            return List.of(new TextBlock("Commitment not found or not yours.", "error"));
        }
        return List.of(new TextBlock(
                "✓ Commitment marked " + status + ": " + truncate(c.summary(), 80), null));
    }

    private Optional<Commitment> resolveByPrefix(String prefix) {
        return commitments.findOpenByUser(USER_ID, LIST_LIMIT).stream()
                .filter(c -> c.id().toString().startsWith(prefix))
                .findFirst();
    }

    private List<OutputBlock> help() {
        String msg = """
                `/todo` — commitments & pending tasks.

                Subcommands:
                - `/todo add <text>`    — record a new commitment
                - `/todo list`          — show open commitments
                - `/todo done <id>`     — mark complete
                - `/todo drop <id>`     — mark dropped
                - `/todo start <id>`    — mark in progress

                Short id prefix (first 8 chars) is enough for done/drop/start.""";
        return List.of(new TextBlock(msg, null));
    }

    private static String formatAge(Instant t) {
        if (t == null) return "-";
        long days = Duration.between(t, Instant.now()).toDays();
        if (days < 1) return "today";
        if (days == 1) return "1 day";
        if (days < 7) return days + " days";
        long weeks = days / 7;
        return weeks + "w";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}

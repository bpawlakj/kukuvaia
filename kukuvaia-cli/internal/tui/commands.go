package tui

import (
	"fmt"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/google/uuid"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// commandResultMsg carries response blocks from a slash command.
type commandResultMsg struct {
	blocks []api.OutputBlock
	err    error
}

// handleCommand processes slash commands. Returns true if handled, plus a tea.Cmd.
func (m *Model) handleCommand(input string) (bool, tea.Cmd) {
	parts := strings.SplitN(input[1:], " ", 2)
	cmd := strings.ToLower(parts[0])
	args := ""
	if len(parts) > 1 {
		args = parts[1]
	}

	switch cmd {
	case "quit", "exit":
		return true, tea.Quit

	case "clear":
		m.messages = nil
		m.viewport.SetContent(MutedStyle.Render("  Start a conversation..."))
		return true, nil

	case "new":
		// Create a new session
		m.session = uuid.New().String()
		m.messages = nil
		m.viewport.SetContent(MutedStyle.Render("  New session started. Type a message..."))
		return true, nil

	case "plan":
		lower := strings.ToLower(strings.TrimSpace(args))
		if lower == "cancel" {
			m.planning = planToolbar{}
		} else if lower != "" && lower != "status" {
			// /plan <task> — activate discovery toolbar
			m.planning = planToolbar{active: true, phase: "discovery", selected: 0}
		}
		// Forward to server
		m.waiting = true
		m.waitStart = time.Now()
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		return true, tea.Batch(m.sendPlan(args), tickElapsed())

	case "session":
		return true, m.fetchSessionInfo()

	case "sessions":
		// Open interactive session picker
		m.picker = NewSessionPicker()
		m.showPicker = true
		return true, m.fetchSessionsForPicker()

	case "rename":
		if args == "" {
			m.messages = append(m.messages, message{
				role:   "assistant",
				blocks: []api.OutputBlock{api.TextBlock{Content: "Usage: /rename <new name>"}},
			})
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return true, nil
		}
		m.waiting = true
		return true, m.renameCurrentSession(args)

	case "help":
		helpText := `Available commands:
  /plan <task>  — start planning mode (discovery → drafting → approval)
  /plan status  — show current planning phase
  /plan cancel  — cancel planning mode
  /new          — start a new session
  /session      — show current session info
  /sessions     — browse and switch sessions (↑↓ navigate)
  /rename <n>   — rename current session
  /clear        — clear chat history
  /help         — show this help
  /quit         — exit

Planning flow: /plan <task> → answer questions → "gotowe" → review plan → "tak"
Scrolling: pgup/pgdn, home/end
All other /commands are forwarded to the server.`
		m.messages = append(m.messages, message{
			role:   "assistant",
			blocks: []api.OutputBlock{api.TextBlock{Content: helpText}},
		})
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		return true, nil

	default:
		// Forward to server
		m.waiting = true
		return true, m.executeRemoteCommand(cmd, args)
	}
}

// fetchSessionsForPicker loads sessions into the picker.
func (m Model) fetchSessionsForPicker() tea.Cmd {
	return func() tea.Msg {
		sessions, err := m.client.ListSessions()
		return sessionsLoadedMsg{sessions: sessions, err: err}
	}
}

// renameCurrentSession renames the active session.
func (m Model) renameCurrentSession(name string) tea.Cmd {
	return func() tea.Msg {
		err := m.client.RenameSession(m.session, name)
		if err != nil {
			return commandResultMsg{err: err}
		}
		return commandResultMsg{
			blocks: []api.OutputBlock{api.TextBlock{
				Content: fmt.Sprintf("Session renamed to: **%s**", name),
			}},
		}
	}
}

// fetchSessionInfo retrieves current session details from the server.
func (m Model) fetchSessionInfo() tea.Cmd {
	return func() tea.Msg {
		info, err := m.client.GetSession(m.session)
		if err != nil {
			return commandResultMsg{err: err}
		}
		var text string
		if info != nil && info.Name != "" {
			text = fmt.Sprintf("Session: **%s** (%s) · %d messages", info.Name, truncateForDisplay(m.session), info.MessageCount)
		} else {
			text = fmt.Sprintf("Session: %s", m.session)
		}
		return commandResultMsg{
			blocks: []api.OutputBlock{api.TextBlock{Content: text}},
		}
	}
}

func truncateForDisplay(id string) string {
	if len(id) > 16 {
		return id[:8] + "…" + id[len(id)-4:]
	}
	return id
}

// sendPlan sends /plan command via chat SSE (server routes it to planning prompt).
func (m Model) sendPlan(task string) tea.Cmd {
	return func() tea.Msg {
		blocks, errs := m.client.Chat(m.session, "/plan "+task)

		var received []api.OutputBlock
		var lastErr error
		for blocks != nil || errs != nil {
			select {
			case block, ok := <-blocks:
				if !ok {
					blocks = nil
					continue
				}
				received = append(received, block)
			case err, ok := <-errs:
				if !ok {
					errs = nil
					continue
				}
				if err != nil {
					lastErr = err
				}
			}
		}
		return chatResultMsg{blocks: received, err: lastErr}
	}
}

// executeRemoteCommand sends a slash command to the server.
func (m Model) executeRemoteCommand(cmd, args string) tea.Cmd {
	return func() tea.Msg {
		blocks, err := m.client.ExecuteCommand(cmd, args, m.session)
		return commandResultMsg{blocks: blocks, err: err}
	}
}

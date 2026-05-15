package main

import (
	"fmt"
	"os"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
	"github.com/kukuvaia/kukuvaia-cli/internal/config"
	"github.com/kukuvaia/kukuvaia-cli/internal/tui"
)

var version = "dev"

func main() {
	// Handle --version before flag.Parse() in config.Load()
	if len(os.Args) > 1 && os.Args[1] == "--version" {
		fmt.Printf("kukuvaia %s\n", version)
		return
	}

	cfg := config.Load()
	client := api.NewClient(cfg.ServerURL)

	// Resume last session unless --new or explicit --session was given
	if !cfg.NewSession && os.Getenv("KUKUVAIA_SESSION") == "" && !flagProvided("session") {
		if lastID := resolveLastSession(client); lastID != "" {
			cfg.SessionID = lastID
		}
	}

	if err := tui.Run(client, cfg.SessionID, cfg.Persona); err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}

// resolveLastSession fetches the most recent session from the server.
func resolveLastSession(client *api.Client) string {
	sessions, err := client.ListSessions()
	if err != nil || len(sessions) == 0 {
		return ""
	}
	// Server returns sessions ordered by updated_at DESC — first is most recent
	s := sessions[0]
	if s.ID != "" {
		return s.ID
	}
	return s.SessionID
}

// flagProvided checks if a flag was explicitly passed on the command line.
func flagProvided(name string) bool {
	for _, arg := range os.Args[1:] {
		if arg == "-"+name || arg == "--"+name {
			return true
		}
		if len(arg) > len(name)+2 && arg[:len(name)+3] == "--"+name+"=" {
			return true
		}
	}
	return false
}

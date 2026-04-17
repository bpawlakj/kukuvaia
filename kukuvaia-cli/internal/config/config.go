package config

import (
	"flag"
	"os"
	"path/filepath"

	"github.com/google/uuid"
	"gopkg.in/yaml.v3"
)

// Config holds CLI configuration resolved from defaults → yaml → env → flags.
type Config struct {
	ServerURL string `yaml:"server_url"`
	SessionID string `yaml:"session_id"`
	Persona   string `yaml:"persona"`
	NewSession bool   `yaml:"-"` // --new flag, not persisted
}

// Load resolves configuration from all sources in priority order.
func Load() Config {
	cfg := Config{
		ServerURL: "http://localhost:8080",
	}

	// Layer 2: ~/.kukuvaia.yaml
	if home, err := os.UserHomeDir(); err == nil {
		path := filepath.Join(home, ".kukuvaia.yaml")
		if data, err := os.ReadFile(path); err == nil {
			_ = yaml.Unmarshal(data, &cfg)
		}
	}

	// Layer 3: environment variables
	if v := os.Getenv("KUKUVAIA_SERVER_URL"); v != "" {
		cfg.ServerURL = v
	}
	if v := os.Getenv("KUKUVAIA_SESSION"); v != "" {
		cfg.SessionID = v
	}
	if v := os.Getenv("KUKUVAIA_PERSONA"); v != "" {
		cfg.Persona = v
	}

	// Layer 4: CLI flags
	flag.StringVar(&cfg.ServerURL, "server", cfg.ServerURL, "kukuvaia-engine server URL")
	flag.StringVar(&cfg.SessionID, "session", cfg.SessionID, "session ID to resume")
	flag.StringVar(&cfg.Persona, "persona", cfg.Persona, "active persona name")
	flag.BoolVar(&cfg.NewSession, "new", false, "start a new session (ignore last session)")
	flag.Parse()

	// If --new flag or no session specified, generate new UUID
	if cfg.NewSession || cfg.SessionID == "" {
		cfg.SessionID = uuid.New().String()
	}

	return cfg
}

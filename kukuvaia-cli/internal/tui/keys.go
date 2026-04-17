package tui

import "github.com/charmbracelet/bubbles/key"

type keyMap struct {
	Submit key.Binding
	Quit   key.Binding
	Clear  key.Binding
}

var keys = keyMap{
	Submit: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "send message"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Clear: key.NewBinding(
		key.WithKeys("ctrl+l"),
		key.WithHelp("ctrl+l", "clear"),
	),
}

package main

import (
	"fmt"
	"strings"

	"github.com/charmbracelet/lipgloss"
)

// Kukuvaia theme colors — Matrix/terminal aesthetic
var (
	bright  = lipgloss.Color("#00FF41") // phosphor green — eyes, highlights
	medium  = lipgloss.Color("#00CC33") // mid green — primary structure
	dim     = lipgloss.Color("#008F11") // dark green — depth, shadows
	darkest = lipgloss.Color("#004400") // very dark — background accents
)

// Styles
var (
	eyeStyle = lipgloss.NewStyle().
			Foreground(bright).
			Bold(true)

	structStyle = lipgloss.NewStyle().
			Foreground(medium)

	dimStyle = lipgloss.NewStyle().
			Foreground(dim)

	darkStyle = lipgloss.NewStyle().
			Foreground(darkest)

	titleStyle = lipgloss.NewStyle().
			Foreground(bright).
			Bold(true)

	taglineStyle = lipgloss.NewStyle().
			Foreground(dim)
)

// Owl parts with color tags:
//   B = bright (eyes, key highlights)
//   M = medium (main structure)
//   D = dim (secondary lines, depth)
//   K = darkest (background accents)
type segment struct {
	text  string
	style lipgloss.Style
}

func b(s string) segment { return segment{s, eyeStyle} }
func m(s string) segment { return segment{s, structStyle} }
func d(s string) segment { return segment{s, dimStyle} }
func k(s string) segment { return segment{s, darkStyle} }

func renderLine(segments ...segment) string {
	var sb strings.Builder
	for _, seg := range segments {
		sb.WriteString(seg.style.Render(seg.text))
	}
	return sb.String()
}

func main() {
	fmt.Println()

	// === OWL ===
	owl := []string{
		// Ear tufts
		renderLine(d("            "), m("▄"), d("                         "), m("▄")),
		renderLine(d("           "), m("▐"), d("░"), m("▌"), d("                       "), m("▐"), d("░"), m("▌")),
		renderLine(d("          "), m("▐"), d("░░"), m("▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀"), d("░░"), m("▌")),
		renderLine(d("         "), m("▐"), d("░░░░░░░░░░░░░░░░░░░░░░░░░"), m("▌")),

		// Eyes
		renderLine(d("         "), m("▐"), d("░"), m("┌─────────┐"), d("░"), m("┌─────────┐"), d("░"), m("▌")),
		renderLine(d("         "), m("▐"), d("░"), m("│"), d("░░"), b("╔═════╗"), d("░░"), m("│"), d("░"), m("│"), d("░░"), b("╔═════╗"), d("░░"), m("│"), d("░"), m("▌")),
		renderLine(d("         "), m("▐"), d("░"), m("│"), d("░░"), b("║ ◉ ◉ ║"), d("░░"), m("│"), d("░"), m("│"), d("░░"), b("║ ◉ ◉ ║"), d("░░"), m("│"), d("░"), m("▌")),
		renderLine(d("         "), m("▐"), d("░"), m("│"), d("░░"), b("╚═════╝"), d("░░"), m("│"), d("░"), m("│"), d("░░"), b("╚═════╝"), d("░░"), m("│"), d("░"), m("▌")),
		renderLine(d("         "), m("▐"), d("░"), m("└────┬────┘"), d("░"), m("└────┬────┘"), d("░"), m("▌")),

		// Beak
		renderLine(d("          "), m("▀▄"), d("░░░░"), m("│"), d("░░"), m("╱▔▔╲"), d("░░"), m("│"), d("░░░░"), m("▄▀")),
		renderLine(d("           "), m("▐"), d("░░░░"), m("└──╲▄▄╱──┘"), d("░░░░"), m("▌")),

		// Body — circuit board
		renderLine(d("           "), m("▐"), d("░░"), m("╔══════════════╗"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("┌┬──────┬┐"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("│├──"), d("▓▓"), m("──┤│"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("││"), d("░░░░░░"), m("││"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("│├──"), d("▓▓"), m("──┤│"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("││"), d("░░░░░░"), m("││"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("║"), d("░"), m("└┴──────┴┘"), d("░"), m("║"), d("░░"), m("▌")),
		renderLine(d("           "), m("▐"), d("░░"), m("╚══════════════╝"), d("░░"), m("▌")),

		// Talons
		renderLine(d("            "), m("▀▄"), d("░░░"), m("╱▔▀▀▀▀▔╲"), d("░░░"), m("▄▀")),
		renderLine(d("              "), m("▀▀▄▄▀"), d("░░░░"), m("▀▄▄▀▀")),
		renderLine(d("                  "), m("▀▀▀▀")),
	}

	for _, line := range owl {
		fmt.Println(line)
	}

	fmt.Println()

	// === TITLE ===
	title := titleStyle.Render("         K U K U V A I A")
	fmt.Println(title)

	// === TAGLINE ===
	tagline := taglineStyle.Render("          wisdom agent")
	fmt.Println(tagline)

	fmt.Println()

	// === SEPARATOR ===
	sep := dimStyle.Render("    ─────────────────────────────")
	fmt.Println(sep)
	fmt.Println()
}

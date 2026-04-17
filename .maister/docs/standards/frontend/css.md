## CSS

### Consistent Methodology
Stick to the project's chosen approach (Tailwind, BEM, CSS modules, etc.) across the entire codebase.

### Work With the Framework
Use framework patterns as intended rather than fighting them with excessive overrides.

### Design Tokens
Establish and document consistent values for colors, spacing, and typography.

### Minimize Custom CSS
Prefer framework utilities to reduce custom styling maintenance.

### Production Optimization
Use CSS purging or tree-shaking to remove unused styles.

### Styles from Theme via go generate
Lipgloss styles are generated from `kukuvaia-theme.yaml` using `go generate`. No hardcoded color values or style constants in Go source files.

### Matrix Aesthetic
Visual identity uses phosphor green `#00FF41` on pure black `#000000`. High contrast, monospace feel. All UI elements follow this palette.

### Design Tokens in YAML
The theme file uses YAML format (not JSON or raw CSS) to support inline comments explaining design rationale for each token.

## Components

### Single Responsibility
Each component should do one thing well.

### Reusability
Design components to work across different contexts with configurable props.

### Composability
Build complex UIs by combining smaller components rather than creating monoliths.

### Clear Interface
Define explicit, documented props with sensible defaults.

### Encapsulation
Keep implementation details private; expose only what's necessary.

### Consistent Naming
Use descriptive names that indicate purpose and follow team conventions.

### Local State
Keep state as close to where it's used as possible; lift only when needed.

### Minimal Props
If a component needs many props, consider composition or splitting it.

### Documentation
Document usage, props, and examples to help team adoption.

### CLI Thin Client
The CLI contains no agent logic, no LLM interaction, and no database access. It is a pure presentation layer that renders server responses.

### Go Charm Stack
The CLI is built with Bubbletea (Elm architecture), Lipgloss (styling), Bubbles (reusable components), and Glamour (markdown rendering).

### Bubbletea Elm Architecture
All TUI components follow the Model/Update/View pattern. State mutations happen only in Update; View is a pure function of Model.

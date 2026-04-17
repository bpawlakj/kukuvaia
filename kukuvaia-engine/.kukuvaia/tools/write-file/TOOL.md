---
name: write_file
description: Write content to a file in the workspace. Creates parent directories automatically. Creates .bak backup before overwriting existing files.
readOnly: false
concurrencySafe: false
parameters:
  path:
    type: string
    description: "File path relative to workspace root"
    required: true
  content:
    type: string
    description: "Content to write to the file"
    required: true
---
- fs.write: {path: "${path}", content: "${content}"}
  as: result
- return: "${result}"

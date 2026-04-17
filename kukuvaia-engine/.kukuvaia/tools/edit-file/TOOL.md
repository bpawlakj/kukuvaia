---
name: edit_file
description: Edit a file by replacing exact text. Performs find-and-replace within a file. Creates .bak backup before editing.
readOnly: false
concurrencySafe: false
parameters:
  path:
    type: string
    description: "File path relative to workspace root"
    required: true
  old_text:
    type: string
    description: "Exact text to find in the file"
    required: true
  new_text:
    type: string
    description: "Replacement text"
    required: true
---
- fs.edit: {path: "${path}", old_text: "${old_text}", new_text: "${new_text}"}
  as: result
- return: "${result}"

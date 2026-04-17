---
name: search_content
description: Search for text across all files in the workspace. Returns matching files with context snippets around each match.
readOnly: true
concurrencySafe: true
parameters:
  query:
    type: string
    description: "Text to search for"
    required: true
  glob:
    type: string
    description: "Glob pattern to filter files (e.g. '*.md'). Omit to search all files."
    required: false
---
- fs.search: {query: "${query}", glob: "${glob}"}
  as: results
- return: "${results}"

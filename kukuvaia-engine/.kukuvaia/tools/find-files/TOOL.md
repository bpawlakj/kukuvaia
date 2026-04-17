---
name: find_files
description: Find files matching a glob pattern in the workspace. Returns a list of matching file paths relative to workspace root.
readOnly: true
concurrencySafe: true
parameters:
  pattern:
    type: string
    description: "Glob pattern to match files (e.g. '**/*.md', 'src/**/*.java', '*.yaml')"
    required: true
---
- fs.find: {glob: "${pattern}"}
  as: files
- return: "${files}"

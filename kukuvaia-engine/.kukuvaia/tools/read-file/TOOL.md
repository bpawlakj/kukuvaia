---
name: read_file
description: Read contents of a file from the workspace. Returns file content as text. Supports reading specific line ranges with offset and limit.
readOnly: true
concurrencySafe: true
parameters:
  path:
    type: string
    description: "File path relative to workspace root"
    required: true
  offset:
    type: integer
    description: "Start line (0-based). Omit to read from beginning."
    required: false
  limit:
    type: integer
    description: "Number of lines to read. Omit to read entire file."
    required: false
---
- lua: |
    if offset or limit then
      return fs.read_lines(path, offset or 0, limit or 9999)
    end
    return fs.read(path)

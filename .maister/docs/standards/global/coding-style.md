## Coding Style

### Naming Consistency
Follow established naming patterns for variables, functions, classes, and files throughout the project.

### Automatic Formatting
Use automated tools to enforce consistent indentation, spacing, and line breaks.

### Descriptive Names
Choose names that clearly communicate intent; avoid cryptic abbreviations or single-letter identifiers outside tight loops.

### Focused Functions
Write functions that do one thing well; smaller functions are easier to read, test, and maintain.

### Uniform Indentation
Standardize on spaces or tabs and enforce with editor/linter settings.

### No Dead Code
Remove unused imports, commented-out blocks, and orphaned functions instead of leaving them behind.

### No Backward Compatibility Unless Required
Avoid extra code paths for backward compatibility unless explicitly needed.

### DRY (Don't Repeat Yourself)
Extract repeated logic into reusable functions or modules.

### PascalCase Classes
All Java classes, records, and enums use PascalCase. This applies consistently across all production and test code.

### camelCase Methods and Fields
All methods, fields, parameters, and local variables use camelCase without exception.

### SCREAMING_SNAKE_CASE Constants
Static final constants use SCREAMING_SNAKE_CASE.

```java
public static final String DEFAULT_PROVIDER = "copilot";
private static final int MAX_DEPTH = 1;
```

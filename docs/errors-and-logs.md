# Errors and logs

When a script fails, chat says what went wrong in words, where, and shows the line:

```
[miner] "abc" isn't a number (line 3) [log] [edit]
  3 │ return parts[1].toInt()
```

- **Compile errors** underline the offending code in red (`[miner:2:17] Unresolved reference 'oers'.`). Only the
  first three are shown; the rest are in the log.
- **Hover** over the error to see where in the script it happened (`at line 3 in load`, `at line 7`), **click**
  it or `[log]` to open the log, and `[edit]` to open the file in your editor.
- Common exceptions are explained instead of named: `"abc" isn't a number`, `A value was null`, `Out of range: …`,
  `Endless recursion…`. `error("…")`, `require` and `check` show just your message.
- A misspelled script name suggests the closest one: `\autowlak` → `Did you mean \autowalk?`

**Logs.** Each script has a log, `minekt/logs/<script>.log`, recording when it started and ended, what it
echoed, every error with its stack trace (cut after the last line of your script), and anything it writes with
`writeLog(…)`, which goes to the log only, not chat. `\log <script>` or the manager's Log button opens it in the
game, newest first; the screen can open the file, copy it or clear it. Files over 1 MB are moved to
`<script>.old.log`.

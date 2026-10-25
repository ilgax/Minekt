# Installing and sharing scripts

```
\install https://github.com/someone/scripts/blob/main/autofarm.kts
```

Minekt downloads the script but **doesn't install it yet**. First it shows a preview in chat:
- the script's name and size, and whether it replaces one you already have
- a plain-language list of what it can do: "send chat messages or commands as you", "connect to the internet",
  "read or write files on your computer", "use raw game access", ...
- whether it compiles

Then click **[Install]**, **[View source]** (opens it in your editor) or **[Cancel]**, or type `\install yes` /
`\install no`. A preview expires after 10 minutes. Installed scripts remember where they came from, so
`\update autofarm` fetches the latest version through the same preview.

- GitHub, gist and pastebin page links are turned into raw-file links automatically; any raw link works.
- Only `https://` is accepted (plain `http` only for `localhost`), at most 512 KB, and web pages are rejected.
- **To share** a script, `\share autofarm` copies it to your clipboard. Paste it anywhere: Discord, a gist, a
  message. Others install it with `\install clipboard` (the name comes from its `// \autofarm` first line,
  or add one: `\install clipboard autofarm`).
- The capability list is a quick scan of the source, not a sandbox. Scripts can do anything on your computer, not
  just in the game. Only run scripts you trust.

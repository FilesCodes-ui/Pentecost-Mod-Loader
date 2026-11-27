#!/bin/sh
# Reads a commit message on stdin, writes it to stdout without AI attribution lines
# (Co-Authored-By: <AI tool>, session links, "generated with" lines) and without trailing blank lines.
sed -E '/^[[:space:]]*(co-authored-by:.*(claude|anthropic|openai|chatgpt|copilot|gemini|codex|cursor)|claude-session:|(generated|written|authored|created) (with|by) .*(claude|chatgpt|copilot|gemini|codex))/Id' \
  | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}'

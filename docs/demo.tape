# VHS tape for the MiniKV demo GIF.
#   1. install VHS:  brew install vhs
#   2. build once:   ./gradlew installDist
#   3. render:       vhs docs/demo.tape   ->   docs/demo.gif
#
# https://github.com/charmbracelet/vhs

Output docs/demo.gif

Set Shell "bash"
Set FontSize 18
Set Width 1180
Set Height 760
Set Padding 22
Set Theme "Dracula"
Set TypingSpeed 55ms

# --- setup (hidden): fresh data dir + use the installDist launcher ---
Hide
Type "rm -rf /tmp/mkv-demo && BIN=build/install/minikv/bin/minikv" Enter
Type "clear" Enter
Show

Type "# MiniKV — an LSM-tree key-value store, driven from its CLI"
Sleep 800ms
Enter
Type "$BIN /tmp/mkv-demo"
Enter
Sleep 1.5s

Type "put user:1001 Ada-Lovelace"
Sleep 400ms
Enter
Sleep 600ms

Type "put user:1002 Alan-Turing"
Sleep 400ms
Enter
Sleep 600ms

Type "get user:1001"
Sleep 400ms
Enter
Sleep 800ms

Type "# flush the in-memory memtable to an immutable SSTable"
Sleep 500ms
Enter
Type "flush"
Sleep 400ms
Enter
Sleep 800ms

Type "stats"
Sleep 400ms
Enter
Sleep 1s

Type "# delete writes a tombstone; the key reads back as (nil)"
Sleep 500ms
Enter
Type "del user:1002"
Sleep 400ms
Enter
Type "get user:1002"
Sleep 400ms
Enter
Sleep 1.2s

Type "stats"
Sleep 400ms
Enter
Sleep 1.2s

Type "exit"
Enter
Sleep 2s

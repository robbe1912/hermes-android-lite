package tunnel

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

// logCap is the number of log lines in memory. The ring keeps the memory of
// the node logs small.
const logCap = 300

// logWidth cuts a long log line.
const logWidth = 400

var (
	logMu   sync.Mutex
	logRing []string
)

// logf is the logger of the embedded node. It keeps the last lines in memory.
// Logs shows them. The app shows the lines on demand only.
func logf(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	line = strings.ReplaceAll(line, "\n", " ")
	if len(line) > logWidth {
		line = line[:logWidth] + "..."
	}
	line = time.Now().UTC().Format("15:04:05.000") + " " + line

	logMu.Lock()
	logRing = append(logRing, line)
	if len(logRing) > logCap {
		logRing = logRing[len(logRing)-logCap:]
	}
	logMu.Unlock()
}

// Logs returns the last n lines of the node log. n 0 or less returns all the
// lines in memory.
func Logs(n int) string {
	logMu.Lock()
	defer logMu.Unlock()
	if n <= 0 || n > len(logRing) {
		n = len(logRing)
	}
	return strings.Join(logRing[len(logRing)-n:], "\n")
}

// clearLogs empties the log. Tests use it.
func clearLogs() {
	logMu.Lock()
	logRing = nil
	logMu.Unlock()
}

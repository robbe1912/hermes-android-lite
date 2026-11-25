package tunnel

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
)

// startEcho starts a TCP echo server on 127.0.0.1. It returns the address.
func startEcho(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				io.Copy(c, c)
				c.Close()
			}()
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return ln.Addr().String()
}

// systemDial dials with the system network.
func systemDial(ctx context.Context, network, address string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, address)
}

// waitFor polls cond until it is true or the time is over.
func waitFor(cond func() bool) bool {
	deadline := time.Now().Add(2 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(5 * time.Millisecond)
	}
	return true
}

func TestForwarderCopiesDataBothWays(t *testing.T) {
	echo := startEcho(t)
	f, err := startForwarder("127.0.0.1:0", echo, systemDial)
	if err != nil {
		t.Fatalf("startForwarder: %v", err)
	}
	defer f.Close()
	if f.Port() == 0 {
		t.Fatal("Port is 0")
	}
	if f.Target() != echo {
		t.Fatalf("Target = %q, want %q", f.Target(), echo)
	}

	c, err := net.DialTimeout("tcp", f.ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial forwarder: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))

	payload := []byte("hello through the forwarder")
	if _, err := c.Write(payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("echo mismatch: %q", got)
	}

	c.Close()
	if !waitFor(func() bool { return f.active.Load() == 0 }) {
		t.Fatalf("active = %d, want 0", f.active.Load())
	}
	if got := f.total.Load(); got != 1 {
		t.Fatalf("total = %d, want 1", got)
	}
	if up, down := f.bytesUp.Load(), f.bytesDown.Load(); up != int64(len(payload)) || down != int64(len(payload)) {
		t.Fatalf("bytes up %d, down %d, want %d each", up, down, len(payload))
	}
	if got := f.lastError(); got != "" {
		t.Fatalf("lastError = %q, want empty", got)
	}
}

// TestForwarderOneSideCloseEndsBoth makes sure that no copy blocks when the
// target closes first.
func TestForwarderOneSideCloseEndsBoth(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Write([]byte("bye"))
			c.Close()
		}
	}()

	f, err := startForwarder("127.0.0.1:0", ln.Addr().String(), systemDial)
	if err != nil {
		t.Fatalf("startForwarder: %v", err)
	}
	defer f.Close()

	c, err := net.DialTimeout("tcp", f.ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial forwarder: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))

	got, err := io.ReadAll(c)
	if err != nil {
		t.Fatalf("read all: %v", err)
	}
	if string(got) != "bye" {
		t.Fatalf("read %q, want %q", got, "bye")
	}
	if !waitFor(func() bool { return f.active.Load() == 0 }) {
		t.Fatalf("active = %d, want 0 after the target closed", f.active.Load())
	}
}

func TestForwarderClosesClientWhenDialFails(t *testing.T) {
	wantErr := errors.New("no route")
	dial := func(context.Context, string, string) (net.Conn, error) { return nil, wantErr }
	f, err := startForwarder("127.0.0.1:0", "192.0.2.1:9", dial)
	if err != nil {
		t.Fatalf("startForwarder: %v", err)
	}
	defer f.Close()

	c, err := net.DialTimeout("tcp", f.ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial forwarder: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))

	// The forwarder must close the connection, so the read ends with EOF.
	if _, err := c.Read(make([]byte, 1)); !errors.Is(err, io.EOF) {
		t.Fatalf("read error = %v, want EOF", err)
	}
	if got := f.lastError(); got != wantErr.Error() {
		t.Fatalf("lastError = %q, want %q", got, wantErr.Error())
	}
	if !waitFor(func() bool { return f.active.Load() == 0 }) {
		t.Fatalf("active = %d, want 0", f.active.Load())
	}
}

func TestForwarderCloseStopsNewConnections(t *testing.T) {
	f, err := startForwarder("127.0.0.1:0", startEcho(t), systemDial)
	if err != nil {
		t.Fatalf("startForwarder: %v", err)
	}
	addr := f.ln.Addr().String()

	if err := f.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := f.Close(); err != nil {
		t.Fatalf("Close twice: %v", err)
	}
	c, err := net.DialTimeout("tcp", addr, 500*time.Millisecond)
	if err == nil {
		c.Close()
		t.Fatal("a connection after Close was accepted")
	}
}

func TestForwardRejectsBadInput(t *testing.T) {
	if _, err := Forward(-1, "192.0.2.1:9"); err == nil {
		t.Fatal("localPort -1 was accepted")
	}
	if _, err := Forward(0, "192.0.2.1"); err == nil {
		t.Fatal("target without a port was accepted")
	}
}

func TestForwardNeedsStartedNode(t *testing.T) {
	Stop()
	if _, err := Forward(0, "192.0.2.1:9"); !errors.Is(err, errStopped) {
		t.Fatalf("Forward error = %v, want errStopped", err)
	}
}

func TestStatusWhenNeverStarted(t *testing.T) {
	Stop()
	got, err := Status()
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	want := `{"state":"stopped","backend":"","loginUrl":"","ips":[],"dnsName":"","online":false,"health":[],"error":""}`
	if got != want {
		t.Fatalf("Status = %s, want %s", got, want)
	}
}

func TestForwardStatusWhenNeverOpened(t *testing.T) {
	CloseForward()
	got, err := ForwardStatus()
	if err != nil {
		t.Fatalf("ForwardStatus: %v", err)
	}
	want := `{"open":false,"port":0,"target":"","active":0,"total":0,"bytesUp":0,"bytesDown":0,"lastError":""}`
	if got != want {
		t.Fatalf("ForwardStatus = %s, want %s", got, want)
	}
}

func TestStartRejectsEmptyStateDir(t *testing.T) {
	if err := Start("", "host", "", "", false); err == nil {
		t.Fatal("Start with an empty stateDir was accepted")
	}
}

func TestCallsNeedStartedNode(t *testing.T) {
	Stop()
	if err := Login(); !errors.Is(err, errStopped) {
		t.Fatalf("Login error = %v, want errStopped", err)
	}
	if _, err := LoginURL(); !errors.Is(err, errStopped) {
		t.Fatalf("LoginURL error = %v, want errStopped", err)
	}
	if err := Logout(); !errors.Is(err, errStopped) {
		t.Fatalf("Logout error = %v, want errStopped", err)
	}
}

func TestFillStatusStates(t *testing.T) {
	cases := []struct {
		name    string
		backend string
		authURL string
		want    string
	}{
		{"running", "Running", "", "running"},
		{"needs login", "NeedsLogin", "", "needsLogin"},
		{"needs approval", "NeedsLogin", "https://example.invalid/a/x", "needsApproval"},
		{"needs machine auth", "NeedsMachineAuth", "", "needsApproval"},
		{"starting", "Starting", "", "starting"},
		{"no state", "NoState", "", "starting"},
		{"stopped", "Stopped", "", "stopped"},
		{"unknown state", "Paused", "", "stopped"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			st := nodeStatus{State: "stopped", IPs: []string{}, Health: []string{}}
			fillStatus(&st, &ipnstate.Status{BackendState: tc.backend, AuthURL: tc.authURL})
			if st.State != tc.want {
				t.Fatalf("state = %q, want %q", st.State, tc.want)
			}
			if st.LoginURL != tc.authURL {
				t.Fatalf("loginUrl = %q, want %q", st.LoginURL, tc.authURL)
			}
		})
	}
}

func TestFillStatusCopiesFields(t *testing.T) {
	st := nodeStatus{State: "stopped", IPs: []string{}, Health: []string{}}
	fillStatus(&st, &ipnstate.Status{
		BackendState: "Running",
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1"), netip.MustParseAddr("fd7a:115c:a1e0::1")},
		Health:       []string{"one warning"},
		Self:         &ipnstate.PeerStatus{Online: true, DNSName: "phone.example.ts.net."},
	})
	if st.State != "running" || st.Backend != "Running" {
		t.Fatalf("state %q, backend %q", st.State, st.Backend)
	}
	if got := strings.Join(st.IPs, ","); got != "100.64.0.1,fd7a:115c:a1e0::1" {
		t.Fatalf("ips = %s", got)
	}
	if !st.Online || st.DNSName != "phone.example.ts.net." {
		t.Fatalf("online %v, dnsName %q", st.Online, st.DNSName)
	}
	if len(st.Health) != 1 || st.Health[0] != "one warning" {
		t.Fatalf("health = %v", st.Health)
	}
}

func TestFillStatusNilIsStopped(t *testing.T) {
	st := nodeStatus{State: "starting", IPs: []string{}, Health: []string{}}
	fillStatus(&st, nil)
	if st.State != "stopped" {
		t.Fatalf("state = %q, want stopped", st.State)
	}
}

func TestLogRingKeepsLastLines(t *testing.T) {
	clearLogs()
	total := logCap + 20
	for i := range total {
		logf("line %d", i)
	}
	if len(logRing) != logCap {
		t.Fatalf("ring length = %d, want %d", len(logRing), logCap)
	}
	// The ring keeps the newest lines. The first line that fits is line 20.
	if !strings.HasSuffix(logRing[0], " line 20") {
		t.Fatalf("first line = %q, want the suffix %q", logRing[0], " line 20")
	}
	if !strings.HasSuffix(logRing[len(logRing)-1], fmtInt(total-1)) {
		t.Fatalf("last line = %q, want the suffix %q", logRing[len(logRing)-1], fmtInt(total-1))
	}

	all := Logs(0)
	if got := strings.Count(all, "\n") + 1; got != logCap {
		t.Fatalf("Logs(0) returned %d lines, want %d", got, logCap)
	}
	two := Logs(2)
	if strings.Count(two, "\n") != 1 || !strings.HasSuffix(two, fmtInt(total-1)) {
		t.Fatalf("Logs(2) = %q", two)
	}

	logf("with\nnewline")
	if got := Logs(1); !strings.HasSuffix(got, "with newline") {
		t.Fatalf("newline was not replaced: %q", got)
	}

	logf("%s", strings.Repeat("x", logWidth+50))
	wantLen := len("15:04:05.000 ") + logWidth + len("...")
	if got := Logs(1); len(got) != wantLen {
		t.Fatalf("long line length = %d, want %d", len(got), wantLen)
	}
}

func fmtInt(i int) string { return " line " + strconv.Itoa(i) }

// TestStartIntegration starts a real node without a key. It needs network
// access to the Tailscale control server. The node is ephemeral, so it
// leaves the tailnet when the test ends.
//
// TSNET_INTEGRATION=link stops when the node has a login link.
// TSNET_INTEGRATION=full waits until a user approves the link. Then it
// forwards a local port to TSNET_FORWARD_TARGET and fetches /api/health.
func TestStartIntegration(t *testing.T) {
	mode := os.Getenv("TSNET_INTEGRATION")
	if mode != "link" && mode != "full" {
		t.Skip("set TSNET_INTEGRATION=link or full to run")
	}
	defer Stop()
	stateDir := filepath.Join(t.TempDir(), "state")
	if err := Start(stateDir, "hermes-lite-test", "", "", true); err != nil {
		t.Fatalf("Start: %v", err)
	}
	st := waitForState(t, "needsApproval", time.Minute)
	if st.LoginURL == "" {
		t.Fatalf("state needsApproval without a link: %s", mustStatus(t))
	}
	t.Logf("login link: %s", st.LoginURL)
	if mode != "full" {
		return
	}
	st = waitForState(t, "running", 5*time.Minute)
	t.Logf("node: %s %s", st.DNSName, strings.Join(st.IPs, " "))

	target := os.Getenv("TSNET_FORWARD_TARGET")
	if target == "" {
		t.Skip("TSNET_FORWARD_TARGET is empty")
	}
	port, err := Forward(0, target)
	if err != nil {
		t.Fatalf("Forward: %v", err)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/health", port))
	if err != nil {
		t.Fatalf("GET through the forward: %v\n%s", err, Logs(30))
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/health through the forward: status %d", resp.StatusCode)
	}
	forward, err := ForwardStatus()
	if err != nil {
		t.Fatalf("ForwardStatus: %v", err)
	}
	t.Logf("forward: %s", forward)
}

// waitForState polls the node until it has the goal state. It starts the
// login when the node has no link. It fails after the time is over.
func waitForState(t *testing.T, goal string, limit time.Duration) nodeStatus {
	t.Helper()
	deadline := time.Now().Add(limit)
	for {
		st := nodeStatus{}
		got := mustStatus(t)
		if err := json.Unmarshal([]byte(got), &st); err != nil {
			t.Fatalf("Status is not JSON: %v: %s", err, got)
		}
		switch st.State {
		case goal:
			return st
		case "needsLogin":
			if err := Login(); err != nil {
				t.Fatalf("Login: %v", err)
			}
		case "error":
			t.Fatalf("node error: %s\n%s", st.Error, Logs(30))
		}
		if time.Now().After(deadline) {
			t.Fatalf("state is %s after %s\n%s", st.State, limit, Logs(30))
		}
		time.Sleep(2 * time.Second)
	}
}

// mustStatus returns the node status as JSON.
func mustStatus(t *testing.T) string {
	t.Helper()
	got, err := Status()
	if err != nil {
		t.Fatalf("Status: %v", err)
	}
	return got
}

// Package tunnel embeds a Tailscale node in the app process and forwards one
// local TCP port to one target through the tailnet.
//
// gomobile builds this package into an Android library. gomobile exposes
// exported package functions with simple types only. So the API uses strings,
// integers and errors, and it reports state as JSON.
//
// The process has at most one embedded node and at most one open forward.
package tunnel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

// statusTimeout bounds each call to the embedded node.
const statusTimeout = 10 * time.Second

var (
	// mu guards server and openForward.
	mu          sync.Mutex
	server      *tsnet.Server
	openForward *forwarder

	errStopped = errors.New("tunnel is not started")
)

// Start starts the embedded tailnet node. It returns when the node runs. It
// does not wait for the network or for the login.
//
// stateDir is a private directory for the node state and keys. hostname is
// the device name in the tailnet. controlURL is "" for Tailscale, or the URL
// of a Headscale server. authKey is a key from the admin console, or "" to
// log in with a link (see Login and Status). ephemeral true removes the
// device from the tailnet when it goes offline.
func Start(stateDir, hostname, controlURL, authKey string, ephemeral bool) error {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return errors.New("tunnel is already started")
	}
	if stateDir == "" {
		return errors.New("stateDir is empty")
	}
	if hostname == "" {
		hostname = "hermes-lite"
	}
	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Ephemeral:  ephemeral,
		Logf:       logger.Logf(logf),
		UserLogf:   logger.Logf(logf),
	}
	if err := s.Start(); err != nil {
		return fmt.Errorf("start tailnet node: %w", err)
	}
	// The node accepts the subnet routes of the tailnet. The gateway is on
	// the LAN, behind the tailnet subnet router. Without this preference the
	// node drops packets to LAN addresses.
	lc, err := s.LocalClient()
	if err != nil {
		s.Close()
		return fmt.Errorf("tailnet node client: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
	defer cancel()
	if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{
		Prefs:       ipn.Prefs{RouteAll: true},
		RouteAllSet: true,
	}); err != nil {
		s.Close()
		return fmt.Errorf("accept subnet routes: %w", err)
	}
	server = s
	return nil
}

// Stop stops the node and closes the forward. The state on disk stays.
func Stop() error {
	mu.Lock()
	s, f := server, openForward
	server, openForward = nil, nil
	mu.Unlock()
	if f != nil {
		f.Close()
	}
	if s == nil {
		return nil
	}
	return s.Close()
}

// Logout logs the node out of the tailnet, then stops it. The next Start
// needs a new key or a new interactive login.
func Logout() error {
	lc, err := localClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
	defer cancel()
	if err := lc.Logout(ctx); err != nil {
		return fmt.Errorf("logout: %w", err)
	}
	return Stop()
}

// Login starts an interactive login. The user opens the link from LoginURL
// and approves the device. Poll Status until the state is "running".
// It is a no-op when the node already has a link.
func Login() error {
	lc, err := localClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// LoginURL returns the link for the interactive login. It returns "" when
// there is no link.
func LoginURL() (string, error) {
	lc, err := localClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
	defer cancel()
	ps, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	if ps == nil {
		return "", nil
	}
	return ps.AuthURL, nil
}

// nodeStatus is the JSON shape of Status.
//
//	state:  stopped | starting | needsLogin | needsApproval | running | error
//	        needsLogin: call Login, then poll. needsApproval: open loginUrl.
type nodeStatus struct {
	State    string   `json:"state"`
	Backend  string   `json:"backend"`
	LoginURL string   `json:"loginUrl"`
	IPs      []string `json:"ips"`
	DNSName  string   `json:"dnsName"`
	Online   bool     `json:"online"`
	Health   []string `json:"health"`
	Error    string   `json:"error"`
}

// Status reports the node as JSON. A node that never started is "stopped".
func Status() (string, error) {
	st := nodeStatus{State: "stopped", IPs: []string{}, Health: []string{}}
	lc, err := localClient()
	switch {
	case errors.Is(err, errStopped):
		// The default is the stopped state.
	case err != nil:
		st.State, st.Error = "error", err.Error()
	default:
		ctx, cancel := context.WithTimeout(context.Background(), statusTimeout)
		ps, err := lc.Status(ctx)
		cancel()
		if err != nil {
			st.State, st.Error = "error", err.Error()
		} else {
			fillStatus(&st, ps)
		}
	}
	b, err := json.Marshal(st)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// fillStatus copies the node status into the JSON shape. ps nil is "stopped".
func fillStatus(st *nodeStatus, ps *ipnstate.Status) {
	if ps == nil {
		st.State = "stopped"
		return
	}
	st.Backend = ps.BackendState
	st.LoginURL = ps.AuthURL
	for _, ip := range ps.TailscaleIPs {
		st.IPs = append(st.IPs, ip.String())
	}
	st.Health = append(st.Health, ps.Health...)
	if ps.Self != nil {
		st.Online = ps.Self.Online
		st.DNSName = ps.Self.DNSName
	}
	switch ps.BackendState {
	case "Running":
		st.State = "running"
	case "NeedsLogin":
		if ps.AuthURL == "" {
			st.State = "needsLogin"
		} else {
			st.State = "needsApproval"
		}
	case "NeedsMachineAuth":
		st.State = "needsApproval"
	case "Starting":
		st.State = "starting"
	default:
		// NoState, Stopped, and states that this code does not know.
		st.State = "stopped"
	}
}

// localClient returns the client of the running node.
func localClient() (*local.Client, error) {
	mu.Lock()
	s := server
	mu.Unlock()
	if s == nil {
		return nil, errStopped
	}
	return s.LocalClient()
}

// Forward opens a local TCP port on 127.0.0.1. It forwards each connection
// to target (host:port) through the tailnet. localPort 0 picks a free port.
// A forward that is open closes first. The function returns the open port.
//
// Other apps on the device can reach a local port. So keep the forward open
// only while the app uses it, and close it with CloseForward.
func Forward(localPort int, target string) (int, error) {
	if localPort < 0 || localPort > 65535 {
		return 0, errors.New("localPort is out of range")
	}
	if _, _, err := net.SplitHostPort(target); err != nil {
		return 0, fmt.Errorf("target must be host:port: %w", err)
	}
	mu.Lock()
	s := server
	mu.Unlock()
	if s == nil {
		return 0, errStopped
	}
	f, err := startForwarder(fmt.Sprintf("127.0.0.1:%d", localPort), target, s.Dial)
	if err != nil {
		return 0, err
	}
	mu.Lock()
	old := openForward
	openForward = f
	mu.Unlock()
	if old != nil {
		old.Close()
	}
	return f.Port(), nil
}

// CloseForward closes the local port. It is a no-op when no port is open.
func CloseForward() error {
	mu.Lock()
	f := openForward
	openForward = nil
	mu.Unlock()
	if f == nil {
		return nil
	}
	return f.Close()
}

// forwardStatus is the JSON shape of ForwardStatus.
type forwardStatus struct {
	Open      bool   `json:"open"`
	Port      int    `json:"port"`
	Target    string `json:"target"`
	Active    int64  `json:"active"`
	Total     int64  `json:"total"`
	BytesUp   int64  `json:"bytesUp"`
	BytesDown int64  `json:"bytesDown"`
	LastError string `json:"lastError"`
}

// ForwardStatus reports the local port as JSON. bytesUp goes from the app to
// the tailnet. bytesDown goes from the tailnet to the app.
func ForwardStatus() (string, error) {
	mu.Lock()
	f := openForward
	mu.Unlock()
	st := forwardStatus{}
	if f != nil {
		st.Open = true
		st.Port = f.Port()
		st.Target = f.Target()
		st.Active = f.active.Load()
		st.Total = f.total.Load()
		st.BytesUp = f.bytesUp.Load()
		st.BytesDown = f.bytesDown.Load()
		st.LastError = f.lastError()
	}
	b, err := json.Marshal(st)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

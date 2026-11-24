package tunnel

import (
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// dialTimeout bounds the first connection. The first connection through a
// new node can take a few seconds.
const dialTimeout = 20 * time.Second

// dialFunc opens a connection to address. tsnet.Server.Dial has this shape.
type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// forwarder listens on a local port. It forwards each connection to one
// target.
type forwarder struct {
	ln     net.Listener
	target string
	dial   dialFunc

	mu     sync.Mutex
	closed bool

	active    atomic.Int64
	total     atomic.Int64
	bytesUp   atomic.Int64 // app to tailnet
	bytesDown atomic.Int64 // tailnet to app

	errMu   sync.Mutex
	lastErr string
}

// startForwarder listens on addr and starts to serve. It does not wait for
// the first connection.
func startForwarder(addr, target string, dial dialFunc) (*forwarder, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	f := &forwarder{ln: ln, target: target, dial: dial}
	go f.serve()
	return f, nil
}

// Port returns the open local port.
func (f *forwarder) Port() int {
	if a, ok := f.ln.Addr().(*net.TCPAddr); ok {
		return a.Port
	}
	return 0
}

// Target returns the target as host:port.
func (f *forwarder) Target() string { return f.target }

// Close closes the local port. Open connections stay open until one side
// closes them. Close twice is safe.
func (f *forwarder) Close() error {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil
	}
	f.closed = true
	f.mu.Unlock()
	return f.ln.Close()
}

func (f *forwarder) isClosed() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.closed
}

func (f *forwarder) setLastErr(msg string) {
	f.errMu.Lock()
	f.lastErr = msg
	f.errMu.Unlock()
}

func (f *forwarder) lastError() string {
	f.errMu.Lock()
	defer f.errMu.Unlock()
	return f.lastErr
}

// serve accepts connections until Close.
func (f *forwarder) serve() {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			if f.isClosed() {
				return
			}
			// A transient error, for example too many open files.
			time.Sleep(50 * time.Millisecond)
			continue
		}
		go f.pipe(c)
	}
}

// pipe connects c to the target and copies data in both directions. It
// returns when one side closes. Then it closes both sides.
func (f *forwarder) pipe(c net.Conn) {
	f.total.Add(1)
	f.active.Add(1)
	defer func() {
		c.Close()
		f.active.Add(-1)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	up, err := f.dial(ctx, "tcp", f.target)
	cancel()
	if err != nil {
		f.setLastErr(err.Error())
		return
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		io.Copy(up, &countingReader{r: c, n: &f.bytesUp})
		up.Close()
	}()
	io.Copy(c, &countingReader{r: up, n: &f.bytesDown})
	// The end of one direction ends the connection. This Close makes the
	// copy in the other direction stop.
	c.Close()
	<-done
}

// countingReader counts the bytes that it reads.
type countingReader struct {
	r io.Reader
	n *atomic.Int64
}

func (c *countingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n.Add(int64(n))
	return n, err
}

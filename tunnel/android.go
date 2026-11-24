//go:build android

package tunnel

// Android denies apps the netlink route socket and /proc/net. So net.Interfaces
// fails there, and the node cannot start (Tailscale issue 2293, golang/go#40569).
// The androidbin feature of Tailscale registers a fallback: one synthetic
// interface with the outbound addresses of the device. It also registers the
// DNS resolver of Android.
import _ "tailscale.com/feature/androidbin"

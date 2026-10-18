package engine

import "testing"

func TestProxyAddress(t *testing.T) {
	cases := []struct {
		host string
		port int32
		want string
		ok   bool
	}{
		{"127.0.0.1", 1080, "127.0.0.1:1080", true},
		{"::1", 7890, "[::1]:7890", true},
		{"[::1]", 7890, "[::1]:7890", true},
		{"localhost", 1080, "", false},
		{"127.0.0.1", 0, "", false},
		{"127.0.0.1", 65536, "", false},
	}
	for _, c := range cases {
		got, err := proxyAddress(c.host, c.port)
		if c.ok != (err == nil) {
			t.Fatalf("%s:%d: err = %v", c.host, c.port, err)
		}
		if c.ok && got.String() != c.want {
			t.Fatalf("%s:%d: got %s, want %s", c.host, c.port, got, c.want)
		}
	}
}

func TestStartRejectsBadFd(t *testing.T) {
	if _, err := Start(&Options{Fd: 0, ProxyHost: "127.0.0.1", ProxyPort: 1080}, nil); err == nil {
		t.Fatal("expected error")
	}
	if _, err := Start(nil, nil); err == nil {
		t.Fatal("expected error")
	}
}
